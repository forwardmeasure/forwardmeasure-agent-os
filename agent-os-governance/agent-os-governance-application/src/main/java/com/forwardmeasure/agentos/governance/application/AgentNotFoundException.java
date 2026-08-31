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

import java.util.UUID;

// Covers both "does not exist" and "exists but is not visible to this tenant" - tenant isolation
// is schema-per-tenant (TenantScope, opened before AgentGovernanceService ever runs), so an id
// from another tenant's schema is indistinguishable from one that was never created. Mapped to
// 404 by agent-os-governance-jaxrs, matching every real operation's documented 404 semantics.
public final class AgentNotFoundException extends RuntimeException {

  public AgentNotFoundException(UUID agentId) {
    super("no agent visible to the caller for id " + agentId);
  }

  public AgentNotFoundException(String namespace, String name, String version) {
    super("no available agent release at " + namespace + "/" + name + "/" + version);
  }
}
