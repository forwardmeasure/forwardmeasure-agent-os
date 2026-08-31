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
package com.forwardmeasure.agentos.execution.jpa.repository;

import com.forwardmeasure.agentos.domain.AgentExecutionState;
import com.forwardmeasure.agentos.domain.AgentInvocationProtocol;
import com.forwardmeasure.agentos.execution.jpa.entity.AgentExecutionRecord;
import com.forwardmeasure.agentos.execution.jpa.entity.AgentExecutionRecord_;
import com.forwardmeasure.jpa.identity.repository.AbstractOwnedEntityRepository;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class AgentExecutionRepository
    extends AbstractOwnedEntityRepository<AgentExecutionRecord, Long> {

  // startAgentExecution's idempotent-replay lookup: a reused Idempotency-Key resolves this row
  // directly rather than re-dispatching to OpenWorkflow.
  public Optional<AgentExecutionRecord> findByCommandId(String commandId) {
    Objects.requireNonNull(commandId, "commandId");
    var builder = criteriaBuilder();
    CriteriaQuery<AgentExecutionRecord> query = builder.createQuery(entityClass());
    Root<AgentExecutionRecord> root = query.from(entityClass());
    query.select(root).where(builder.equal(root.get(AgentExecutionRecord_.commandId), commandId));
    return entityManager().createQuery(query).setMaxResults(1).getResultList().stream().findFirst();
  }

  // listAgentExecutions' real contract: cursor (keyset) pagination, newest first. Fetches
  // limit + 1 rows so the caller can tell whether a next page exists without a separate count
  // query - see AgentExecutionServiceImpl.list for how the extra row becomes nextCursor.
  public List<AgentExecutionRecord> listAfter(
      Long ownerId,
      List<AgentExecutionState> states,
      AgentInvocationProtocol protocol,
      String contextId,
      OffsetDateTime createdFrom,
      OffsetDateTime createdUntil,
      OffsetDateTime afterCreatedAt,
      Long afterId,
      int limit) {
    var builder = criteriaBuilder();
    CriteriaQuery<AgentExecutionRecord> query = builder.createQuery(entityClass());
    Root<AgentExecutionRecord> root = query.from(entityClass());

    List<Predicate> predicates = new ArrayList<>();
    predicates.add(builder.equal(root.get(AgentExecutionRecord_.owner).get("id"), ownerId));
    if (states != null && !states.isEmpty()) {
      predicates.add(root.get(AgentExecutionRecord_.state).in(states));
    }
    if (protocol != null) {
      predicates.add(builder.equal(root.get(AgentExecutionRecord_.protocol), protocol));
    }
    if (contextId != null) {
      predicates.add(builder.equal(root.get(AgentExecutionRecord_.contextId), contextId));
    }
    if (createdFrom != null) {
      predicates.add(
          builder.greaterThanOrEqualTo(root.get(AgentExecutionRecord_.createdAt), createdFrom));
    }
    if (createdUntil != null) {
      predicates.add(
          builder.lessThanOrEqualTo(root.get(AgentExecutionRecord_.createdAt), createdUntil));
    }
    if (afterCreatedAt != null && afterId != null) {
      predicates.add(
          builder.or(
              builder.lessThan(root.get(AgentExecutionRecord_.createdAt), afterCreatedAt),
              builder.and(
                  builder.equal(root.get(AgentExecutionRecord_.createdAt), afterCreatedAt),
                  builder.lessThan(root.get(AgentExecutionRecord_.id), afterId))));
    }

    query
        .select(root)
        .where(predicates.toArray(new Predicate[0]))
        .orderBy(
            builder.desc(root.get(AgentExecutionRecord_.createdAt)),
            builder.desc(root.get(AgentExecutionRecord_.id)));
    return entityManager().createQuery(query).setMaxResults(limit).getResultList();
  }
}
