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

import java.util.Objects;
import java.util.UUID;

// The authenticated caller of the current request, plus which tenant they're acting within -
// resolved once per request by a framework-specific agent-os-{fw}-actor-binding from the
// incoming JWT, then reused identically by every hosted vertical (the single highest-value fix
// identified from the old repo's triplicated, security-relevant claims-resolution code).
//
// tenantId is a raw UUID, not forwardmeasure-jpa-tenancy's TenantId - that type is technology-
// free (a plain UUID wrapper, no JPA/Hibernate dependency of its own) but still lives under the
// com.forwardmeasure.jpa.. package agent-os-domain's ArchUnit rule blocks by name. The
// actor-binding modules wrap this value into TenantId/TenantSchema where they actually need
// that behavior, at the point they open the persistence layer's TenantScope.
public record AgentActor(ActorReference actor, UUID tenantId) {

  public AgentActor {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(tenantId, "tenantId");
  }
}
