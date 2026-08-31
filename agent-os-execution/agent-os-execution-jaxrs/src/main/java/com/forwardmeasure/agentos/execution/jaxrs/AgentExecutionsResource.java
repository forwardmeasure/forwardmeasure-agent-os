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
package com.forwardmeasure.agentos.execution.jaxrs;

import com.forwardmeasure.agentos.domain.AgentActorResolver;
import com.forwardmeasure.agentos.domain.AgentExecution;
import com.forwardmeasure.agentos.execution.api.AgentExecutionsApi;
import com.forwardmeasure.agentos.execution.api.model.AgentExecutionControl;
import com.forwardmeasure.agentos.execution.api.model.AgentExecutionHistoryPage;
import com.forwardmeasure.agentos.execution.api.model.AgentExecutionPage;
import com.forwardmeasure.agentos.execution.api.model.AgentExecutionStart;
import com.forwardmeasure.agentos.execution.api.model.AgentExecutionState;
import com.forwardmeasure.agentos.execution.api.model.AgentInvocationProtocol;
import com.forwardmeasure.agentos.execution.application.AgentExecutionService;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

// Hosted identically by every agent-os-execution-{fw} module - see agent-os-governance-jaxrs's
// own AgentsResource for the shared shape (one agentActors.withActor(...) call per method body).
public class AgentExecutionsResource implements AgentExecutionsApi {

  private final AgentExecutionService execution;
  private final AgentActorResolver agentActors;

  public AgentExecutionsResource(AgentExecutionService execution, AgentActorResolver agentActors) {
    this.execution = Objects.requireNonNull(execution, "execution");
    this.agentActors = Objects.requireNonNull(agentActors, "agentActors");
  }

  @Override
  public Response startAgentExecution(
      String idempotencyKey, String xCorrelationID, AgentExecutionStart agentExecutionStart) {
    return agentActors.withActor(
        actor -> {
          AgentExecution started =
              execution.start(
                  actor,
                  AgentExecutionWireMapper.coordinatesOf(agentExecutionStart.getAgent()),
                  // REST is the real protocol for any call arriving through this resource - A2A/MCP
                  // invocations arrive through their own protocol edges, not this one.
                  com.forwardmeasure.agentos.domain.AgentInvocationProtocol.REST,
                  idempotencyKey,
                  xCorrelationID,
                  agentExecutionStart.getInput());
          return Response.status(202)
              .tag(ExecutionRevisionHeaders.etag(started.revision()))
              .entity(AgentExecutionWireMapper.toWire(started))
              .build();
        });
  }

  @Override
  public Response getAgentExecution(UUID executionId) {
    return agentActors.withActor(
        actor -> {
          AgentExecution found = execution.get(actor, executionId);
          return Response.ok(AgentExecutionWireMapper.toWire(found))
              .tag(ExecutionRevisionHeaders.etag(found.revision()))
              .build();
        });
  }

  @Override
  public Response getAgentExecutionHistory(UUID executionId, Long afterSequence, Integer limit) {
    return agentActors.withActor(
        actor -> {
          var entries = execution.history(actor, executionId, afterSequence, limit);
          var wirePage = new AgentExecutionHistoryPage(entries);
          if (!entries.isEmpty()) {
            wirePage.nextAfterSequence(entries.get(entries.size() - 1).getSequence());
          }
          return Response.ok(wirePage).build();
        });
  }

  @Override
  public Response listAgentExecutions(
      List<AgentExecutionState> state,
      AgentInvocationProtocol protocol,
      String contextId,
      java.util.Date createdFrom,
      java.util.Date createdUntil,
      String cursor,
      Integer limit) {
    return agentActors.withActor(
        actor -> {
          List<com.forwardmeasure.agentos.domain.AgentExecutionState> domainStates =
              state == null ? null : state.stream().map(AgentExecutionWireMapper::stateOf).toList();
          com.forwardmeasure.agentos.domain.AgentInvocationProtocol domainProtocol =
              protocol == null ? null : AgentExecutionWireMapper.protocolOf(protocol);
          Instant from = createdFrom == null ? null : createdFrom.toInstant();
          Instant until = createdUntil == null ? null : createdUntil.toInstant();
          var page =
              execution.list(
                  actor, domainStates, domainProtocol, contextId, from, until, cursor, limit);
          var wirePage =
              new AgentExecutionPage(
                  page.items().stream().map(AgentExecutionWireMapper::toWire).toList());
          if (page.nextCursor() != null) {
            wirePage.nextCursor(page.nextCursor());
          }
          return Response.ok(wirePage).build();
        });
  }

  @Override
  public Response pauseAgentExecution(
      String ifMatch,
      String xCorrelationID,
      UUID executionId,
      AgentExecutionControl agentExecutionControl) {
    return agentActors.withActor(
        actor -> {
          AgentExecution paused =
              execution.pause(
                  actor,
                  executionId,
                  ExecutionRevisionHeaders.parseIfMatch(ifMatch),
                  xCorrelationID,
                  reasonOf(agentExecutionControl));
          return Response.status(202)
              .tag(ExecutionRevisionHeaders.etag(paused.revision()))
              .entity(AgentExecutionWireMapper.toWire(paused))
              .build();
        });
  }

  @Override
  public Response resumeAgentExecution(
      String ifMatch,
      String xCorrelationID,
      UUID executionId,
      AgentExecutionControl agentExecutionControl) {
    return agentActors.withActor(
        actor -> {
          AgentExecution resumed =
              execution.resume(
                  actor,
                  executionId,
                  ExecutionRevisionHeaders.parseIfMatch(ifMatch),
                  xCorrelationID,
                  reasonOf(agentExecutionControl));
          return Response.status(202)
              .tag(ExecutionRevisionHeaders.etag(resumed.revision()))
              .entity(AgentExecutionWireMapper.toWire(resumed))
              .build();
        });
  }

  @Override
  public Response cancelAgentExecution(
      String ifMatch,
      String xCorrelationID,
      UUID executionId,
      AgentExecutionControl agentExecutionControl) {
    return agentActors.withActor(
        actor -> {
          AgentExecution cancelled =
              execution.cancel(
                  actor,
                  executionId,
                  ExecutionRevisionHeaders.parseIfMatch(ifMatch),
                  xCorrelationID,
                  reasonOf(agentExecutionControl));
          return Response.status(202)
              .tag(ExecutionRevisionHeaders.etag(cancelled.revision()))
              .entity(AgentExecutionWireMapper.toWire(cancelled))
              .build();
        });
  }

  private static String reasonOf(AgentExecutionControl control) {
    return control == null ? null : control.getReason();
  }
}
