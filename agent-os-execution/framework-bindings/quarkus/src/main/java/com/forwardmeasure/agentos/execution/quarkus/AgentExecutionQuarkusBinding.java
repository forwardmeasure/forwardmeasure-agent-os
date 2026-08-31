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
package com.forwardmeasure.agentos.execution.quarkus;

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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

// Quarkus CDI composition for the execution vertical. Reproduces agent-os-governance-quarkus's own
// persistence-wiring producers for AgentGovernanceService (see this module's own pom comment for
// why) rather than calling a separately-deployed governance service over HTTP - execution and
// governance share one database, and §2.9 is explicit that release availability is a direct,
// in-process call, not a network hop.
@ApplicationScoped
public class AgentExecutionQuarkusBinding {

  @Produces
  @ApplicationScoped
  AgentRepository agentRepository(EntityManager entityManager) {
    return repository(new AgentRepository(), entityManager);
  }

  @Produces
  @ApplicationScoped
  AgentService agentService(AgentRepository repository) {
    return new AgentServiceImpl(repository);
  }

  @Produces
  @ApplicationScoped
  AgentAuditEventRepository agentAuditEventRepository(EntityManager entityManager) {
    return repository(new AgentAuditEventRepository(), entityManager);
  }

  @Produces
  @ApplicationScoped
  AgentAuditEventService agentAuditEventService(AgentAuditEventRepository repository) {
    return new AgentAuditEventServiceImpl(repository);
  }

  // Definition-management and execution-management are real, separately deployed OpenWorkflow
  // services (see openworkflow-quarkus-service vs openworkflow-deployments/execution-management) -
  // distinct base URLs, not one shared endpoint, since execution talks to both (release lookup
  // indirectly via AgentGovernanceService, and its own dispatch directly).
  @Produces
  @ApplicationScoped
  WorkflowReleaseResolver workflowReleaseResolver(
      @ConfigProperty(name = "agent-os.openworkflow.definition-management-base-url") URI baseUrl,
      @ConfigProperty(name = "agent-os.openworkflow.token-endpoint") URI tokenEndpoint,
      @ConfigProperty(name = "agent-os.openworkflow.client-id") String clientId,
      @ConfigProperty(name = "agent-os.openworkflow.client-secret") String clientSecret) {
    return new OpenWorkflowWorkflowReleaseResolver(
        baseUrl, tokenSupplier(tokenEndpoint, clientId, clientSecret));
  }

  @Produces
  @ApplicationScoped
  AgentGovernanceService agentGovernanceService(
      AgentService agents,
      AgentAuditEventService auditEvents,
      ActorService actors,
      WorkflowReleaseResolver workflowReleases) {
    return new AgentGovernanceServiceImpl(agents, auditEvents, actors, workflowReleases);
  }

  @Produces
  @ApplicationScoped
  AgentExecutionRepository agentExecutionRepository(EntityManager entityManager) {
    return repository(new AgentExecutionRepository(), entityManager);
  }

  @Produces
  @ApplicationScoped
  AgentExecutionRecordService agentExecutionRecordService(AgentExecutionRepository repository) {
    return new AgentExecutionRecordServiceImpl(repository);
  }

  @Produces
  @ApplicationScoped
  WorkflowExecutionDispatcher workflowExecutionDispatcher(
      @ConfigProperty(name = "agent-os.openworkflow.execution-management-base-url") URI baseUrl,
      @ConfigProperty(name = "agent-os.openworkflow.token-endpoint") URI tokenEndpoint,
      @ConfigProperty(name = "agent-os.openworkflow.client-id") String clientId,
      @ConfigProperty(name = "agent-os.openworkflow.client-secret") String clientSecret) {
    return new OpenWorkflowExecutionDispatcher(
        baseUrl, tokenSupplier(tokenEndpoint, clientId, clientSecret));
  }

  @Produces
  @ApplicationScoped
  AgentExecutionService agentExecutionService(
      AgentExecutionRecordService executions,
      AgentGovernanceService governance,
      ActorService actors,
      WorkflowExecutionDispatcher dispatcher) {
    return new AgentExecutionServiceImpl(executions, governance, actors, dispatcher);
  }

  @Produces
  @ApplicationScoped
  AgentExecutionsResource agentExecutionsResource(
      AgentExecutionService execution, AgentActorResolver agentActors) {
    return new AgentExecutionsResource(execution, agentActors);
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
