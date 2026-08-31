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

import com.forwardmeasure.agentos.domain.AgentActor;
import com.forwardmeasure.agentos.domain.AgentCoordinates;
import com.forwardmeasure.agentos.domain.AgentDefinition;
import com.forwardmeasure.agentos.domain.AgentStatus;
import com.forwardmeasure.agentos.governance.api.model.AgentAuditEvent;
import com.forwardmeasure.agentos.governance.api.model.AgentDefinitionContent;
import java.util.UUID;

// The governance vertical's application-service port: every operation
// agent-governance-management.openapi.yaml exposes, expressed in domain types plus the one
// generated wire type (AgentDefinitionContent) agent-os-domain itself already reuses directly -
// see AgentDefinition's own doc comment for why that specific type is not hand-rolled. Audit
// events are likewise the generated AgentAuditEvent/AgentAuditOperation directly: both are flat
// data records with no independent domain behaviour of their own (unlike AgentStatus, which owns
// the real transition table), so wrapping them in a second, parallel domain type would be exactly
// the duplication the "don't hand-roll API contract models" rule exists to prevent.
//
// agent-os-governance-jpa depends on this module (not the reverse, see its own pom) and provides
// the sole implementation, AgentGovernanceServiceImpl - this interface is the seam
// agent-os-governance-jaxrs is written against, with the concrete implementation wired in only at
// the agent-os-governance-{fw} layer, one instance per framework, each wrapped in that
// framework's own transactional boundary.
//
// Every method's first parameter is the caller resolved by AgentActorResolver.withActor - never
// re-derived here - matching the "explicit call, same shape everywhere" rule already established
// for actor resolution itself. expectedRevision on every mutating call is the numeric value of the
// request's If-Match header; a mismatch against the entity's current revision throws
// StaleRevisionException (mapped to 412 by agent-os-governance-jaxrs). reason is the free-text
// AgentLifecycleAction.reason field, unwrapped to a plain nullable String at this boundary rather
// than passed as the wire envelope - that single-field wrapper carries no shape of its own worth
// preserving, unlike AgentDefinitionContent.
public interface AgentGovernanceService {

  AgentDefinition createDraft(
      AgentActor caller, AgentCoordinates coordinates, AgentDefinitionContent content);

  AgentDefinition getAgent(AgentActor caller, UUID agentId);

  Page<AgentDefinition> listAgents(
      AgentActor caller, int offset, int limit, AgentStatus statusFilter);

  AgentDefinition updateDraft(
      AgentActor caller, UUID agentId, int expectedRevision, AgentDefinitionContent content);

  void deleteDraft(AgentActor caller, UUID agentId, int expectedRevision);

  AgentDefinition submitForReview(
      AgentActor caller, UUID agentId, int expectedRevision, String reason);

  AgentDefinition returnToDraft(
      AgentActor caller, UUID agentId, int expectedRevision, String reason);

  AgentDefinition approve(AgentActor caller, UUID agentId, int expectedRevision, String reason);

  AgentDefinition publish(
      AgentActor caller,
      UUID agentId,
      int expectedRevision,
      UUID workflowId,
      UUID workflowRevisionId,
      String correlationId);

  AgentDefinition deprecate(AgentActor caller, UUID agentId, int expectedRevision, String reason);

  AgentDefinition archive(AgentActor caller, UUID agentId, int expectedRevision, String reason);

  Page<AgentAuditEvent> listAuditEvents(AgentActor caller, UUID agentId, int offset, int limit);

  // Discovery-facing: published releases only, across the whole tenant - not scoped to the
  // caller's own agents. See AgentReleasesApi's real description: "the same governance data
  // execution reads directly ... there is no separate read-model or replication delay."
  Page<AgentDefinition> listReleases(AgentActor caller, int offset, int limit);

  AgentDefinition getRelease(AgentActor caller, AgentCoordinates coordinates);
}
