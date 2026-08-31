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

import com.forwardmeasure.agentos.domain.ActorReference;
import com.forwardmeasure.agentos.domain.AgentActor;
import com.forwardmeasure.agentos.domain.AgentCoordinates;
import com.forwardmeasure.agentos.domain.AgentDefinition;
import com.forwardmeasure.agentos.domain.AgentExecution;
import com.forwardmeasure.agentos.domain.AgentExecutionState;
import com.forwardmeasure.agentos.domain.AgentInvocationProtocol;
import com.forwardmeasure.agentos.domain.OpenWorkflowExecutionSnapshot;
import com.forwardmeasure.agentos.domain.WorkflowExecutionDispatcher;
import com.forwardmeasure.agentos.domain.WorkflowReleaseBinding;
import com.forwardmeasure.agentos.execution.api.model.AgentExecutionHistoryEntry;
import com.forwardmeasure.agentos.execution.application.AgentExecutionService;
import com.forwardmeasure.agentos.execution.application.CursorPage;
import com.forwardmeasure.agentos.execution.application.DuplicateIdempotencyKeyException;
import com.forwardmeasure.agentos.execution.application.ExecutionNotFoundException;
import com.forwardmeasure.agentos.execution.application.StaleExecutionRevisionException;
import com.forwardmeasure.agentos.execution.jpa.entity.AgentExecutionRecord;
import com.forwardmeasure.agentos.execution.jpa.service.AgentExecutionRecordService;
import com.forwardmeasure.agentos.governance.application.AgentGovernanceService;
import com.forwardmeasure.jpa.identity.service.ActorService;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

// The sole implementation of AgentExecutionService (agent-os-execution-application) - see that
// interface's own doc comment for why this module depends on it, not the reverse. Release lookup
// goes directly through AgentGovernanceService (agent-os-governance-application), reusing the
// caller's own already-resolved AgentActor - no separate read model, per §2.9.
//
// @Transactional is jakarta.transaction's standard annotation, the same one AbstractBaseServiceImpl
// (forwardmeasure-jpa-core) already carries - see AgentGovernanceServiceImpl's own doc comment for
// why Quarkus/Spring need nothing further here and Micronaut needs a framework-layer wrapper
// instead (the same MicronautTransactionalServiceProxy pattern, reused for this service too).
@Transactional
public class AgentExecutionServiceImpl implements AgentExecutionService {

  private final AgentExecutionRecordService executions;
  private final AgentGovernanceService governance;
  private final ActorService actors;
  private final WorkflowExecutionDispatcher dispatcher;

  public AgentExecutionServiceImpl(
      AgentExecutionRecordService executions,
      AgentGovernanceService governance,
      ActorService actors,
      WorkflowExecutionDispatcher dispatcher) {
    this.executions = Objects.requireNonNull(executions, "executions");
    this.governance = Objects.requireNonNull(governance, "governance");
    this.actors = Objects.requireNonNull(actors, "actors");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
  }

  @Override
  public AgentExecution start(
      AgentActor caller,
      AgentCoordinates agent,
      AgentInvocationProtocol protocol,
      String idempotencyKey,
      String correlationId,
      Object input) {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(agent, "agent");
    Objects.requireNonNull(protocol, "protocol");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(input, "input");

    var existing = executions.findByCommandId(idempotencyKey);
    if (existing.isPresent()) {
      AgentExecutionRecord record = existing.get();
      AgentCoordinates existingCoordinates =
          new AgentCoordinates(record.getNamespace(), record.getName(), record.getAgentVersion());
      if (!existingCoordinates.equals(agent) || !Objects.equals(record.getInput(), input)) {
        throw new DuplicateIdempotencyKeyException(idempotencyKey);
      }
      return toDomain(record);
    }

    AgentDefinition release = governance.getRelease(caller, agent);
    WorkflowReleaseBinding binding = release.workflowBinding();

    com.forwardmeasure.jpa.identity.entity.Actor owner = loadActorEntity(caller.actor());
    AgentExecutionRecord record =
        AgentExecutionRecord.builder()
            .commandId(idempotencyKey)
            .namespace(agent.namespace())
            .name(agent.name())
            .agentVersion(agent.version())
            .workflowId(binding.workflowId())
            .workflowRevisionId(binding.revisionId())
            .workflowRevisionDigest(binding.revisionDigest())
            .protocol(protocol)
            .state(AgentExecutionState.SUBMITTED)
            .correlationId(correlationId)
            .input(input)
            .owner(owner)
            .build();
    // Persisted before OpenWorkflow is ever called - see AgentExecution.admit's own doc comment.
    executions.persist(record);
    executions.flush();

    OpenWorkflowExecutionSnapshot snapshot =
        dispatcher.start(binding, idempotencyKey, correlationId, input);
    applySnapshot(record, snapshot);
    executions.flush();
    return toDomain(record);
  }

