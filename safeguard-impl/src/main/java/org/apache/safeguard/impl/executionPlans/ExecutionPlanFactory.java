/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.safeguard.impl.executionPlans;

import org.apache.safeguard.api.bulkhead.Bulkhead;
import org.apache.safeguard.api.bulkhead.BulkheadBuilder;
import org.apache.safeguard.api.bulkhead.BulkheadManager;
import org.apache.safeguard.impl.circuitbreaker.FailsafeCircuitBreaker;
import org.apache.safeguard.impl.circuitbreaker.FailsafeCircuitBreakerBuilder;
import org.apache.safeguard.impl.circuitbreaker.FailsafeCircuitBreakerManager;
import org.apache.safeguard.impl.fallback.FallbackRunner;
import org.apache.safeguard.impl.retry.FailsafeRetryBuilder;
import org.apache.safeguard.impl.retry.FailsafeRetryDefinition;
import org.apache.safeguard.impl.retry.FailsafeRetryManager;
import org.apache.safeguard.impl.util.NamingUtil;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.safeguard.impl.executionPlans.MicroprofileAnnotationMapper.mapCircuitBreaker;
import static org.apache.safeguard.impl.executionPlans.MicroprofileAnnotationMapper.mapRetry;
import static org.apache.safeguard.impl.util.AnnotationUtil.getAnnotation;

public class ExecutionPlanFactory {
    private final FailsafeCircuitBreakerManager circuitBreakerManager;
    private final FailsafeRetryManager retryManager;
    private final BulkheadManager bulkheadManager;
    private ConcurrentMap<String, ExecutionPlan> executionPlanMap = new ConcurrentHashMap<>();
    private final boolean enableAllMicroProfileFeatures;

    public ExecutionPlanFactory(FailsafeCircuitBreakerManager circuitBreakerManager,
                                FailsafeRetryManager retryManager,
                                BulkheadManager bulkheadManager) {
        this.circuitBreakerManager = circuitBreakerManager;
        this.retryManager = retryManager;
        this.bulkheadManager = bulkheadManager;
        this.enableAllMicroProfileFeatures = this.enableNonFallbacksForMicroProfile();
    }

    public ExecutionPlan locateExecutionPlan(String name, Duration timeout, boolean async) {
        return executionPlanMap.computeIfAbsent(name, key -> {
            FailsafeCircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker(key);
            FailsafeRetryDefinition retryDefinition = retryManager.getRetryDefinition(key);
            if (circuitBreaker == null && retryDefinition == null) {
                return null;
            } else {
                return new SyncFailsafeExecutionPlan(retryDefinition, circuitBreaker, null);
            }
        });
    }

    public ExecutionPlan locateExecutionPlan(Method method) {
        final String name = NamingUtil.createName(method);
        return executionPlanMap.computeIfAbsent(name, key -> {
            FailsafeCircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker(name);
            if (circuitBreaker == null) {
                circuitBreaker = createCBDefinition(name, method);
            }
            FailsafeRetryDefinition retryDefinition = retryManager.getRetryDefinition(name);
            if (retryDefinition == null) {
                retryDefinition = createDefinition(name, method);
            }
            Bulkhead bulkhead = bulkheadManager.getBulkhead(name);
            if (bulkhead == null) {
                bulkhead = createBulkhead(name, method);
            }
            boolean isAsync = isAsync(method);
            Duration timeout = readTimeout(method);
            FallbackRunner fallbackRunner = this.createFallback(method);
            if(this.enableAllMicroProfileFeatures) {
                BulkheadExecutionPlan parent = new BulkheadExecutionPlan(bulkhead);
                if (circuitBreaker == null && retryDefinition == null && isAsync) {
                    if (timeout == null) {
                        parent.setChild(new AsyncOnlyExecutionPlan(null));
                    } else {
                        parent.setChild(new AsyncTimeoutExecutionPlan(timeout, Executors.newFixedThreadPool(5)));
                    }
                } else if (circuitBreaker == null && retryDefinition == null && timeout != null) {
                    // then its just timeout
                    parent.setChild(new AsyncTimeoutExecutionPlan(timeout, Executors.newFixedThreadPool(5)));
                } else {
                    if (isAsync || timeout != null) {
                        parent.setChild(new AsyncFailsafeExecutionPlan(retryDefinition, circuitBreaker, fallbackRunner, Executors.newScheduledThreadPool(5), timeout));;
                    } else if(circuitBreaker == null && retryDefinition == null && fallbackRunner == null) {
                        parent.setChild(new BasicExecutionPlan());
                    } else if(circuitBreaker == null && retryDefinition == null) {
                        parent.setChild(new FallbackOnlyExecutionPlan(fallbackRunner));
                    } else {
                        parent.setChild(new SyncFailsafeExecutionPlan(retryDefinition, circuitBreaker, fallbackRunner));
                    }
                }
                return parent;
            }else {
                if(fallbackRunner == null) {
                    return new BasicExecutionPlan();
                }
                else {
                    return new FallbackOnlyExecutionPlan(fallbackRunner);
                }
            }

        });
    }

