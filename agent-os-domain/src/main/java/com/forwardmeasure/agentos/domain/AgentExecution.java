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
package com.forwardmeasure.agentos.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

// The execution aggregate: coordinates of the agent invoked, the exact published workflow revision
// it was bound to at admission time, OpenWorkflow's own execution identity once acknowledged, and
// the collapsed wire-level state (see AgentExecutionState's own doc comment). Verified against
// agent-execution-management.openapi.yaml's AgentExecution schema and its real path operations.
//
// Unlike AgentDefinition, this aggregate has no pause()/resume()/cancel() transition methods of
// its own: OpenWorkflow's startExecution/pauseExecution/resumeExecution/cancelExecution are
// themselves synchronous calls that return the authoritative new Execution state (verified against
// OpenWorkflow's real generated ExecutionsApi client, which returns Execution directly, not a
// queued acknowledgement) - agent-os-execution-application pre-flight-checks a command is legal
// with state().requireTransitionTo(...) before dispatching, then applies whatever state
// OpenWorkflow's response actually reports via withOpenWorkflowSnapshot, unconditionally: once
// dispatched, OpenWorkflow is the source of truth, not a local guess re-validated against this
// enum's own transition table.
public record AgentExecution(
    UUID id,
    String commandId,
    AgentCoordinates agent,
    WorkflowReleaseBinding workflowBinding,
    UUID openWorkflowExecutionId,
    // OpenWorkflow's own optimistic-lock revision on its Execution - distinct from this
    // aggregate's own `revision` (agent-os's own ETag). Needed only internally, to build the
    // If-Match header OpenWorkflow's own pause/resume/cancel calls require; never exposed on the
    // wire AgentExecution, which carries exactly one version field (this aggregate's own).
    Long openWorkflowRevision,
    String engineId,
    AgentInvocationProtocol protocol,
    String contextId,
    AgentExecutionState state,
    long revision,
    String correlationId,
    Object input,
    Object output,
    String lastFailure,
    Instant createdAt,
    Instant updatedAt,
    Instant acceptedAt) {

  public AgentExecution {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(commandId, "commandId");
    Objects.requireNonNull(agent, "agent");
    Objects.requireNonNull(workflowBinding, "workflowBinding");
    Objects.requireNonNull(protocol, "protocol");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  // The initial persisted row, written before OpenWorkflow is ever called - see
  // agent-os-execution-application's real dispatch order: persist SUBMITTED first (so a crash
  // between persisting and calling OpenWorkflow leaves a recoverable, visible row under the
  // caller's own Idempotency-Key), then dispatch, then apply the real snapshot.
  public static AgentExecution admit(
      AgentCoordinates agent,
      WorkflowReleaseBinding workflowBinding,
      AgentInvocationProtocol protocol,
      String commandId,
      String correlationId,
      Object input,
      Instant now) {
    return new AgentExecution(
        UUID.randomUUID(),
        commandId,
        agent,
        workflowBinding,
        null,
        null,
        null,
        protocol,
        null,
        AgentExecutionState.SUBMITTED,
        0,
        correlationId,
        input,
        null,
        null,
        now,
        now,
        null);
  }

  // Applied unconditionally after every synchronous OpenWorkflow call (start/get/pause/resume/
  // cancel all return a fresh Execution) - OpenWorkflow is the authoritative source for state
  // once dispatched, not re-validated against AgentExecutionState's own transition table.
  public AgentExecution withOpenWorkflowSnapshot(
      UUID openWorkflowExecutionId,
      Long openWorkflowRevision,
      String engineId,
      AgentExecutionState state,
      Object output,
      String lastFailure,
      Instant now) {
    return new AgentExecution(
        id,
        commandId,
        agent,
        workflowBinding,
        openWorkflowExecutionId,
        openWorkflowRevision,
        engineId,
        protocol,
        contextId,
        state,
        revision,
        correlationId,
        input,
        output,
        lastFailure,
        createdAt,
        now,
        acceptedAt == null ? now : acceptedAt);
  }
}