  @Override
  public AgentExecution get(AgentActor caller, UUID executionId) {
    AgentExecutionRecord record = loadEntity(caller, executionId);
    if (record.getOpenWorkflowExecutionId() != null && !isTerminal(record.getState())) {
      OpenWorkflowExecutionSnapshot snapshot = dispatcher.get(record.getOpenWorkflowExecutionId());
      applySnapshot(record, snapshot);
      executions.flush();
    }
    return toDomain(record);
  }

  @Override
  public CursorPage<AgentExecution> list(
      AgentActor caller,
      List<AgentExecutionState> states,
      AgentInvocationProtocol protocol,
      String contextId,
      Instant createdFrom,
      Instant createdUntil,
      String cursor,
      int limit) {
    Long ownerId = loadActorEntity(caller.actor()).getId();
    String queryScope = queryScope(states, protocol, contextId, createdFrom, createdUntil, limit);
    ExecutionCursor.Position after = ExecutionCursor.decode(cursor, queryScope);

    List<AgentExecutionRecord> rows =
        executions.listAfter(
            ownerId,
            states,
            protocol,
            contextId,
            createdFrom == null ? null : createdFrom.atOffset(ZoneOffset.UTC),
            createdUntil == null ? null : createdUntil.atOffset(ZoneOffset.UTC),
            after == null ? null : after.createdAt(),
            after == null ? null : after.id(),
            limit + 1);

    boolean hasMore = rows.size() > limit;
    List<AgentExecutionRecord> page = hasMore ? rows.subList(0, limit) : rows;
    String nextCursor =
        hasMore
            ? ExecutionCursor.encode(
                new ExecutionCursor.Position(
                    page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId()),
                queryScope)
            : null;
    return new CursorPage<>(page.stream().map(this::toDomain).toList(), nextCursor);
  }

  @Override
  public List<AgentExecutionHistoryEntry> history(
      AgentActor caller, UUID executionId, long afterSequence, int limit) {
    AgentExecutionRecord record = loadEntity(caller, executionId);
    if (record.getOpenWorkflowExecutionId() == null) {
      return List.of();
    }
    return dispatcher.history(record.getOpenWorkflowExecutionId(), afterSequence, limit);
  }

  @Override
  public AgentExecution pause(
      AgentActor caller,
      UUID executionId,
      long expectedRevision,
      String correlationId,
      String reason) {
    return control(
        caller,
        executionId,
        expectedRevision,
        AgentExecutionState.PAUSED,
        (openWorkflowExecutionId, openWorkflowRevision) ->
            dispatcher.pause(openWorkflowExecutionId, openWorkflowRevision, correlationId, reason));
  }

  @Override
  public AgentExecution resume(
      AgentActor caller,
      UUID executionId,
      long expectedRevision,
      String correlationId,
      String reason) {
    return control(
        caller,
        executionId,
        expectedRevision,
        AgentExecutionState.RUNNING,
        (openWorkflowExecutionId, openWorkflowRevision) ->
            dispatcher.resume(
                openWorkflowExecutionId, openWorkflowRevision, correlationId, reason));
  }

  @Override
  public AgentExecution cancel(
      AgentActor caller,
      UUID executionId,
      long expectedRevision,
      String correlationId,
      String reason) {
    return control(
        caller,
        executionId,
        expectedRevision,
        AgentExecutionState.CANCELLED,
        (openWorkflowExecutionId, openWorkflowRevision) ->
            dispatcher.cancel(
                openWorkflowExecutionId, openWorkflowRevision, correlationId, reason));
  }

  @FunctionalInterface
  private interface OpenWorkflowCommand {
    OpenWorkflowExecutionSnapshot dispatch(UUID openWorkflowExecutionId, long openWorkflowRevision);
  }

