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

import com.forwardmeasure.agentos.domain.AgentCoordinates;
import com.forwardmeasure.agentos.domain.AgentExecution;
import com.forwardmeasure.agentos.domain.AgentExecutionState;
import com.forwardmeasure.agentos.domain.AgentInvocationProtocol;
import com.forwardmeasure.agentos.domain.WorkflowReleaseBinding;
import java.util.Date;

// Hand-written, not MapStruct - see agent-os-governance-jaxrs's own AgentWireMapper for why this
// module makes the same choice. Every schema here (AgentCoordinates, WorkflowReleaseBinding,
// AgentInvocationProtocol) is the execution-management spec's own generated copy of a shape shared
// conceptually with the governance-management spec - a separate class in a separate package, not a
// hand-rolled duplicate: each service's OpenAPI generation run produces its own model module, and
// this is that generator's own real output, not invented.
final class AgentExecutionWireMapper {

  private AgentExecutionWireMapper() {}

  static com.forwardmeasure.agentos.execution.api.model.AgentExecution toWire(
      AgentExecution domain) {
    var wire =
        new com.forwardmeasure.agentos.execution.api.model.AgentExecution(
            domain.id(),
            domain.commandId(),
            toWireCoordinates(domain.agent()),
            toWireBinding(domain.workflowBinding()),
            toWireProtocol(domain.protocol()),
            toWireState(domain.state()),
            domain.revision(),
            domain.correlationId(),
            domain.input(),
            Date.from(domain.createdAt()),
            Date.from(domain.updatedAt()));
    if (domain.openWorkflowExecutionId() != null) {
      wire.openWorkflowExecutionId(domain.openWorkflowExecutionId());
    }
    if (domain.engineId() != null) {
      wire.engineId(domain.engineId());
    }
    if (domain.contextId() != null) {
      wire.contextId(domain.contextId());
    }
    if (domain.output() != null) {
      wire.output(domain.output());
    }
    if (domain.lastFailure() != null) {
      wire.lastFailure(domain.lastFailure());
    }
    if (domain.acceptedAt() != null) {
      wire.acceptedAt(Date.from(domain.acceptedAt()));
    }
    return wire;
  }

  static AgentCoordinates coordinatesOf(
      com.forwardmeasure.agentos.execution.api.model.AgentCoordinates wire) {
    return new AgentCoordinates(wire.getNamespace(), wire.getName(), wire.getVersion());
  }

  private static com.forwardmeasure.agentos.execution.api.model.AgentCoordinates toWireCoordinates(
      AgentCoordinates domain) {
    return new com.forwardmeasure.agentos.execution.api.model.AgentCoordinates(
        domain.namespace(), domain.name(), domain.version());
  }

  private static com.forwardmeasure.agentos.execution.api.model.WorkflowReleaseBinding
      toWireBinding(WorkflowReleaseBinding domain) {
    return new com.forwardmeasure.agentos.execution.api.model.WorkflowReleaseBinding(
        domain.workflowId(), domain.revisionId(), domain.revisionDigest());
  }

  private static com.forwardmeasure.agentos.execution.api.model.AgentInvocationProtocol
      toWireProtocol(AgentInvocationProtocol domain) {
    return com.forwardmeasure.agentos.execution.api.model.AgentInvocationProtocol.fromValue(
        domain.wireValue());
  }

  static com.forwardmeasure.agentos.execution.api.model.AgentExecutionState toWireState(
      AgentExecutionState domain) {
    return com.forwardmeasure.agentos.execution.api.model.AgentExecutionState.fromValue(
        domain.wireValue());
  }

  static AgentExecutionState stateOf(
      com.forwardmeasure.agentos.execution.api.model.AgentExecutionState wire) {
    return AgentExecutionState.fromWireValue(wire.toString());
  }

  static AgentInvocationProtocol protocolOf(
      com.forwardmeasure.agentos.execution.api.model.AgentInvocationProtocol wire) {
    return AgentInvocationProtocol.fromWireValue(wire.toString());
  }
}
