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
package com.forwardmeasure.agentos.policy.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.agentos.domain.AgentActorResolver;
import com.forwardmeasure.agentos.domain.PolicyEvaluator;
import com.forwardmeasure.agentos.policy.drools.DroolsPolicyEvaluator;
import com.forwardmeasure.agentos.policy.jaxrs.PolicyEvaluationsResource;
import com.forwardmeasure.agentos.policy.opa.OpaPolicyEvaluator;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jersey.autoconfigure.ResourceConfigCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// Spring composition for the policy vertical - see agent-os-execution-spring's own
// AgentExecutionSpringBinding for the shared shape. No persistence-wiring beans of policy's own -
// both PolicyEvaluator implementations are stateless call-outs (HTTP to OPA, gRPC to
// decision-engine), never a database.
// agent-os-spring-actor-binding's own ForwardMeasureJpaAutoConfiguration
// (forwardmeasure-jpa-spring)
// still needs a plain injectable EntityManager bean for its own ActorRepository/ActorService
// producers - that bean is now provided by ForwardMeasureJpaAutoConfiguration.
// forwardMeasureEntityManager itself (fixed 2026-08-28), not redeclared per-service here.
@Configuration(proxyBeanMethods = false)
public class AgentPolicySpringBinding {

  // Nested @Configuration classes gated by @ConditionalOnProperty, mirroring
  // OpenWorkflowSpringConfiguration's own PostgresqlDefinitions/CassandraDefinitions pattern
  // (openworkflow-actor-engine's persistence-profile selection) - the closest existing precedent
  // in this ecosystem for "one interface, config-selected implementation" in Spring.
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(
      name = "agent-os.policy.evaluator",
      havingValue = "opa",
      matchIfMissing = true)
  static class OpaPolicyEvaluatorConfiguration {

    @Bean
    PolicyEvaluator policyEvaluator(
        @Value("${agent-os.policy.opa-base-url}") URI opaBaseUrl,
        @Value("${agent-os.policy.opa-token:}") String opaToken) {
      return new OpaPolicyEvaluator(
          HttpClient.newHttpClient(),
          new ObjectMapper(),
          opaBaseUrl,
          opaToken == null || opaToken.isBlank() ? null : opaToken,
          Duration.ofSeconds(10));
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "agent-os.policy.evaluator", havingValue = "drools")
  static class DroolsPolicyEvaluatorConfiguration {

    @Bean
    PolicyEvaluator policyEvaluator(
        @Value("${agent-os.policy.drools-target}") String droolsTarget) {
      return new DroolsPolicyEvaluator(droolsTarget, new ObjectMapper(), Duration.ofSeconds(10));
    }
  }

  @Bean
  PolicyEvaluationsResource policyEvaluationsResource(
      PolicyEvaluator policyEvaluator, AgentActorResolver agentActors) {
    return new PolicyEvaluationsResource(policyEvaluator, agentActors);
  }

  @Bean
  ResourceConfigCustomizer policyResourceConfigCustomizer(PolicyEvaluationsResource resource) {
    return resourceConfig -> resourceConfig.register(resource);
  }

  @Bean
  SecurityFilterChain agentPolicySecurity(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
        .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
        .build();
  }
}
