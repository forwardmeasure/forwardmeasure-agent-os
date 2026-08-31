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
package com.forwardmeasure.agentos.governance.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forwardmeasure.agentos.domain.AgentStatus;
import com.forwardmeasure.agentos.governance.api.model.AgentDefinitionContent;
import com.forwardmeasure.agentos.governance.api.model.AgentProtocols;
import com.forwardmeasure.agentos.governance.api.model.AgentSkill;
import com.forwardmeasure.agentos.governance.jpa.entity.Agent;
import com.forwardmeasure.agentos.governance.jpa.repository.AgentRepository;
import com.forwardmeasure.agentos.governance.jpa.service.AgentService;
import com.forwardmeasure.agentos.governance.jpa.service.impl.AgentServiceImpl;
import com.forwardmeasure.jpa.identity.entity.Actor;
import com.forwardmeasure.jpa.identity.entity.IdentityType;
import com.forwardmeasure.jpa.identity.repository.ActorRepository;
import com.forwardmeasure.jpa.liquibase.TenantSchemaMigrator;
import com.forwardmeasure.jpa.tenancy.TenantId;
import com.forwardmeasure.jpa.tenancy.TenantSchema;
import com.forwardmeasure.testcontainers.junit.postgresql.WithPostgreSqlContainer;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

@WithPostgreSqlContainer(databaseName = "agent_os_governance_contract")
class AgentPostgreSqlIntegrationTest {

  @Test
  void persistsAndQueriesTheFullGovernanceLifecycle(PostgreSqlTestContainer database) {
    TenantSchema tenant = prepare(database);
    try (EntityManagerFactory entityManagers = entityManagers(database, tenant)) {
      Long agentId =
          inTransaction(
              entityManagers,
              context -> {
                Actor owner = actor(context.actors(), "owner");
                Agent draft =
                    Agent.builder()
                        .namespace("acme")
                        .name("invoice-reconciler")
                        .agentVersion("1.0.0")
                        .status(AgentStatus.DRAFT)
                        .content(content())
                        .owner(owner)
                        .build();
                context.agents().persist(draft);
                context.agents().flush();
                assertNotNull(draft.getId());
                assertNotNull(draft.getUuid());
                return draft.getId();
              });

      // Round-trip: a fresh EntityManager, fresh repository, real Postgres row - not the
      // still-attached in-memory instance from the write above.
      inTransaction(
          entityManagers,
          context -> {
            Agent reloaded = context.agents().findById(agentId);
            assertNotNull(reloaded);
            assertEquals("acme", reloaded.getNamespace());
            assertEquals(AgentStatus.DRAFT, reloaded.getStatus());
            // The JSONB round-trip is the real thing under test here: the generated
            // AgentDefinitionContent survives a write+read through Postgres unchanged.
            assertEquals(content().getDisplayName(), reloaded.getContent().getDisplayName());
            assertEquals(1, reloaded.getContent().getSkills().size());
            assertEquals(content().getPublicUri(), reloaded.getContent().getPublicUri());
            return null;
          });

      boolean draftIsAvailable =
          inTransaction(
              entityManagers,
              context ->
                  context
                      .agents()
                      .findAvailableRelease("acme", "invoice-reconciler", "1.0.0")
                      .isPresent());
      assertFalse(draftIsAvailable, "a draft agent must not be an AVAILABLE release");

      inTransaction(
          entityManagers,
          context -> {
            Actor reviewer = actor(context.actors(), "reviewer");
            Agent agent = context.agents().findById(agentId);
            agent.setStatus(AgentStatus.PUBLISHED);
            agent.setReviewer(reviewer);
            agent.setWorkflowId(UUID.randomUUID());
            agent.setWorkflowRevisionId(UUID.randomUUID());
            agent.setWorkflowRevisionDigest("a".repeat(64));
            agent.setAgentDefinitionSha256("b".repeat(64));
            agent.setPublishedAt(OffsetDateTime.now());
            return null;
          });

      Optional<Agent> available =
          inTransaction(
              entityManagers,
              context ->
                  context.agents().findAvailableRelease("acme", "invoice-reconciler", "1.0.0"));
      assertTrue(
          available.isPresent(), "a published agent must be findable as an AVAILABLE release");
      assertEquals(AgentStatus.PUBLISHED, available.get().getStatus());
      assertNotNull(available.get().getWorkflowId());

      inTransaction(
          entityManagers,
          context -> {
            context.agents().findById(agentId).setStatus(AgentStatus.DEPRECATED);
            return null;
          });

      assertFalse(
          inTransaction(
                  entityManagers,
                  context ->
                      context.agents().findAvailableRelease("acme", "invoice-reconciler", "1.0.0"))
              .isPresent(),
          "a deprecated agent must no longer be an AVAILABLE release");
    }
  }

  private static AgentDefinitionContent content() {
    return new AgentDefinitionContent(
        "Invoice Reconciliation Agent",
        List.of(
            new AgentSkill(
                "reconcile-invoice", "Reconcile Invoice", "Matches an invoice to its PO")),
        Map.of("type", "object"),
        Map.of("type", "object"),
        new AgentProtocols(true, false),
        URI.create("https://agents.example.com/invoice-reconciler"));
  }

  private TenantSchema prepare(PostgreSqlTestContainer database) {
    TenantSchema tenant = TenantSchema.forTenant(new TenantId(UUID.randomUUID()));
    database.createSchema(tenant.value());
    TenantSchemaMigrator migrator =
        new TenantSchemaMigrator(
            database.dataSource(),
            "db/changelog/agent-os-governance-jpa-test.xml",
            getClass().getClassLoader());
    assertTrue(migrator.validate(tenant).valid());
    migrator.migrate(tenant);
    assertTrue(migrator.status(tenant).current());
    return tenant;
  }

  private EntityManagerFactory entityManagers(
      PostgreSqlTestContainer database, TenantSchema tenant) {
    return Persistence.createEntityManagerFactory(
        "agent-os-governance-jpa-test",
        Map.of(
            "jakarta.persistence.jdbc.url", database.hostJdbcUrl(),
            "jakarta.persistence.jdbc.user", database.username(),
            "jakarta.persistence.jdbc.password", database.password(),
            "jakarta.persistence.jdbc.driver", "org.postgresql.Driver",
            "hibernate.default_schema", tenant.value()));
  }

  private <T> T inTransaction(EntityManagerFactory entityManagers, Function<Context, T> work) {
    EntityManager entityManager = entityManagers.createEntityManager();
    var transaction = entityManager.getTransaction();
    try {
      transaction.begin();
      ActorRepository actors = new ActorRepository();
      actors.bindPersistenceContext(entityManager);
      AgentRepository agentRepository = new AgentRepository();
      agentRepository.bindPersistenceContext(entityManager);
      AgentService agents = new AgentServiceImpl(agentRepository);
      T result = work.apply(new Context(actors, agents));
      transaction.commit();
      return result;
    } catch (RuntimeException | Error failure) {
      if (transaction.isActive()) {
        transaction.rollback();
      }
      throw failure;
    } finally {
      entityManager.close();
    }
  }

  private Actor actor(ActorRepository actors, String label) {
    Actor actor =
        Actor.builder()
            .subjectIdentifier(label + "-" + UUID.randomUUID())
            .identityProvider("test")
            .type(IdentityType.HUMAN)
            .build();
    actors.persist(actor);
    return actor;
  }

  private record Context(ActorRepository actors, AgentService agents) {}
}
