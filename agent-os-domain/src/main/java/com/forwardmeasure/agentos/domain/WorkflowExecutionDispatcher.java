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

import com.forwardmeasure.agentos.execution.api.model.AgentExecutionHistoryEntry;
import java.util.List;
import java.util.UUID;

// Dispatches durable execution commands to OpenWorkflow's own execution-management API - real,
// synchronous calls (verified against OpenWorkflow's generated ExecutionsApi client, which returns
// Execution directly, not a queued acknowledgement), never an outbox or message queue. Lives here,
// not in agent-os-execution-application, for the same reason WorkflowReleaseResolver does: so
// agent-os-openworkflow-client never depends on a single vertical's application module. Every
// mutating method's openWorkflowRevision is OpenWorkflow's own Execution.version - never agent-os's
// own AgentExecution.revision - used to build the If-Match header OpenWorkflow's own
// pause/resume/cancel operations require.
public interface WorkflowExecutionDispatcher {

  OpenWorkflowExecutionSnapshot start(
      WorkflowReleaseBinding binding, String idempotencyKey, String correlationId, Object input);

  OpenWorkflowExecutionSnapshot get(UUID openWorkflowExecutionId);

  OpenWorkflowExecutionSnapshot pause(
      UUID openWorkflowExecutionId, long openWorkflowRevision, String correlationId, String reason);

  OpenWorkflowExecutionSnapshot resume(
      UUID openWorkflowExecutionId, long openWorkflowRevision, String correlationId, String reason);

  OpenWorkflowExecutionSnapshot cancel(
      UUID openWorkflowExecutionId, long openWorkflowRevision, String correlationId, String reason);

  // getAgentExecutionHistory's real contract: "Retrieve ordered execution history, proxied from
  // OpenWorkflow" - a live read-through, not a locally persisted copy. Returns the generated wire
  // AgentExecutionHistoryEntry directly (reused, not hand-rolled) - the implementation maps from
  // OpenWorkflow's own, differently-shaped ExecutionHistoryEntry.
  List<AgentExecutionHistoryEntry> history(
      UUID openWorkflowExecutionId, long afterSequence, int limit);
}
