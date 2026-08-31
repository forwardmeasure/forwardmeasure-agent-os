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
package com.forwardmeasure.agentos.governance.application;

import com.forwardmeasure.agentos.domain.AgentStatus;
import java.util.UUID;

// deleteAgentDraft's real contract: "Only agents in draft status may be deleted." Deletion isn't
// itself a lifecycle transition AgentStatus models, so it doesn't reuse
// InvalidLifecycleTransitionException - it's a precondition on an operation the status machine
// doesn't otherwise know about. Mapped to 409 by agent-os-governance-jaxrs, same as a real
// transition conflict.
public final class AgentNotDraftException extends RuntimeException {

  public AgentNotDraftException(UUID agentId, AgentStatus actual) {
    super("agent " + agentId + " is not in draft status (actual: " + actual + ")");
  }
}
