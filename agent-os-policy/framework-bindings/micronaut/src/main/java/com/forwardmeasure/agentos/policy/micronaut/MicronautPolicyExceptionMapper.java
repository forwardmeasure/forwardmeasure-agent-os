/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at https://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License.
 */
package com.forwardmeasure.agentos.policy.micronaut;

import com.forwardmeasure.agentos.policy.jaxrs.PolicyExceptionMapper;
import jakarta.inject.Singleton;

/**
 * Micronaut compile-time discovery edge for {@link PolicyExceptionMapper} - a JAX-RS mapper in the
 * framework-neutral jaxrs module is invisible to micronaut-jaxrs's exception handling unless a bean
 * compiled with micronaut-jaxrs-processor stands in for it. Without it every failure, a 401/403
 * included, falls through to Micronaut's own handlers instead of this service's RFC 9457 problem.
 */
@Singleton
public final class MicronautPolicyExceptionMapper extends PolicyExceptionMapper {}
