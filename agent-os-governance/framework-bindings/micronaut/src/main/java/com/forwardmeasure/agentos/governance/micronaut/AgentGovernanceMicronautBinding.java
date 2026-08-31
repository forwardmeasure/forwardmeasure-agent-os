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
package com.forwardmeasure.agentos.governance.micronaut;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.agentos.domain.WorkflowReleaseResolver;
import com.forwardmeasure.agentos.governance.application.AgentGovernanceService;
import com.forwardmeasure.agentos.governance.jpa.application.AgentGovernanceServiceImpl;
import com.forwardmeasure.agentos.governance.jpa.repository.AgentAuditEventRepository;
import com.forwardmeasure.agentos.governance.jpa.repository.AgentRepository;
import com.forwardmeasure.agentos.governance.jpa.service.AgentAuditEventService;
import com.forwardmeasure.agentos.governance.jpa.service.AgentService;
import com.forwardmeasure.agentos.governance.jpa.service.impl.AgentAuditEventServiceImpl;
import com.forwardmeasure.agentos.governance.jpa.service.impl.AgentServiceImpl;
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

// Micronaut composition for the governance vertical, mirroring
// OpenWorkflowDefinitionManagementMicronautBinding's real @Factory shape and
// forwardmeasure-jpa-micronaut's own repository(new X(), entityManager) producer pattern (see
// ForwardMeasureJpaFactory's real ActorRepository/ActorService producers). AgentService,
// AgentAuditEventService, and AgentGovernanceService are each wrapped in
// MicronautTransactionalServiceProxy - see that class's own doc comment for why Micronaut alone,
// unlike Quarkus/Spring, needs this.
@Factory
public class AgentGovernanceMicronautBinding {

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
    var tokens =
        new OAuthClientCredentialsTokenSupplier(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            tokenEndpoint,
            clientId,
            clientSecret,
            Duration.ofSeconds(10));
    return new OpenWorkflowWorkflowReleaseResolver(baseUrl, tokens);
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

  private static <R extends AbstractBaseRepository<?, ?>> R repository(
      R repository, EntityManager context) {
    repository.bindPersistenceContext(context);
    return repository;
  }
}
