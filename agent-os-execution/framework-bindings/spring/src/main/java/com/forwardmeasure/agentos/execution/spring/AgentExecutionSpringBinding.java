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
package com.forwardmeasure.agentos.execution.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.agentos.domain.AgentActorResolver;
import com.forwardmeasure.agentos.domain.WorkflowExecutionDispatcher;
import com.forwardmeasure.agentos.domain.WorkflowReleaseResolver;
import com.forwardmeasure.agentos.execution.application.AgentExecutionService;
import com.forwardmeasure.agentos.execution.jaxrs.AgentExecutionsResource;
import com.forwardmeasure.agentos.execution.jpa.application.AgentExecutionServiceImpl;
import com.forwardmeasure.agentos.execution.jpa.repository.AgentExecutionRepository;
import com.forwardmeasure.agentos.execution.jpa.service.AgentExecutionRecordService;
import com.forwardmeasure.agentos.execution.jpa.service.impl.AgentExecutionRecordServiceImpl;
import com.forwardmeasure.agentos.governance.application.AgentGovernanceService;
import com.forwardmeasure.agentos.governance.jpa.application.AgentGovernanceServiceImpl;
import com.forwardmeasure.agentos.governance.jpa.repository.AgentAuditEventRepository;
import com.forwardmeasure.agentos.governance.jpa.repository.AgentRepository;
import com.forwardmeasure.agentos.governance.jpa.service.AgentAuditEventService;
import com.forwardmeasure.agentos.governance.jpa.service.AgentService;
import com.forwardmeasure.agentos.governance.jpa.service.impl.AgentAuditEventServiceImpl;
import com.forwardmeasure.agentos.governance.jpa.service.impl.AgentServiceImpl;
import com.forwardmeasure.agentos.openworkflow.client.OpenWorkflowExecutionDispatcher;
import com.forwardmeasure.agentos.openworkflow.client.OpenWorkflowWorkflowReleaseResolver;
import com.forwardmeasure.jpa.core.repository.AbstractBaseRepository;
import com.forwardmeasure.jpa.identity.service.ActorService;
import com.forwardmeasure.openworkflow.authorization.authzen.OAuthClientCredentialsTokenSupplier;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jersey.autoconfigure.ResourceConfigCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// Spring composition for the execution vertical - see agent-os-execution-quarkus's own
// AgentExecutionQuarkusBinding doc comment for why this reproduces governance's own persistence
// wiring in-process rather than calling a separately-deployed governance service. The injectable
// EntityManager bean itself is provided by ForwardMeasureJpaAutoConfiguration.
// forwardMeasureEntityManager (forwardmeasure-jpa-spring, fixed 2026-08-28) - not redeclared here;
// spring-boot-starter-data-jpa alone never provides one (Quarkus/Micronaut's own containers do,
// which is why this gap was Spring-only).
@Configuration(proxyBeanMethods = false)
public class AgentExecutionSpringBinding {

  @Bean
  AgentRepository agentRepository(EntityManager entityManager) {
    return repository(new AgentRepository(), entityManager);
  }

  @Bean
  AgentService agentService(AgentRepository repository) {
    return new AgentServiceImpl(repository);
  }

  @Bean
  AgentAuditEventRepository agentAuditEventRepository(EntityManager entityManager) {
    return repository(new AgentAuditEventRepository(), entityManager);
  }

  @Bean
  AgentAuditEventService agentAuditEventService(AgentAuditEventRepository repository) {
    return new AgentAuditEventServiceImpl(repository);
  }

  @Bean
  WorkflowReleaseResolver workflowReleaseResolver(
      @Value("${agent-os.openworkflow.definition-management-base-url}") URI baseUrl,
      @Value("${agent-os.openworkflow.token-endpoint}") URI tokenEndpoint,
      @Value("${agent-os.openworkflow.client-id}") String clientId,
      @Value("${agent-os.openworkflow.client-secret}") String clientSecret) {
    return new OpenWorkflowWorkflowReleaseResolver(
        baseUrl, tokenSupplier(tokenEndpoint, clientId, clientSecret));
  }

  @Bean
  AgentGovernanceService agentGovernanceService(
      AgentService agents,
      AgentAuditEventService auditEvents,
      ActorService actors,
      WorkflowReleaseResolver workflowReleases) {
    return new AgentGovernanceServiceImpl(agents, auditEvents, actors, workflowReleases);
  }

  @Bean
  AgentExecutionRepository agentExecutionRepository(EntityManager entityManager) {
    return repository(new AgentExecutionRepository(), entityManager);
  }

  @Bean
  AgentExecutionRecordService agentExecutionRecordService(AgentExecutionRepository repository) {
    return new AgentExecutionRecordServiceImpl(repository);
  }

  @Bean
  WorkflowExecutionDispatcher workflowExecutionDispatcher(
      @Value("${agent-os.openworkflow.execution-management-base-url}") URI baseUrl,
      @Value("${agent-os.openworkflow.token-endpoint}") URI tokenEndpoint,
      @Value("${agent-os.openworkflow.client-id}") String clientId,
      @Value("${agent-os.openworkflow.client-secret}") String clientSecret) {
    return new OpenWorkflowExecutionDispatcher(
        baseUrl, tokenSupplier(tokenEndpoint, clientId, clientSecret));
  }

  @Bean
  AgentExecutionService agentExecutionService(
      AgentExecutionRecordService executions,
      AgentGovernanceService governance,
      ActorService actors,
      WorkflowExecutionDispatcher dispatcher) {
    return new AgentExecutionServiceImpl(executions, governance, actors, dispatcher);
  }

  @Bean
  AgentExecutionsResource agentExecutionsResource(
      AgentExecutionService execution, AgentActorResolver agentActors) {
    return new AgentExecutionsResource(execution, agentActors);
  }

  @Bean
  ResourceConfigCustomizer agentExecutionResourceConfigCustomizer(
      AgentExecutionsResource executions) {
    return resourceConfig -> resourceConfig.register(executions);
  }

  @Bean
  SecurityFilterChain agentExecutionSecurity(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
        .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
        .build();
  }

  private static OAuthClientCredentialsTokenSupplier tokenSupplier(
      URI tokenEndpoint, String clientId, String clientSecret) {
    return new OAuthClientCredentialsTokenSupplier(
        HttpClient.newHttpClient(),
        new ObjectMapper(),
        tokenEndpoint,
        clientId,
        clientSecret,
        Duration.ofSeconds(10));
  }

  private static <R extends AbstractBaseRepository<?, ?>> R repository(
      R repository, EntityManager context) {
    repository.bindPersistenceContext(context);
    return repository;
  }
}
