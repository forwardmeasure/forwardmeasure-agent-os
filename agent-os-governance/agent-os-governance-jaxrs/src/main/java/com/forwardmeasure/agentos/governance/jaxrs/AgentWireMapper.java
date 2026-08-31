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
package com.forwardmeasure.agentos.governance.jaxrs;

import com.forwardmeasure.agentos.domain.ActorType;
import com.forwardmeasure.agentos.domain.AgentDefinition;
import com.forwardmeasure.agentos.governance.api.model.AgentDefinitionContent;
import com.forwardmeasure.agentos.governance.api.model.AgentDraftCreate;
import com.forwardmeasure.agentos.governance.api.model.AgentDraftUpdate;
import com.forwardmeasure.agentos.governance.api.model.Paging;
import java.util.Date;

// Hand-written, not MapStruct: content's seven fields are flattened onto Agent/AgentDraftCreate/
// AgentDraftUpdate/AgentRelease by the OpenAPI spec's own allOf composition (see
// AgentDefinitionContent's own doc comment), and every generated model already exposes a fluent
// builder-style setter for each field - chaining those directly is more direct than fighting
// MapStruct's nested-source-path syntax across a record/POJO boundary for a mapping this shaped.
// The rule this whole effort keeps returning to is "don't hand-roll a parallel type for a wire
// shape" - a stateless function that reads one already-correct type and writes another is not
// that; it's the translation this layer exists to do.
final class AgentWireMapper {

  private AgentWireMapper() {}

  static com.forwardmeasure.agentos.governance.api.model.Agent toWire(AgentDefinition domain) {
    AgentDefinitionContent content = domain.content();
    var wire =
        new com.forwardmeasure.agentos.governance.api.model.Agent(
            content.getDisplayName(),
            content.getSkills(),
            content.getInputSchema(),
            content.getOutputSchema(),
            content.getProtocols(),
            content.getPublicUri(),
            domain.id(),
            domain.revision(),
            domain.coordinates().namespace(),
            domain.coordinates().name(),
            domain.coordinates().version(),
            toWireStatus(domain.status()),
            toWireActor(domain.owner()),
            Date.from(domain.createdAt()),
            Date.from(domain.updatedAt()));
    wire.description(content.getDescription());
    wire.a2uiPresentation(content.getA2uiPresentation());
    if (domain.reviewer() != null) {
      wire.reviewer(toWireActor(domain.reviewer()));
    }
    if (domain.workflowBinding() != null) {
      wire.workflowBinding(toWireBinding(domain.workflowBinding()));
    }
    wire.agentDefinitionSha256(domain.agentDefinitionSha256());
    if (domain.publishedAt() != null) {
      wire.publishedAt(Date.from(domain.publishedAt()));
    }
    if (domain.deprecatedAt() != null) {
      wire.deprecatedAt(Date.from(domain.deprecatedAt()));
    }
    if (domain.archivedAt() != null) {
      wire.archivedAt(Date.from(domain.archivedAt()));
    }
    return wire;
  }

  // Only ever called for a PUBLISHED AgentDefinition (listAgentReleases/getAgentRelease's port
  // methods never return anything else), so workflowBinding/agentDefinitionSha256 - both required
  // here - are guaranteed non-null by AgentDefinition.publish's own invariants.
  static com.forwardmeasure.agentos.governance.api.model.AgentRelease toReleaseWire(
      AgentDefinition domain) {
    AgentDefinitionContent content = domain.content();
    var wire =
        new com.forwardmeasure.agentos.governance.api.model.AgentRelease(
            domain.coordinates().namespace(),
            domain.coordinates().name(),
            domain.coordinates().version(),
            content.getDisplayName(),
            content.getSkills(),
            content.getProtocols(),
            content.getPublicUri(),
            toWireBinding(domain.workflowBinding()),
            domain.agentDefinitionSha256(),
            Date.from(domain.updatedAt()));
    wire.description(content.getDescription());
    return wire;
  }

  static com.forwardmeasure.agentos.governance.api.model.ActorReference toWireActor(
      com.forwardmeasure.agentos.domain.ActorReference actor) {
    var type =
        actor.type() == ActorType.HUMAN
            ? com.forwardmeasure.agentos.governance.api.model.ActorReference.TypeEnum.HUMAN
            : com.forwardmeasure.agentos.governance.api.model.ActorReference.TypeEnum.SERVICE;
    return new com.forwardmeasure.agentos.governance.api.model.ActorReference(
            actor.id(), actor.subject(), type)
        .displayName(actor.displayName());
  }

  static com.forwardmeasure.agentos.governance.api.model.WorkflowReleaseBinding toWireBinding(
      com.forwardmeasure.agentos.domain.WorkflowReleaseBinding binding) {
    return new com.forwardmeasure.agentos.governance.api.model.WorkflowReleaseBinding(
        binding.workflowId(), binding.revisionId(), binding.revisionDigest());
  }

  static com.forwardmeasure.agentos.governance.api.model.AgentStatus toWireStatus(
      com.forwardmeasure.agentos.domain.AgentStatus status) {
    return com.forwardmeasure.agentos.governance.api.model.AgentStatus.fromValue(
        status.wireValue());
  }

  static Paging toPaging(int offset, int limit, boolean isLastPage, long totalCount) {
    var paging = new Paging(offset, limit, isLastPage);
    if (!isLastPage) {
      paging.nextPageStart(offset + limit);
    }
    paging.totalCount(totalCount);
    return paging;
  }

  static AgentDefinitionContent contentOf(AgentDraftCreate create) {
    var content =
        new AgentDefinitionContent(
            create.getDisplayName(),
            create.getSkills(),
            create.getInputSchema(),
            create.getOutputSchema(),
            create.getProtocols(),
            create.getPublicUri());
    content.description(create.getDescription());
    content.a2uiPresentation(create.getA2uiPresentation());
    return content;
  }

  static AgentDefinitionContent contentOf(AgentDraftUpdate update) {
    var content =
        new AgentDefinitionContent(
            update.getDisplayName(),
            update.getSkills(),
            update.getInputSchema(),
            update.getOutputSchema(),
            update.getProtocols(),
            update.getPublicUri());
    content.description(update.getDescription());
    content.a2uiPresentation(update.getA2uiPresentation());
    return content;
  }
}
