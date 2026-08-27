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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.forwardmeasure.agentos.governance.api.model.AgentDefinitionContent;
import com.forwardmeasure.agentos.governance.api.model.AgentProtocols;
import com.forwardmeasure.agentos.governance.api.model.AgentSkill;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentDefinitionTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private static AgentDefinitionContent content() {
    return new AgentDefinitionContent(
        "Test Agent",
        List.of(new AgentSkill("skill-1", "Skill One", "does a thing")),
        Map.of("type", "object"),
        Map.of("type", "object"),
        new AgentProtocols(true, false),
        URI.create("https://agents.example.com/test-agent"));
  }

  private static ActorReference actor(String subject) {
    return new ActorReference(UUID.randomUUID(), subject, ActorType.HUMAN, null);
  }

  private static AgentDefinition freshDraft() {
    return AgentDefinition.draft(
        new AgentCoordinates("acme", "test-agent", "1.0.0"), actor("owner"), content(), T0);
  }

  @Test
  void draftStartsAtRevisionZeroWithNoReviewerOrBinding() {
    AgentDefinition agent = freshDraft();
    assertEquals(0, agent.revision());
    assertEquals(AgentStatus.DRAFT, agent.status());
    assertNull(agent.reviewer());
    assertNull(agent.workflowBinding());
    assertEquals(T0, agent.createdAt());
    assertEquals(T0, agent.updatedAt());
  }

  @Test
  void everyLifecycleOperationIncrementsRevision() {
    ActorReference owner = actor("owner");
    ActorReference reviewer = actor("reviewer");
    AgentDefinition agent =
        AgentDefinition.draft(
            new AgentCoordinates("acme", "test-agent", "1.0.0"), owner, content(), T0);
    assertEquals(0, agent.revision());

    agent = agent.submitForReview(T0.plusSeconds(1));
    assertEquals(1, agent.revision());

    agent = agent.approve(reviewer, T0.plusSeconds(2));
    assertEquals(2, agent.revision());

    WorkflowReleaseBinding binding =
        new WorkflowReleaseBinding(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64));
    agent = agent.publish(binding, "b".repeat(64), T0.plusSeconds(3));
    assertEquals(3, agent.revision());
    assertEquals(AgentStatus.PUBLISHED, agent.status());
    assertEquals(binding, agent.workflowBinding());
    assertEquals(T0.plusSeconds(3), agent.publishedAt());

    agent = agent.deprecate(T0.plusSeconds(4));
    assertEquals(4, agent.revision());
    assertEquals(T0.plusSeconds(4), agent.deprecatedAt());

    agent = agent.archive(T0.plusSeconds(5));
    assertEquals(5, agent.revision());
    assertEquals(AgentStatus.ARCHIVED, agent.status());
    assertEquals(T0.plusSeconds(5), agent.archivedAt());
  }

  @Test
  void returnToDraftIsTheOnlyWayBackFromInReview() {
    AgentDefinition agent = freshDraft().submitForReview(T0);
    assertEquals(AgentStatus.IN_REVIEW, agent.status());

    agent = agent.returnToDraft(T0.plusSeconds(1));
    assertEquals(AgentStatus.DRAFT, agent.status());
  }

  @Test
  void reviewerMustDifferFromOwner() {
    ActorReference owner = actor("same-person");
    AgentDefinition agent =
        AgentDefinition.draft(new AgentCoordinates("acme", "a", "1.0.0"), owner, content(), T0)
            .submitForReview(T0);
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> agent.approve(owner, T0.plusSeconds(1)));
    assertNotNull(ex.getMessage());
  }

  @Test
  void contentIsMutableOnlyWhileInDraftStatus() {
    AgentDefinition agent = freshDraft().submitForReview(T0);
    assertThrows(
        InvalidLifecycleTransitionException.class,
        () -> agent.updateContent(content(), T0.plusSeconds(1)));
  }

  @Test
  void updateContentSucceedsWhileInDraftStatus() {
    AgentDefinition agent = freshDraft();
    AgentDefinition updated = agent.updateContent(content(), T0.plusSeconds(1));
    assertEquals(1, updated.revision());
    assertEquals(AgentStatus.DRAFT, updated.status());
  }

  @Test
  void publishRequiresApprovedStatus() {
    AgentDefinition agent = freshDraft();
    WorkflowReleaseBinding binding =
        new WorkflowReleaseBinding(UUID.randomUUID(), UUID.randomUUID(), "c".repeat(64));
    assertThrows(
        InvalidLifecycleTransitionException.class,
        () -> agent.publish(binding, "d".repeat(64), T0));
  }
}
