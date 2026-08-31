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
package com.forwardmeasure.agentos.policy.jaxrs;

import com.forwardmeasure.agentos.domain.AgentActorResolver;
import com.forwardmeasure.agentos.domain.PolicyEvaluator;
import com.forwardmeasure.agentos.policy.api.PolicyEvaluationsApi;
import com.forwardmeasure.agentos.policy.api.model.PolicyEvaluationRequest;
import jakarta.ws.rs.core.Response;
import java.util.Objects;

// Hosted identically by every agent-os-policy-{fw} module - see agent-os-execution-jaxrs's own
// AgentExecutionsResource for the shared shape. Deliberately has no wire mapper: unlike
// AgentExecution, PolicyEvaluationRequest/Result carry no independent domain behavior, so the
// generated wire type IS the PolicyEvaluator port's own request/response type (see PolicyEvaluator
// itself). This resource's only job is resolving the authenticated actor and delegating.
public class PolicyEvaluationsResource implements PolicyEvaluationsApi {

  private final PolicyEvaluator policyEvaluator;
  private final AgentActorResolver agentActors;

  public PolicyEvaluationsResource(
      PolicyEvaluator policyEvaluator, AgentActorResolver agentActors) {
    this.policyEvaluator = Objects.requireNonNull(policyEvaluator, "policyEvaluator");
    this.agentActors = Objects.requireNonNull(agentActors, "agentActors");
  }

  @Override
  public Response evaluatePolicy(
      String xCorrelationID, PolicyEvaluationRequest policyEvaluationRequest) {
    return agentActors.withActor(
        actor -> {
          return Response.ok(policyEvaluator.evaluate(policyEvaluationRequest)).build();
        });
  }
}
