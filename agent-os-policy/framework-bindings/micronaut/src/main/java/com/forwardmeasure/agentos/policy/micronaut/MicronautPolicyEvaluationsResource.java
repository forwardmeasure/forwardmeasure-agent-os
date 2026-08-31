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

import com.forwardmeasure.agentos.domain.AgentActorResolver;
import com.forwardmeasure.agentos.domain.PolicyEvaluator;
import com.forwardmeasure.agentos.policy.jaxrs.PolicyEvaluationsResource;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

// Micronaut compile-time discovery edge for the framework-neutral resource - see
// agent-os-execution-micronaut's own MicronautAgentExecutionsResource for why this thin subclass
// is needed.
@Singleton
public final class MicronautPolicyEvaluationsResource extends PolicyEvaluationsResource {

  @Inject
  public MicronautPolicyEvaluationsResource(
      PolicyEvaluator policyEvaluator, AgentActorResolver agentActors) {
    super(policyEvaluator, agentActors);
  }
}
