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
package com.forwardmeasure.agentos.openworkflow.client;

import com.forwardmeasure.agentos.domain.AgentExecutionState;
import com.forwardmeasure.agentos.domain.OpenWorkflowExecutionSnapshot;
import com.forwardmeasure.agentos.domain.WorkflowExecutionDispatcher;
import com.forwardmeasure.agentos.domain.WorkflowReleaseBinding;
import com.forwardmeasure.agentos.execution.api.model.AgentExecutionHistoryEntry;
import com.forwardmeasure.openworkflow.authorization.authzen.BearerTokenSupplier;
import com.forwardmeasure.openworkflow.execution.api.model.Execution;
import com.forwardmeasure.openworkflow.execution.api.model.ExecutionControl;
import com.forwardmeasure.openworkflow.execution.api.model.ExecutionHistoryEntry;
import com.forwardmeasure.openworkflow.execution.api.model.ExecutionStart;
import com.forwardmeasure.openworkflow.execution.client.ApiClient;
import com.forwardmeasure.openworkflow.execution.client.ApiException;
import com.forwardmeasure.openworkflow.execution.client.api.ExecutionsApi;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

// Wraps OpenWorkflow's generated public execution-management ApacheHttp client behind
// agent-os-domain's WorkflowExecutionDispatcher port. Every operation is a real, synchronous call
// (verified against OpenWorkflow's own generated ExecutionsApi, which returns Execution directly,
// not a queued acknowledgement) - no polling, no outbox. Authenticates the same way
// OpenWorkflowWorkflowReleaseResolver does: agent-os's own service identity via OAuth2
// client-credentials, not the end user's own bearer token - see that class's own doc comment for
// why.
public final class OpenWorkflowExecutionDispatcher implements WorkflowExecutionDispatcher {

  private final URI endpoint;
  private final BearerTokenSupplier tokens;

  public OpenWorkflowExecutionDispatcher(URI endpoint, BearerTokenSupplier tokens) {
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    this.tokens = Objects.requireNonNull(tokens, "tokens");
  }

  @Override
  public OpenWorkflowExecutionSnapshot start(
      WorkflowReleaseBinding binding, String idempotencyKey, String correlationId, Object input) {
    Objects.requireNonNull(binding, "binding");
    return call(
        api ->
            api.startExecution(
                idempotencyKey, correlationId, new ExecutionStart(binding.revisionId(), input)));
  }

  @Override
  public OpenWorkflowExecutionSnapshot get(UUID openWorkflowExecutionId) {
    return call(api -> api.getExecution(openWorkflowExecutionId));
  }

  @Override
  public OpenWorkflowExecutionSnapshot pause(
      UUID openWorkflowExecutionId,
      long openWorkflowRevision,
      String correlationId,
      String reason) {
    return call(
        api ->
            api.pauseExecution(
                etag(openWorkflowRevision),
                correlationId,
                openWorkflowExecutionId,
                new ExecutionControl().reason(reason)));
  }

  @Override
  public OpenWorkflowExecutionSnapshot resume(
      UUID openWorkflowExecutionId,
      long openWorkflowRevision,
      String correlationId,
      String reason) {
    return call(
        api ->
            api.resumeExecution(
                etag(openWorkflowRevision),
                correlationId,
                openWorkflowExecutionId,
                new ExecutionControl().reason(reason)));
  }

  @Override
  public OpenWorkflowExecutionSnapshot cancel(
      UUID openWorkflowExecutionId,
      long openWorkflowRevision,
      String correlationId,
      String reason) {
    return call(
        api ->
            api.cancelExecution(
                etag(openWorkflowRevision),
                correlationId,
                openWorkflowExecutionId,
                new ExecutionControl().reason(reason)));
  }

  @Override
  public List<AgentExecutionHistoryEntry> history(
      UUID openWorkflowExecutionId, long afterSequence, int limit) {
    ExecutionsApi api = api();
    try {
      return api
          .getExecutionHistory(openWorkflowExecutionId, afterSequence, limit)
          .getItems()
          .stream()
          .map(OpenWorkflowExecutionDispatcher::toWireHistoryEntry)
          .toList();
    } catch (ApiException failure) {
      throw new IllegalStateException(
          "OpenWorkflow execution-management call failed with HTTP " + failure.getCode(), failure);
    }
  }

  @FunctionalInterface
  private interface ExecutionCall {
    Execution invoke(ExecutionsApi api) throws ApiException;
  }

  private OpenWorkflowExecutionSnapshot call(ExecutionCall call) {
    try {
      return toSnapshot(call.invoke(api()));
    } catch (ApiException failure) {
      throw new IllegalStateException(
          "OpenWorkflow execution-management call failed with HTTP " + failure.getCode(), failure);
    }
  }

  private ExecutionsApi api() {
    ApiClient client =
        new ApiClient().setBasePath(endpoint.toString()).setBearerToken(tokens.bearerToken());
    return new ExecutionsApi(client);
  }

  private static String etag(long revision) {
    return "\"" + revision + "\"";
  }

  private static OpenWorkflowExecutionSnapshot toSnapshot(Execution execution) {
    return new OpenWorkflowExecutionSnapshot(
        execution.getId(),
        execution.getVersion(),
        execution.getEngineId(),
        toDomainState(execution.getState()),
        execution.getOutput(),
        execution.getError() == null ? null : String.valueOf(execution.getError()));
  }

  private static AgentExecutionHistoryEntry toWireHistoryEntry(ExecutionHistoryEntry entry) {
    var wire =
        new AgentExecutionHistoryEntry(
            entry.getEventId(),
            entry.getSequence(),
            com.forwardmeasure.agentos.execution.api.model.AgentExecutionState.fromValue(
                toDomainState(entry.getState()).wireValue()),
            entry.getOccurredAt());
    wire.data(entry.getData());
    return wire;
  }

  // Collapses OpenWorkflow's nine-value ExecutionState into agent-os's own six - see
  // AgentExecutionState's own doc comment for the reasoning: an in-flight transient state
  // (WAITING/PAUSING/CANCELLING) is reported as whichever state it's transitioning away from,
  // since acknowledgement of a command means the transition is durable, not that it has completed.
  private static AgentExecutionState toDomainState(
      com.forwardmeasure.openworkflow.execution.api.model.ExecutionState state) {
    return switch (state) {
      case NEW -> AgentExecutionState.SUBMITTED;
      case RUNNING, WAITING, PAUSING, CANCELLING -> AgentExecutionState.RUNNING;
      case PAUSED -> AgentExecutionState.PAUSED;
      case CANCELLED -> AgentExecutionState.CANCELLED;
      case COMPLETED -> AgentExecutionState.COMPLETED;
      case FAILED -> AgentExecutionState.FAILED;
    };
  }
}