  private AgentExecution control(
      AgentActor caller,
      UUID executionId,
      long expectedRevision,
      AgentExecutionState target,
      OpenWorkflowCommand command) {
    AgentExecutionRecord record = loadEntity(caller, executionId);
    checkRevision(executionId, record, expectedRevision);
    // Pre-flight guard against agent-os's own last-known state, so an already-terminal execution
    // fails fast with the real 409 rather than a doomed call being forwarded to OpenWorkflow.
    record.getState().requireTransitionTo(target);
    if (record.getOpenWorkflowExecutionId() == null || record.getOpenWorkflowRevision() == null) {
      throw new IllegalStateException(
          "agent execution " + executionId + " has not yet been acknowledged by OpenWorkflow");
    }
    OpenWorkflowExecutionSnapshot snapshot =
        command.dispatch(record.getOpenWorkflowExecutionId(), record.getOpenWorkflowRevision());
    applySnapshot(record, snapshot);
    executions.flush();
    return toDomain(record);
  }

  private AgentExecutionRecord loadEntity(AgentActor caller, UUID executionId) {
    Objects.requireNonNull(executionId, "executionId");
    AgentExecutionRecord record =
        executions
            .findByUuid(executionId)
            .orElseThrow(() -> new ExecutionNotFoundException(executionId));
    if (!record.getOwner().getUuid().equals(caller.actor().id())) {
      throw new ExecutionNotFoundException(executionId);
    }
    return record;
  }

  private void checkRevision(UUID executionId, AgentExecutionRecord record, long expectedRevision) {
    long actual = record.getVersion().longValue();
    if (actual != expectedRevision) {
      throw new StaleExecutionRevisionException(executionId, expectedRevision, actual);
    }
  }

  private com.forwardmeasure.jpa.identity.entity.Actor loadActorEntity(ActorReference reference) {
    return actors
        .findByUuid(reference.id())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "no Actor row for "
                        + reference.id()
                        + " - AgentActorResolver already"
                        + " resolved this subject earlier in the same request"));
  }

  private static boolean isTerminal(AgentExecutionState state) {
    return state == AgentExecutionState.COMPLETED
        || state == AgentExecutionState.FAILED
        || state == AgentExecutionState.CANCELLED;
  }

  private void applySnapshot(AgentExecutionRecord record, OpenWorkflowExecutionSnapshot snapshot) {
    record.setOpenWorkflowExecutionId(snapshot.openWorkflowExecutionId());
    record.setOpenWorkflowRevision(snapshot.openWorkflowRevision());
    record.setEngineId(snapshot.engineId());
    record.setState(snapshot.state());
    if (snapshot.output() != null) {
      record.setOutput(snapshot.output());
    }
    record.setLastFailure(snapshot.lastFailure());
    if (record.getAcceptedAt() == null) {
      record.setAcceptedAt(OffsetDateTime.now(ZoneOffset.UTC));
    }
  }

  private AgentExecution toDomain(AgentExecutionRecord record) {
    return new AgentExecution(
        record.getUuid(),
        record.getCommandId(),
        new AgentCoordinates(record.getNamespace(), record.getName(), record.getAgentVersion()),
        new WorkflowReleaseBinding(
            record.getWorkflowId(),
            record.getWorkflowRevisionId(),
            record.getWorkflowRevisionDigest()),
        record.getOpenWorkflowExecutionId(),
        record.getOpenWorkflowRevision(),
        record.getEngineId(),
        record.getProtocol(),
        record.getContextId(),
        record.getState(),
        record.getVersion().longValue(),
        record.getCorrelationId(),
        record.getInput(),
        record.getOutput(),
        record.getLastFailure(),
        record.getCreatedAt().toInstant(),
        record.getUpdatedAt().toInstant(),
        record.getAcceptedAt() == null ? null : record.getAcceptedAt().toInstant());
  }

  private static String queryScope(
      List<AgentExecutionState> states,
      AgentInvocationProtocol protocol,
      String contextId,
      Instant createdFrom,
      Instant createdUntil,
      int limit) {
    return String.join(
        "|",
        states == null
            ? ""
            : states.stream().map(Enum::name).sorted().collect(Collectors.joining(",")),
        protocol == null ? "" : protocol.name(),
        contextId == null ? "" : contextId,
        createdFrom == null ? "" : createdFrom.toString(),
        createdUntil == null ? "" : createdUntil.toString(),
        Integer.toString(limit));
  }
}
