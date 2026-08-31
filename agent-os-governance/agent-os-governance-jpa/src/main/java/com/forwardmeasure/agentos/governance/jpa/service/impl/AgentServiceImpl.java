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
package com.forwardmeasure.agentos.governance.jpa.service.impl;

import com.forwardmeasure.agentos.governance.jpa.entity.Agent;
import com.forwardmeasure.agentos.governance.jpa.repository.AgentRepository;
import com.forwardmeasure.agentos.governance.jpa.service.AgentService;
import com.forwardmeasure.jpa.identity.service.impl.OwnedEntityServiceImpl;
import java.util.Optional;

public class AgentServiceImpl extends OwnedEntityServiceImpl<Agent, Long, AgentRepository>
    implements AgentService {

  public AgentServiceImpl(AgentRepository repository) {
    super(repository);
  }

  @Override
  public Optional<Agent> findAvailableRelease(String namespace, String name, String agentVersion) {
    return repository().findAvailableRelease(namespace, name, agentVersion);
  }

  @Override
  public Optional<Agent> findByCoordinates(String namespace, String name, String agentVersion) {
    return repository().findByCoordinates(namespace, name, agentVersion);
  }
}
