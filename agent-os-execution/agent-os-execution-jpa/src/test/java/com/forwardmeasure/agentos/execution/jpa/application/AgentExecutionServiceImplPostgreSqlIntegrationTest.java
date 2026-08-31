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
package com.forwardmeasure.agentos.execution.jpa.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forwardmeasure.agentos.domain.ActorReference;
import com.forwardmeasure.agentos.domain.ActorType;
import com.forwardmeasure.agentos.domain.AgentActor;
import com.forwardmeasure.agentos.domain.AgentCoordinates;
import com.forwardmeasure.agentos.domain.AgentDefinition;
import com.forwardmeasure.agentos.domain.AgentExecution;
import com.forwardmeasure.agentos.domain.AgentExecutionState;
import com.forwardmeasure.agentos.domain.AgentInvocationProtocol;
import com.forwardmeasure.agentos.domain.OpenWorkflowExecutionSnapshot;
import com.forwardmeasure.agentos.domain.WorkflowReleaseBinding;
import com.forwardmeasure.agentos.domain.WorkflowReleaseResolver;
import com.forwardmeasure.agentos.execution.application.AgentExecutionService;
import com.forwardmeasure.agentos.execution.application.DuplicateIdempotencyKeyException;
import com.forwardmeasure.agentos.execution.application.StaleExecutionRevisionException;
import com.forwardmeasure.agentos.execution.jpa.repository.AgentExecutionRepository;
import com.forwardmeasure.agentos.execution.jpa.service.impl.AgentExecutionRecordServiceImpl;
import com.forwardmeasure.agentos.governance.api.model.AgentDefinitionContent;
import com.forwardmeasure.agentos.governance.api.model.AgentProtocols;
import com.forwardmeasure.agentos.governance.api.model.AgentSkill;
import com.forwardmeasure.agentos.governance.application.AgentGovernanceService;
import com.forwardmeasure.agentos.governance.jpa.application.AgentGovernanceServiceImpl;
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

// Exercises AgentExecutionServiceImpl against a real Postgres schema that also holds governance's
// own tables - execution reads release availability directly from them in-process (see
// AgentExecutionServiceImpl's own doc comment), so a meaningful test needs a real published Agent
// row, not a stub.
@WithPostgreSqlContainer(databaseName = "agent_os_execution_application_contract")
class AgentExecutionServiceImplPostgreSqlIntegrationTest {

  private static final AgentCoordinates COORDINATES =
      new AgentCoordinates("acme", "invoice-reconciler", "1.0.0");

