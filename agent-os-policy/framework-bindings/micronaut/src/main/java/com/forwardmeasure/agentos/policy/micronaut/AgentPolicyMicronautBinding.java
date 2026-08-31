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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.agentos.domain.PolicyEvaluator;
import com.forwardmeasure.agentos.policy.drools.DroolsPolicyEvaluator;
import com.forwardmeasure.agentos.policy.opa.OpaPolicyEvaluator;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Secondary;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

// Micronaut composition for the policy vertical - see agent-os-execution-micronaut's own
// AgentExecutionMicronautBinding for the shared shape. No MicronautTransactionalServiceProxy here
// - both PolicyEvaluator implementations are stateless call-outs (HTTP to OPA, gRPC to
// decision-engine), never a transactional database service.
//
// @Requires(property=...) on each competing @Factory method, mirroring
// MicronautRuntimeFactory's own persistence-profile selection (openworkflow-actor-engine) - the
// closest existing precedent in this ecosystem for "one interface, config-selected
// implementation" in Micronaut.
@Factory
public class AgentPolicyMicronautBinding {

  @Singleton
  @Secondary
  @Requires(property = "agent-os.policy.evaluator", value = "opa", defaultValue = "opa")
  PolicyEvaluator opaPolicyEvaluator(
      @Value("${agent-os.policy.opa-base-url}") URI opaBaseUrl,
      @Value("${agent-os.policy.opa-token:}") String opaToken) {
    return new OpaPolicyEvaluator(
        HttpClient.newHttpClient(),
        new ObjectMapper(),
        opaBaseUrl,
        opaToken == null || opaToken.isBlank() ? null : opaToken,
        Duration.ofSeconds(10));
  }

  @Singleton
  @Secondary
  @Requires(property = "agent-os.policy.evaluator", value = "drools")
  PolicyEvaluator droolsPolicyEvaluator(
      @Value("${agent-os.policy.drools-target}") String droolsTarget) {
    return new DroolsPolicyEvaluator(droolsTarget, new ObjectMapper(), Duration.ofSeconds(10));
  }
}