    private boolean enableNonFallbacksForMicroProfile() {
        try {
            Class.forName("org.eclipse.microprofile.config.Config");
            Config config = ConfigProvider.getConfig();
            AtomicBoolean disableExecutions = new AtomicBoolean(true);
            config.getOptionalValue("MP_Fault_Tolerance_NonFallback_Enabled", Boolean.class)
                    .ifPresent(disableExecutions::set);
            return disableExecutions.get();
        } catch (ClassNotFoundException e) {
            return true;
        }
    }

    private FailsafeRetryDefinition createDefinition(String name, Method method) {
        Retry retry = getAnnotation(method, Retry.class);
        if (retry == null) {
            return null;
        }
        FailsafeRetryBuilder retryBuilder = retryManager.newRetryDefinition(name);
        return mapRetry(retry, retryBuilder);
    }

    private FailsafeCircuitBreaker createCBDefinition(String name, Method method) {
        CircuitBreaker circuitBreaker = getAnnotation(method, CircuitBreaker.class);
        if (circuitBreaker == null) {
            return null;
        }
        FailsafeCircuitBreakerBuilder circuitBreakerBuilder = this.circuitBreakerManager.newCircuitBreaker(name);
        return new FailsafeCircuitBreaker(mapCircuitBreaker(circuitBreaker, circuitBreakerBuilder));
    }

    private Bulkhead createBulkhead(String name, Method method) {
        org.eclipse.microprofile.faulttolerance.Bulkhead annotation = getAnnotation(method,
                org.eclipse.microprofile.faulttolerance.Bulkhead.class);
        if (annotation == null) {
            return null;
        }
        boolean async = getAnnotation(method, Asynchronous.class) != null;
        BulkheadBuilder bulkheadBuilder = this.bulkheadManager.newBulkheadBuilder(name)
                .withMaxWaiting(annotation.waitingTaskQueue())
                .withMaxConcurrency(annotation.value());
        if(async) {
            bulkheadBuilder.asynchronous();
        }
        bulkheadBuilder.build();
        return bulkheadManager.getBulkhead(name);
    }

    private FallbackRunner createFallback(Method method) {
        Fallback fallback = getAnnotation(method, Fallback.class);
        if(fallback == null) {
            return null;
        }
        String methodName = "".equals(fallback.fallbackMethod()) ? null : fallback.fallbackMethod();
        return new FallbackRunner(fallback.value(), methodName);
    }

    private boolean isAsync(Method method) {
        return getAnnotation(method, Asynchronous.class) != null &&
                getAnnotation(method, org.eclipse.microprofile.faulttolerance.Bulkhead.class) == null;
    }

    private Duration readTimeout(Method method) {
        Timeout timeout = getAnnotation(method, Timeout.class);
        if(timeout == null) {
            return null;
        }
        return Duration.of(timeout.value(), timeout.unit());
    }
}