  @Test
  void startsGetsPausesAndListsAgentExecutions(PostgreSqlTestContainer database) {
    TenantSchema tenant = prepare(database);
    try (EntityManagerFactory entityManagers = entityManagers(database, tenant)) {
      AgentActor[] owner = new AgentActor[1];
      AgentActor[] reviewer = new AgentActor[1];
      WorkflowReleaseBinding[] binding = new WorkflowReleaseBinding[1];

      inTransaction(
          entityManagers,
          context -> {
            owner[0] = actor(context, "owner");
            reviewer[0] = actor(context, "reviewer");
            AgentDefinition draft =
                context.governance().createDraft(owner[0], COORDINATES, content());
            AgentDefinition submitted =
                context.governance().submitForReview(owner[0], draft.id(), draft.revision(), null);
            AgentDefinition approved =
                context.governance().approve(reviewer[0], draft.id(), submitted.revision(), null);
            AgentDefinition published =
                context
                    .governance()
                    .publish(
                        owner[0],
                        draft.id(),
                        approved.revision(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "publish-correlation");
            binding[0] = published.workflowBinding();
            return null;
          });

      AgentExecution started =
          inTransaction(
              entityManagers,
              context ->
                  context
                      .execution()
                      .start(
                          owner[0],
                          COORDINATES,
                          AgentInvocationProtocol.REST,
                          "idempotency-key-1",
                          "start-correlation",
                          Map.of("amount", 100)));
      assertEquals(AgentExecutionState.RUNNING, started.state());
      assertEquals(binding[0], started.workflowBinding());

      // Idempotent replay: same key, same input -> the same execution, no new dispatch.
      AgentExecution replayed =
          inTransaction(
              entityManagers,
              context ->
                  context
                      .execution()
                      .start(
                          owner[0],
                          COORDINATES,
                          AgentInvocationProtocol.REST,
                          "idempotency-key-1",
                          "start-correlation",
                          Map.of("amount", 100)));
      assertEquals(started.id(), replayed.id());
      assertEquals(started.revision(), replayed.revision());

      // Same key, different input -> a real conflict, not a silent overwrite.
      assertThrows(
          DuplicateIdempotencyKeyException.class,
          () ->
              inTransaction(
                  entityManagers,
                  context ->
                      context
                          .execution()
                          .start(
                              owner[0],
                              COORDINATES,
                              AgentInvocationProtocol.REST,
                              "idempotency-key-1",
                              "start-correlation",
                              Map.of("amount", 999))));

      AgentExecution fetched =
          inTransaction(entityManagers, context -> context.execution().get(owner[0], started.id()));
      assertEquals(started.id(), fetched.id());

      assertThrows(
          StaleExecutionRevisionException.class,
          () ->
              inTransaction(
                  entityManagers,
                  context ->
                      context
                          .execution()
                          .pause(owner[0], started.id(), 999L, "pause-correlation", "stale test")));

      AgentExecution paused =
          inTransaction(
              entityManagers,
              context ->
                  context
                      .execution()
                      .pause(
                          owner[0],
                          started.id(),
                          fetched.revision(),
                          "pause-correlation",
                          "pausing"));
      assertEquals(AgentExecutionState.PAUSED, paused.state());

      var page =
          inTransaction(
              entityManagers,
              context ->
                  context.execution().list(owner[0], null, null, null, null, null, null, 10));
      assertEquals(1, page.items().size());
      assertEquals(started.id(), page.items().get(0).id());
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

  private static final class StubWorkflowReleaseResolver implements WorkflowReleaseResolver {
    @Override
    public WorkflowReleaseBinding resolvePublishedRevision(UUID workflowId, UUID revisionId) {
      return new WorkflowReleaseBinding(workflowId, revisionId, "c".repeat(64));
    }
  }

  // A synchronous stub matching OpenWorkflow's own real dispatch shape - see
  // WorkflowExecutionDispatcher's own doc comment for why this is synchronous, not queued.
  private static final class StubWorkflowExecutionDispatcher
      implements com.forwardmeasure.agentos.domain.WorkflowExecutionDispatcher {
    private long revision;

    @Override
    public OpenWorkflowExecutionSnapshot start(
        WorkflowReleaseBinding binding, String idempotencyKey, String correlationId, Object input) {
      revision = 1;
      return new OpenWorkflowExecutionSnapshot(
          UUID.randomUUID(), revision, "engine-1", AgentExecutionState.RUNNING, null, null);
    }

    @Override
    public OpenWorkflowExecutionSnapshot get(UUID openWorkflowExecutionId) {
      return new OpenWorkflowExecutionSnapshot(
          openWorkflowExecutionId, revision, "engine-1", AgentExecutionState.RUNNING, null, null);
    }

    @Override
    public OpenWorkflowExecutionSnapshot pause(
        UUID openWorkflowExecutionId,
        long openWorkflowRevision,
        String correlationId,
        String reason) {
      revision = openWorkflowRevision + 1;
      return new OpenWorkflowExecutionSnapshot(
          openWorkflowExecutionId, revision, "engine-1", AgentExecutionState.PAUSED, null, null);
    }

    @Override
    public OpenWorkflowExecutionSnapshot resume(
        UUID openWorkflowExecutionId,
        long openWorkflowRevision,
        String correlationId,
        String reason) {
      revision = openWorkflowRevision + 1;
      return new OpenWorkflowExecutionSnapshot(
          openWorkflowExecutionId, revision, "engine-1", AgentExecutionState.RUNNING, null, null);
    }

    @Override
    public OpenWorkflowExecutionSnapshot cancel(
        UUID openWorkflowExecutionId,
        long openWorkflowRevision,
        String correlationId,
        String reason) {
      revision = openWorkflowRevision + 1;
      return new OpenWorkflowExecutionSnapshot(
          openWorkflowExecutionId, revision, "engine-1", AgentExecutionState.CANCELLED, null, null);
    }

    @Override
    public List<com.forwardmeasure.agentos.execution.api.model.AgentExecutionHistoryEntry> history(
        UUID openWorkflowExecutionId, long afterSequence, int limit) {
      return List.of();
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

  private TenantSchema prepare(PostgreSqlTestContainer database) {
    TenantSchema tenant = TenantSchema.forTenant(new TenantId(UUID.randomUUID()));
    database.createSchema(tenant.value());
    TenantSchemaMigrator migrator =
        new TenantSchemaMigrator(
            database.dataSource(),
            "db/changelog/agent-os-execution-jpa-test.xml",
            getClass().getClassLoader());
    assertTrue(migrator.validate(tenant).valid());
    migrator.migrate(tenant);
    assertTrue(migrator.status(tenant).current());
    return tenant;
  }

  private EntityManagerFactory entityManagers(
      PostgreSqlTestContainer database, TenantSchema tenant) {
    return Persistence.createEntityManagerFactory(
        "agent-os-execution-jpa-test",
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

      AgentExecutionRepository executionRepository = new AgentExecutionRepository();
      executionRepository.bindPersistenceContext(entityManager);
      AgentExecutionService execution =
          new AgentExecutionServiceImpl(
              new AgentExecutionRecordServiceImpl(executionRepository),
              governance,
              new ActorServiceImpl(actorRepository),
              new StubWorkflowExecutionDispatcher());

      T result = work.apply(new Context(actorRepository, governance, execution));
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

  private record Context(
      ActorRepository actorRepository,
      AgentGovernanceService governance,
      AgentExecutionService execution) {}
}
