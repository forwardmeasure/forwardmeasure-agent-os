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
package com.forwardmeasure.agentos.governance.jpa.service;

import com.forwardmeasure.agentos.governance.jpa.entity.Agent;
import com.forwardmeasure.jpa.identity.service.OwnedEntityService;
import java.util.Optional;

// An internal persistence collaborator of AgentGovernanceServiceImpl (application/) - not
// AgentRepository directly, which is a jakarta.persistence/Criteria-API-flavored implementation
// detail this interface exists to hide, matching forwardmeasure-jpa's own
// OwnedEntityService/AuditedEntityService pattern. Not the application-facing port itself: that is
// agent-os-governance-application's AgentGovernanceService, which this module depends on (see this
// module's pom) and implements - never the reverse.
public interface AgentService extends OwnedEntityService<Agent, Long> {

  Optional<Agent> findAvailableRelease(String namespace, String name, String agentVersion);

  Optional<Agent> findByCoordinates(String namespace, String name, String agentVersion);
}
