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
package com.forwardmeasure.agentos.execution.jpa.service;

import com.forwardmeasure.agentos.domain.AgentExecutionState;
import com.forwardmeasure.agentos.domain.AgentInvocationProtocol;
import com.forwardmeasure.agentos.execution.jpa.entity.AgentExecutionRecord;
import com.forwardmeasure.jpa.identity.service.OwnedEntityService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

// Named distinctly from agent-os-execution-application's own AgentExecutionService (the
// application-facing port) - this is an internal persistence collaborator of
// AgentExecutionServiceImpl (execution/jpa/application/), not the port itself.
public interface AgentExecutionRecordService
    extends OwnedEntityService<AgentExecutionRecord, Long> {

  Optional<AgentExecutionRecord> findByCommandId(String commandId);

  List<AgentExecutionRecord> listAfter(
      Long ownerId,
      List<AgentExecutionState> states,
      AgentInvocationProtocol protocol,
      String contextId,
      OffsetDateTime createdFrom,
      OffsetDateTime createdUntil,
      OffsetDateTime afterCreatedAt,
      Long afterId,
      int limit);
}
