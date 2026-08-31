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

import com.forwardmeasure.agentos.domain.AgentStatus;
import com.forwardmeasure.agentos.governance.jpa.entity.Agent;
import com.forwardmeasure.agentos.governance.jpa.entity.Agent_;
import com.forwardmeasure.jpa.identity.repository.AbstractOwnedEntityRepository;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.Objects;
import java.util.Optional;

public class AgentRepository extends AbstractOwnedEntityRepository<Agent, Long> {

  // The one read method execution depends on (docs/implementation-plan.md §2.9): find the
  // AVAILABLE release for a tenant's own agent coordinates - AVAILABLE means published and not
  // yet deprecated, per deprecateAgent's real description ("Marks the release unavailable for
  // new invocations"). A single indexed query, no separate read-model pipeline.
  public Optional<Agent> findAvailableRelease(String namespace, String name, String agentVersion) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(agentVersion, "agentVersion");
    var builder = criteriaBuilder();
    CriteriaQuery<Agent> query = builder.createQuery(entityClass());
    Root<Agent> root = query.from(entityClass());
    query
        .select(root)
        .where(
            builder.equal(root.get(Agent_.namespace), namespace),
            builder.equal(root.get(Agent_.name), name),
            builder.equal(root.get(Agent_.agentVersion), agentVersion),
            builder.equal(root.get(Agent_.status), AgentStatus.PUBLISHED));
    return entityManager().createQuery(query).setMaxResults(1).getResultList().stream().findFirst();
  }

  // createAgentDraft's real contract: "The (namespace, name, version) coordinate must be unique
  // within the authenticated tenant" - regardless of status, unlike findAvailableRelease above.
  public Optional<Agent> findByCoordinates(String namespace, String name, String agentVersion) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(agentVersion, "agentVersion");
    var builder = criteriaBuilder();
    CriteriaQuery<Agent> query = builder.createQuery(entityClass());
    Root<Agent> root = query.from(entityClass());
    query
        .select(root)
        .where(
            builder.equal(root.get(Agent_.namespace), namespace),
            builder.equal(root.get(Agent_.name), name),
            builder.equal(root.get(Agent_.agentVersion), agentVersion));
    return entityManager().createQuery(query).setMaxResults(1).getResultList().stream().findFirst();
  }
}
