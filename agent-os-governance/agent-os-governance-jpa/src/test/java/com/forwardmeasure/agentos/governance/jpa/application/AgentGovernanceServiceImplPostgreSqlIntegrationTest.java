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
package com.forwardmeasure.agentos.governance.jpa.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forwardmeasure.agentos.domain.ActorReference;
import com.forwardmeasure.agentos.domain.ActorType;
import com.forwardmeasure.agentos.domain.AgentActor;
import com.forwardmeasure.agentos.domain.AgentCoordinates;
import com.forwardmeasure.agentos.domain.AgentDefinition;
import com.forwardmeasure.agentos.domain.AgentStatus;
import com.forwardmeasure.agentos.domain.WorkflowReleaseBinding;
import com.forwardmeasure.agentos.domain.WorkflowReleaseResolver;
import com.forwardmeasure.agentos.governance.api.model.AgentAuditOperation;
import com.forwardmeasure.agentos.governance.api.model.AgentDefinitionContent;
import com.forwardmeasure.agentos.governance.api.model.AgentProtocols;
import com.forwardmeasure.agentos.governance.api.model.AgentSkill;
import com.forwardmeasure.agentos.governance.application.AgentGovernanceService;
import com.forwardmeasure.agentos.governance.application.AgentNotDraftException;
import com.forwardmeasure.agentos.governance.application.DuplicateAgentCoordinatesException;
import com.forwardmeasure.agentos.governance.application.Page;
import com.forwardmeasure.agentos.governance.application.SelfApprovalNotAllowedException;
import com.forwardmeasure.agentos.governance.application.StaleRevisionException;
import com.forwardmeasure.agentos.governance.jpa.repository.AgentAuditEventRepository;
import com.forwardmeasure.agentos.governance.jpa.repository.AgentRepository;
import com.forwardmeasure.agentos.governance.jpa.service.impl.AgentAuditEventServiceImpl;
import com.forwardmeasure.agentos.governance.jpa.service.impl.AgentServiceImpl;
import com.forwardmeasure.jpa.identity.entity.Actor;
import com.forwardmeasure.jpa.identity.entity.IdentityType;
import com.forwardmeasure.jpa.identity.repository.ActorRepository;
import com.forwardmeasure.jpa.identity.service.impl.ActorServiceImpl;
import com.forwardmeasure.jpa.liquibase.TenantSchemaMigrator;
import com.forwardmeasure.jpa.tenancy.TenantId;
import com.forwardmeasure.jpa.tenancy.TenantSchema;
import com.forwardmeasure.testcontainers.junit.postgresql.WithPostgreSqlContainer;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

// Exercises AgentGovernanceServiceImpl - not just AgentService - through a real Postgres schema,
// matching AgentPostgreSqlIntegrationTest's own manual EntityManager/transaction idiom (no
// TenantScope.open here: this test calls the application service directly, the same way its real
// caller, agent-os-governance-jaxrs's resources, would from inside AgentActorResolver.withActor's
// already-open scope).
@WithPostgreSqlContainer(databaseName = "agent_os_governance_application_contract")
class AgentGovernanceServiceImplPostgreSqlIntegrationTest {

  private static final AgentCoordinates COORDINATES =
      new AgentCoordinates("acme", "invoice-reconciler", "1.0.0");

