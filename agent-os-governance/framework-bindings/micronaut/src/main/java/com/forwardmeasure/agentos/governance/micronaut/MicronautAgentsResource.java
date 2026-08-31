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
package com.forwardmeasure.agentos.governance.micronaut;

import com.forwardmeasure.agentos.domain.AgentActorResolver;
import com.forwardmeasure.agentos.governance.application.AgentGovernanceService;
import com.forwardmeasure.agentos.governance.jaxrs.AgentsResource;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

// Micronaut compile-time discovery edge for the framework-neutral resource - HTTP metadata is
// inherited from the generated AgentsApi interface via micronaut-jaxrs-server, but the concrete
// class itself must be compiled within this Micronaut module for that discovery to see it. Mirrors
// openworkflow's real MicronautWorkflowDefinitionGovernanceResource shape.
@Singleton
public final class MicronautAgentsResource extends AgentsResource {

  @Inject
  public MicronautAgentsResource(
      AgentGovernanceService governance, AgentActorResolver agentActors) {
    super(governance, agentActors);
  }
}
