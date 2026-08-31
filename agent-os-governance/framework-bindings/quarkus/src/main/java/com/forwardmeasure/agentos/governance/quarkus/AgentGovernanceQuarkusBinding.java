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
package com.forwardmeasure.agentos.governance.quarkus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.agentos.domain.AgentActorResolver;
import com.forwardmeasure.agentos.domain.WorkflowReleaseResolver;
import com.forwardmeasure.agentos.governance.application.AgentGovernanceService;
import com.forwardmeasure.agentos.governance.jaxrs.AgentGovernanceResource;
import com.forwardmeasure.agentos.governance.jaxrs.AgentReleasesResource;
import com.forwardmeasure.agentos.governance.jaxrs.AgentsResource;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

// Quarkus CDI composition for the governance vertical - persistence, the OpenWorkflow release
// resolver, the application service, and JAX-RS resource hosting, all in one place. Mirrors
// forwardmeasure-openworkflow's own real Quarkus binding shape (see
// OpenWorkflowDefinitionManagementQuarkusBinding) and forwardmeasure-jpa-spring/-micronaut's
// repository(new X(), entityManager) producer pattern for binding EntityManager into an
// AbstractBaseRepository subclass explicitly, rather than relying on Quarkus's own
// @PersistenceContext field injection working across this jar's bean archive boundary.
@ApplicationScoped
public class AgentGovernanceQuarkusBinding {

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

  @Produces
  @ApplicationScoped
  WorkflowReleaseResolver workflowReleaseResolver(
      @ConfigProperty(name = "agent-os.openworkflow.definition-management-base-url") URI baseUrl,
      @ConfigProperty(name = "agent-os.openworkflow.token-endpoint") URI tokenEndpoint,
      @ConfigProperty(name = "agent-os.openworkflow.client-id") String clientId,
      @ConfigProperty(name = "agent-os.openworkflow.client-secret") String clientSecret) {
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
  AgentsResource agentsResource(AgentGovernanceService governance, AgentActorResolver agentActors) {
    return new AgentsResource(governance, agentActors);
  }

  @Produces
  @ApplicationScoped
  AgentGovernanceResource agentGovernanceResource(
      AgentGovernanceService governance, AgentActorResolver agentActors) {
    return new AgentGovernanceResource(governance, agentActors);
  }

  @Produces
  @ApplicationScoped
  AgentReleasesResource agentReleasesResource(
      AgentGovernanceService governance, AgentActorResolver agentActors) {
    return new AgentReleasesResource(governance, agentActors);
  }

  private static <R extends AbstractBaseRepository<?, ?>> R repository(
      R repository, EntityManager context) {
    repository.bindPersistenceContext(context);
    return repository;
  }
}
