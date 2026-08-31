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
package com.forwardmeasure.agentos.execution.application;

import com.forwardmeasure.agentos.domain.AgentActor;
import com.forwardmeasure.agentos.domain.AgentCoordinates;
import com.forwardmeasure.agentos.domain.AgentExecution;
import com.forwardmeasure.agentos.domain.AgentExecutionState;
import com.forwardmeasure.agentos.domain.AgentInvocationProtocol;
import com.forwardmeasure.agentos.execution.api.model.AgentExecutionHistoryEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// The execution vertical's application-service port: every operation
// agent-execution-management.openapi.yaml exposes, expressed in domain types. Release lookup goes
// directly through agent-os-governance-application's AgentGovernanceService (this module depends
// on it, per §2.9 - "no intermediary to wire") using the same AgentActor the caller was already
// resolved to - no separate lookup mechanism, no replicated read model.
//
// agent-os-execution-jpa depends on this module (not the reverse, matching the governance
// vertical's real dependency direction) and provides the sole implementation,
// AgentExecutionServiceImpl.
public interface AgentExecutionService {

  AgentExecution start(
      AgentActor caller,
      AgentCoordinates agent,
      AgentInvocationProtocol protocol,
      String idempotencyKey,
      String correlationId,
      Object input);

  AgentExecution get(AgentActor caller, UUID executionId);

  CursorPage<AgentExecution> list(
      AgentActor caller,
      List<AgentExecutionState> states,
      AgentInvocationProtocol protocol,
      String contextId,
      Instant createdFrom,
      Instant createdUntil,
      String cursor,
      int limit);

  List<AgentExecutionHistoryEntry> history(
      AgentActor caller, UUID executionId, long afterSequence, int limit);

  AgentExecution pause(
      AgentActor caller,
      UUID executionId,
      long expectedRevision,
      String correlationId,
      String reason);

  AgentExecution resume(
      AgentActor caller,
      UUID executionId,
      long expectedRevision,
      String correlationId,
      String reason);

  AgentExecution cancel(
      AgentActor caller,
      UUID executionId,
      long expectedRevision,
      String correlationId,
      String reason);
}
