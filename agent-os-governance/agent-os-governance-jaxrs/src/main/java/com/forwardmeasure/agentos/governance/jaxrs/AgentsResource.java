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

import com.forwardmeasure.agentos.domain.AgentActorResolver;
import com.forwardmeasure.agentos.domain.AgentCoordinates;
import com.forwardmeasure.agentos.domain.AgentDefinition;
import com.forwardmeasure.agentos.governance.api.AgentsApi;
import com.forwardmeasure.agentos.governance.api.model.AgentDraftCreate;
import com.forwardmeasure.agentos.governance.api.model.AgentDraftUpdate;
import com.forwardmeasure.agentos.governance.api.model.AgentPage;
import com.forwardmeasure.agentos.governance.application.AgentGovernanceService;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;

// Hosted identically by every agent-os-governance-{fw} module - see this class's own module's
// pom description. AgentActorResolver.withActor wraps every method body as one atomic call
// (resolve caller, open tenant scope, run the operation, close tenant scope), never split across
// a filter pair - see AgentActorResolver's own doc comment for why.
public class AgentsResource implements AgentsApi {

  private final AgentGovernanceService governance;
  private final AgentActorResolver agentActors;

  public AgentsResource(AgentGovernanceService governance, AgentActorResolver agentActors) {
    this.governance = Objects.requireNonNull(governance, "governance");
    this.agentActors = Objects.requireNonNull(agentActors, "agentActors");
  }

  @Override
  public Response createAgentDraft(AgentDraftCreate agentDraftCreate) {
    return agentActors.withActor(
        actor -> {
          AgentCoordinates coordinates =
              new AgentCoordinates(
                  agentDraftCreate.getNamespace(),
                  agentDraftCreate.getName(),
                  agentDraftCreate.getVersion());
          AgentDefinition created =
              governance.createDraft(
                  actor, coordinates, AgentWireMapper.contentOf(agentDraftCreate));
          return Response.created(URI.create("/v1/agents/" + created.id()))
              .tag(RevisionHeaders.etag(created.revision()))
              .entity(AgentWireMapper.toWire(created))
              .build();
        });
  }

  @Override
  public Response deleteAgentDraft(String ifMatch, UUID agentId) {
    return agentActors.withActor(
        actor -> {
          governance.deleteDraft(actor, agentId, RevisionHeaders.parseIfMatch(ifMatch));
          return Response.noContent().build();
        });
  }

  @Override
  public Response getAgent(UUID agentId) {
    return agentActors.withActor(
        actor -> {
          AgentDefinition agent = governance.getAgent(actor, agentId);
          return Response.ok(AgentWireMapper.toWire(agent))
              .tag(RevisionHeaders.etag(agent.revision()))
              .build();
        });
  }

  @Override
  public Response listAgents(
      Integer offset,
      Integer limit,
      com.forwardmeasure.agentos.governance.api.model.AgentStatus status) {
    return agentActors.withActor(
        actor -> {
          var domainStatus =
              status == null
                  ? null
                  : com.forwardmeasure.agentos.domain.AgentStatus.fromWireValue(status.toString());
          var page = governance.listAgents(actor, offset, limit, domainStatus);
          var wirePage =
              new AgentPage(
                  page.items().stream().map(AgentWireMapper::toWire).toList(),
                  AgentWireMapper.toPaging(
                      page.offset(), page.limit(), page.isLastPage(), page.totalCount()));
          return Response.ok(wirePage).build();
        });
  }

  @Override
  public Response updateAgentDraft(
      String ifMatch, UUID agentId, AgentDraftUpdate agentDraftUpdate) {
    return agentActors.withActor(
        actor -> {
          AgentDefinition updated =
              governance.updateDraft(
                  actor,
                  agentId,
                  RevisionHeaders.parseIfMatch(ifMatch),
                  AgentWireMapper.contentOf(agentDraftUpdate));
          return Response.ok(AgentWireMapper.toWire(updated))
              .tag(RevisionHeaders.etag(updated.revision()))
              .build();
        });
  }
}
