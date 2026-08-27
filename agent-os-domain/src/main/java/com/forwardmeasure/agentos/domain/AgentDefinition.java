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

import com.forwardmeasure.agentos.governance.api.model.AgentDefinitionContent;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

// The governed agent aggregate: coordinates, lifecycle status, provenance (owner/reviewer), its
// content, and - once published - the exact OpenWorkflow revision it's bound to. Verified against
// agent-governance-management.openapi.yaml's Agent schema and its real path operations, not
// reconstructed from prose. Immutable: every lifecycle operation below returns a new instance
// with revision incremented, matching the ETag/If-Match optimistic-concurrency headers the real
// API contract uses on every mutating request.
//
// content is typed as the real OpenAPI-generated AgentDefinitionContent
// (agent-os-governance-management-models, WP1) - not a hand-rolled parallel type mirroring its
// wire shape, and not an untyped JsonNode escape hatch either. That shape has exactly one
// canonical generated source (it only appears in agent-governance-management.openapi.yaml); the
// aggregate reuses it directly. Structural validity is enforced at the boundary by
// agent-os-schema-validation against WP1's generated JSON Schema, not re-implemented here.
public record AgentDefinition(
    UUID id,
    int revision,
    AgentCoordinates coordinates,
    AgentStatus status,
    AgentDefinitionContent content,
    ActorReference owner,
    ActorReference reviewer,
    WorkflowReleaseBinding workflowBinding,
    String agentDefinitionSha256,
    Instant createdAt,
    Instant updatedAt,
    Instant publishedAt,
    Instant deprecatedAt,
    Instant archivedAt) {

  public AgentDefinition {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(coordinates, "coordinates");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public static AgentDefinition draft(
      AgentCoordinates coordinates,
      ActorReference owner,
      AgentDefinitionContent content,
      Instant now) {
    return new AgentDefinition(
        UUID.randomUUID(),
        0,
        coordinates,
        AgentStatus.DRAFT,
        content,
        owner,
        null,
        null,
        null,
        now,
        now,
        null,
        null,
        null);
  }

  // Content is mutable only while in draft status, per the real updateAgentDraft operation.
  public AgentDefinition updateContent(AgentDefinitionContent newContent, Instant now) {
    if (status != AgentStatus.DRAFT) {
      throw new InvalidLifecycleTransitionException(status, AgentStatus.DRAFT);
    }
    return new AgentDefinition(
        id,
        revision + 1,
        coordinates,
        status,
        newContent,
        owner,
        reviewer,
        workflowBinding,
        agentDefinitionSha256,
        createdAt,
        now,
        publishedAt,
        deprecatedAt,
        archivedAt);
  }

  public AgentDefinition submitForReview(Instant now) {
    status.requireTransitionTo(AgentStatus.IN_REVIEW);
    return new AgentDefinition(
        id,
        revision + 1,
        coordinates,
        AgentStatus.IN_REVIEW,
        content,
        owner,
        reviewer,
        workflowBinding,
        agentDefinitionSha256,
        createdAt,
        now,
        publishedAt,
        deprecatedAt,
        archivedAt);
  }

  public AgentDefinition returnToDraft(Instant now) {
    status.requireTransitionTo(AgentStatus.DRAFT);
    return new AgentDefinition(
        id,
        revision + 1,
        coordinates,
        AgentStatus.DRAFT,
        content,
        owner,
        reviewer,
        workflowBinding,
        agentDefinitionSha256,
        createdAt,
        now,
        publishedAt,
        deprecatedAt,
        archivedAt);
  }

  // The authenticated reviewer must differ from the agent's owner, per the real approveAgent
  // operation.
  public AgentDefinition approve(ActorReference reviewerActor, Instant now) {
    status.requireTransitionTo(AgentStatus.APPROVED);
    Objects.requireNonNull(reviewerActor, "reviewerActor");
    if (reviewerActor.equals(owner)) {
      throw new IllegalArgumentException("the reviewer must differ from the agent's owner");
    }
    return new AgentDefinition(
        id,
        revision + 1,
        coordinates,
        AgentStatus.APPROVED,
        content,
        owner,
        reviewerActor,
        workflowBinding,
        agentDefinitionSha256,
        createdAt,
        now,
        publishedAt,
        deprecatedAt,
        archivedAt);
  }

  public AgentDefinition publish(
      WorkflowReleaseBinding binding, String definitionSha256, Instant now) {
    status.requireTransitionTo(AgentStatus.PUBLISHED);
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(definitionSha256, "definitionSha256");
    return new AgentDefinition(
        id,
        revision + 1,
        coordinates,
        AgentStatus.PUBLISHED,
        content,
        owner,
        reviewer,
        binding,
        definitionSha256,
        createdAt,
        now,
        now,
        deprecatedAt,
        archivedAt);
  }

  public AgentDefinition deprecate(Instant now) {
    status.requireTransitionTo(AgentStatus.DEPRECATED);
    return new AgentDefinition(
        id,
        revision + 1,
        coordinates,
        AgentStatus.DEPRECATED,
        content,
        owner,
        reviewer,
        workflowBinding,
        agentDefinitionSha256,
        createdAt,
        now,
        publishedAt,
        now,
        archivedAt);
  }

  public AgentDefinition archive(Instant now) {
    status.requireTransitionTo(AgentStatus.ARCHIVED);
    return new AgentDefinition(
        id,
        revision + 1,
        coordinates,
        AgentStatus.ARCHIVED,
        content,
        owner,
        reviewer,
        workflowBinding,
        agentDefinitionSha256,
        createdAt,
        now,
        publishedAt,
        deprecatedAt,
        now);
  }
}
