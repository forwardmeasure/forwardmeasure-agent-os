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
package com.forwardmeasure.agentos.policy.quarkus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.agentos.domain.AgentActorResolver;
import com.forwardmeasure.agentos.domain.PolicyEvaluator;
import com.forwardmeasure.agentos.domain.PolicyEvaluatorProfile;
import com.forwardmeasure.agentos.policy.drools.DroolsPolicyEvaluator;
import com.forwardmeasure.agentos.policy.jaxrs.PolicyEvaluationsResource;
import com.forwardmeasure.agentos.policy.opa.OpaPolicyEvaluator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

// Quarkus CDI composition for the policy vertical. No persistence-wiring producers here, unlike
// the execution/governance bindings - both PolicyEvaluator implementations are stateless call-outs
// (HTTP to OPA, gRPC to decision-engine), never a database.
@ApplicationScoped
public class AgentPolicyQuarkusBinding {

  // Imperative branch on the raw config value, matching QuarkusRuntimeWiring's own style
  // (openworkflow-actor-engine's persistence-profile selection) rather than Quarkus's
  // @IfBuildProperty, which nothing in this codebase currently uses.
  @Produces
  @ApplicationScoped
  PolicyEvaluator policyEvaluator(
      @ConfigProperty(name = "agent-os.policy.evaluator", defaultValue = "opa") String evaluator,
      @ConfigProperty(name = "agent-os.policy.opa-base-url") Optional<URI> opaBaseUrl,
      @ConfigProperty(name = "agent-os.policy.opa-token") Optional<String> opaToken,
      @ConfigProperty(name = "agent-os.policy.drools-target") Optional<String> droolsTarget) {
    PolicyEvaluatorProfile profile = PolicyEvaluatorProfile.parse(evaluator);
    if (profile == PolicyEvaluatorProfile.DROOLS) {
      return new DroolsPolicyEvaluator(
          droolsTarget.orElseThrow(
              () ->
                  new IllegalStateException(
                      "agent-os.policy.drools-target is required when"
                          + " agent-os.policy.evaluator=drools")),
          new ObjectMapper(),
          Duration.ofSeconds(10));
    }
    return new OpaPolicyEvaluator(
        HttpClient.newHttpClient(),
        new ObjectMapper(),
        opaBaseUrl.orElseThrow(
            () ->
                new IllegalStateException(
                    "agent-os.policy.opa-base-url is required when agent-os.policy.evaluator=opa")),
        opaToken.orElse(null),
        Duration.ofSeconds(10));
  }

  @Produces
  @ApplicationScoped
  PolicyEvaluationsResource policyEvaluationsResource(
      PolicyEvaluator policyEvaluator, AgentActorResolver agentActors) {
    return new PolicyEvaluationsResource(policyEvaluator, agentActors);
  }
}
