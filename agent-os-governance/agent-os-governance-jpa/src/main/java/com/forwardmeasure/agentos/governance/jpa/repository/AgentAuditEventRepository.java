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
package com.forwardmeasure.agentos.governance.jpa.repository;

import com.forwardmeasure.agentos.governance.jpa.entity.AgentAuditEventRecord;
import com.forwardmeasure.jpa.core.query.Page;
import com.forwardmeasure.jpa.core.query.PageRequest;
import com.forwardmeasure.jpa.core.query.SortDirection;
import com.forwardmeasure.jpa.core.query.SortOrder;
import com.forwardmeasure.jpa.core.repository.AbstractBaseRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class AgentAuditEventRepository extends AbstractBaseRepository<AgentAuditEventRecord, Long> {

  // listAgentAuditEvents' real contract: "Audit event page, ordered oldest first."
  public Page<AgentAuditEventRecord> pageByAgentId(UUID agentId, int offset, int limit) {
    Objects.requireNonNull(agentId, "agentId");
    PageRequest request =
        new PageRequest(
            offset, limit, List.of(new SortOrder("occurredAt", SortDirection.ASCENDING)));
    return page(request, (root, query, builder) -> builder.equal(root.get("agentId"), agentId));
  }
}