  @Test
  void runsTheFullLifecycleWithAnAccurateAuditTrail(PostgreSqlTestContainer database) {
    TenantSchema tenant = prepare(database);
    try (EntityManagerFactory entityManagers = entityManagers(database, tenant)) {
      AgentActor[] owner = new AgentActor[1];
      AgentActor[] reviewer = new AgentActor[1];

      UUID agentId =
          inTransaction(
              entityManagers,
              context -> {
                owner[0] = actor(context, "owner");
                reviewer[0] = actor(context, "reviewer");
                AgentDefinition draft =
                    context.governance().createDraft(owner[0], COORDINATES, content());
                assertEquals(0, draft.revision());
                assertEquals(AgentStatus.DRAFT, draft.status());
                return draft.id();
              });

      assertThrows(
          DuplicateAgentCoordinatesException.class,
          () ->
              inTransaction(
                  entityManagers,
                  context -> context.governance().createDraft(owner[0], COORDINATES, content())));

      int afterUpdate =
          inTransaction(
                  entityManagers,
                  context ->
                      context.governance().updateDraft(owner[0], agentId, 0, updatedContent()))
              .revision();
      assertEquals(1, afterUpdate);

      assertThrows(
          StaleRevisionException.class,
          () ->
              inTransaction(
                  entityManagers,
                  context -> context.governance().updateDraft(owner[0], agentId, 0, content())));

      int afterSubmit =
          inTransaction(
                  entityManagers,
                  context ->
                      context
                          .governance()
                          .submitForReview(owner[0], agentId, 1, "ready for review"))
              .revision();
      assertEquals(2, afterSubmit);

      assertThrows(
          SelfApprovalNotAllowedException.class,
          () ->
              inTransaction(
                  entityManagers,
                  context -> context.governance().approve(owner[0], agentId, 2, null)));

      int afterApprove =
          inTransaction(
                  entityManagers,
                  context -> context.governance().approve(reviewer[0], agentId, 2, "looks good"))
              .revision();
      assertEquals(3, afterApprove);

      WorkflowReleaseBinding binding =
          new WorkflowReleaseBinding(UUID.randomUUID(), UUID.randomUUID(), "c".repeat(64));
      AgentDefinition published =
          inTransaction(
              entityManagers,
              context ->
                  context
                      .governance()
                      .publish(
                          owner[0],
                          agentId,
                          3,
                          binding.workflowId(),
                          binding.revisionId(),
                          "correlation-1"));
      assertEquals(4, published.revision());
      assertEquals(AgentStatus.PUBLISHED, published.status());
      assertEquals(binding, published.workflowBinding());
      assertTrue(published.agentDefinitionSha256().matches("^[0-9a-f]{64}$"));

      AgentDefinition release =
          inTransaction(
              entityManagers, context -> context.governance().getRelease(owner[0], COORDINATES));
      assertEquals(agentId, release.id());

      AgentDefinition deprecated =
          inTransaction(
              entityManagers,
              context -> context.governance().deprecate(owner[0], agentId, 4, "superseded"));
      assertEquals(5, deprecated.revision());
      assertEquals(AgentStatus.DEPRECATED, deprecated.status());

      assertThrows(
          AgentNotDraftException.class,
          () ->
              inTransaction(
                  entityManagers,
                  context -> {
                    context.governance().deleteDraft(owner[0], agentId, 5);
                    return null;
                  }));

      AgentDefinition archived =
          inTransaction(
              entityManagers, context -> context.governance().archive(owner[0], agentId, 5, null));
      assertEquals(6, archived.revision());
      assertEquals(AgentStatus.ARCHIVED, archived.status());

      Page<com.forwardmeasure.agentos.governance.api.model.AgentAuditEvent> auditEvents =
          inTransaction(
              entityManagers,
              context -> context.governance().listAuditEvents(owner[0], agentId, 0, 32));
      assertEquals(7, auditEvents.totalCount());
      List<AgentAuditOperation> operations =
          auditEvents.items().stream()
              .map(com.forwardmeasure.agentos.governance.api.model.AgentAuditEvent::getOperation)
              .toList();
      assertEquals(
          List.of(
              AgentAuditOperation.CREATED,
              AgentAuditOperation.UPDATED,
              AgentAuditOperation.REVIEW_SUBMITTED,
              AgentAuditOperation.APPROVED,
              AgentAuditOperation.PUBLISHED,
              AgentAuditOperation.DEPRECATED,
              AgentAuditOperation.ARCHIVED),
          operations,
          "audit events must be ordered oldest first and cover every real transition");
    }
  }

  private AgentActor actor(Context context, String label) {
    Actor entity =
        Actor.builder()
            .subjectIdentifier(label + "-" + UUID.randomUUID())
            .identityProvider("test")
            .type(IdentityType.HUMAN)
            .build();
    context.actorRepository().persist(entity);
    context.actorRepository().flush();
    return new AgentActor(
        new ActorReference(entity.getUuid(), entity.getSubjectIdentifier(), ActorType.HUMAN, null),
        UUID.randomUUID());
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

  // Genuinely different from content() - Hibernate's dirty-checking on the JSONB content column
  // compares AgentDefinitionContent.equals() (it's a generated record-like model with real
  // equals/hashCode), so re-submitting field-for-field-identical content correctly produces no
  // UPDATE and no revision bump; that's desired production behaviour, not something to work
  // around by asserting a version increment that shouldn't happen.
  private static AgentDefinitionContent updatedContent() {
    return new AgentDefinitionContent(
        "Invoice Reconciliation Agent (v2)",
        List.of(
            new AgentSkill(
                "reconcile-invoice", "Reconcile Invoice", "Matches an invoice to its PO")),
        Map.of("type", "object"),
        Map.of("type", "object"),
        new AgentProtocols(true, false),
        URI.create("https://agents.example.com/invoice-reconciler"));
  }

  private static final class StubWorkflowReleaseResolver implements WorkflowReleaseResolver {
    @Override
    public WorkflowReleaseBinding resolvePublishedRevision(UUID workflowId, UUID revisionId) {
      return new WorkflowReleaseBinding(workflowId, revisionId, "c".repeat(64));
    }
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
      ActorRepository actorRepository = new ActorRepository();
      actorRepository.bindPersistenceContext(entityManager);
      AgentRepository agentRepository = new AgentRepository();
      agentRepository.bindPersistenceContext(entityManager);
      AgentAuditEventRepository auditEventRepository = new AgentAuditEventRepository();
      auditEventRepository.bindPersistenceContext(entityManager);
      AgentGovernanceService governance =
          new AgentGovernanceServiceImpl(
              new AgentServiceImpl(agentRepository),
              new AgentAuditEventServiceImpl(auditEventRepository),
              new ActorServiceImpl(actorRepository),
              new StubWorkflowReleaseResolver());
      T result = work.apply(new Context(actorRepository, governance));
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

  private record Context(ActorRepository actorRepository, AgentGovernanceService governance) {}
}
