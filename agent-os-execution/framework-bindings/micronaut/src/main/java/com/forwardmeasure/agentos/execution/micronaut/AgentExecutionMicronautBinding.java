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
package com.forwardmeasure.agentos.execution.micronaut;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.agentos.domain.WorkflowExecutionDispatcher;
import com.forwardmeasure.agentos.domain.WorkflowReleaseResolver;
import com.forwardmeasure.agentos.execution.application.AgentExecutionService;
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
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Secondary;
import io.micronaut.context.annotation.Value;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.hibernate.Session;

// Micronaut composition for the execution vertical - see agent-os-execution-quarkus's own
// AgentExecutionQuarkusBinding doc comment for why this reproduces governance's own persistence
// wiring in-process, and agent-os-governance-micronaut's own binding for why every service here is
// wrapped in MicronautTransactionalServiceProxy.
@Factory
public class AgentExecutionMicronautBinding {

  @Singleton
  @Secondary
  @Requires(beans = EntityManager.class)
  AgentRepository agentRepository(EntityManager entityManager) {
    return repository(new AgentRepository(), entityManager);
  }

  @Singleton
  @Secondary
  AgentService agentService(
      AgentRepository repository, TransactionOperations<Session> transactions) {
    return MicronautTransactionalServiceProxy.create(
        AgentService.class, new AgentServiceImpl(repository), transactions);
  }

  @Singleton
  @Secondary
  @Requires(beans = EntityManager.class)
  AgentAuditEventRepository agentAuditEventRepository(EntityManager entityManager) {
    return repository(new AgentAuditEventRepository(), entityManager);
  }

  @Singleton
  @Secondary
  AgentAuditEventService agentAuditEventService(
      AgentAuditEventRepository repository, TransactionOperations<Session> transactions) {
    return MicronautTransactionalServiceProxy.create(
        AgentAuditEventService.class, new AgentAuditEventServiceImpl(repository), transactions);
  }

  @Singleton
  @Secondary
  WorkflowReleaseResolver workflowReleaseResolver(
      @Value("${agent-os.openworkflow.definition-management-base-url}") URI baseUrl,
      @Value("${agent-os.openworkflow.token-endpoint}") URI tokenEndpoint,
      @Value("${agent-os.openworkflow.client-id}") String clientId,
      @Value("${agent-os.openworkflow.client-secret}") String clientSecret) {
    return new OpenWorkflowWorkflowReleaseResolver(
        baseUrl, tokenSupplier(tokenEndpoint, clientId, clientSecret));
  }

  @Singleton
  @Secondary
  AgentGovernanceService agentGovernanceService(
      AgentService agents,
      AgentAuditEventService auditEvents,
      ActorService actors,
      WorkflowReleaseResolver workflowReleases,
      TransactionOperations<Session> transactions) {
    return MicronautTransactionalServiceProxy.create(
        AgentGovernanceService.class,
        new AgentGovernanceServiceImpl(agents, auditEvents, actors, workflowReleases),
        transactions);
  }

  @Singleton
  @Secondary
  @Requires(beans = EntityManager.class)
  AgentExecutionRepository agentExecutionRepository(EntityManager entityManager) {
    return repository(new AgentExecutionRepository(), entityManager);
  }

  @Singleton
  @Secondary
  AgentExecutionRecordService agentExecutionRecordService(
      AgentExecutionRepository repository, TransactionOperations<Session> transactions) {
    return MicronautTransactionalServiceProxy.create(
        AgentExecutionRecordService.class,
        new AgentExecutionRecordServiceImpl(repository),
        transactions);
  }

  @Singleton
  @Secondary
  WorkflowExecutionDispatcher workflowExecutionDispatcher(
      @Value("${agent-os.openworkflow.execution-management-base-url}") URI baseUrl,
      @Value("${agent-os.openworkflow.token-endpoint}") URI tokenEndpoint,
      @Value("${agent-os.openworkflow.client-id}") String clientId,
      @Value("${agent-os.openworkflow.client-secret}") String clientSecret) {
    return new OpenWorkflowExecutionDispatcher(
        baseUrl, tokenSupplier(tokenEndpoint, clientId, clientSecret));
  }

  @Singleton
  @Secondary
  AgentExecutionService agentExecutionService(
      AgentExecutionRecordService executions,
      AgentGovernanceService governance,
      ActorService actors,
      WorkflowExecutionDispatcher dispatcher,
      TransactionOperations<Session> transactions) {
    return MicronautTransactionalServiceProxy.create(
        AgentExecutionService.class,
        new AgentExecutionServiceImpl(executions, governance, actors, dispatcher),
        transactions);
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
