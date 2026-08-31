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
import com.forwardmeasure.agentos.domain.AgentDefinition;
import com.forwardmeasure.agentos.governance.api.AgentGovernanceApi;
import com.forwardmeasure.agentos.governance.api.model.AgentAuditEventPage;
import com.forwardmeasure.agentos.governance.api.model.AgentLifecycleAction;
import com.forwardmeasure.agentos.governance.api.model.AgentPublishRequest;
import com.forwardmeasure.agentos.governance.application.AgentGovernanceService;
import jakarta.ws.rs.core.Response;
import java.util.Objects;
import java.util.UUID;

public class AgentGovernanceResource implements AgentGovernanceApi {

  private final AgentGovernanceService governance;
  private final AgentActorResolver agentActors;

  public AgentGovernanceResource(
      AgentGovernanceService governance, AgentActorResolver agentActors) {
    this.governance = Objects.requireNonNull(governance, "governance");
    this.agentActors = Objects.requireNonNull(agentActors, "agentActors");
  }

  @Override
  public Response approveAgent(
      String ifMatch, UUID agentId, AgentLifecycleAction agentLifecycleAction) {
    return agentActors.withActor(
        actor -> {
          AgentDefinition agent =
              governance.approve(
                  actor,
                  agentId,
                  RevisionHeaders.parseIfMatch(ifMatch),
                  reasonOf(agentLifecycleAction));
          return ok(agent);
        });
  }

  @Override
  public Response archiveAgent(
      String ifMatch, UUID agentId, AgentLifecycleAction agentLifecycleAction) {
    return agentActors.withActor(
        actor -> {
          AgentDefinition agent =
              governance.archive(
                  actor,
                  agentId,
                  RevisionHeaders.parseIfMatch(ifMatch),
                  reasonOf(agentLifecycleAction));
          return ok(agent);
        });
  }

  @Override
  public Response deprecateAgent(
      String ifMatch, UUID agentId, AgentLifecycleAction agentLifecycleAction) {
    return agentActors.withActor(
        actor -> {
          AgentDefinition agent =
              governance.deprecate(
                  actor,
                  agentId,
                  RevisionHeaders.parseIfMatch(ifMatch),
                  reasonOf(agentLifecycleAction));
          return ok(agent);
        });
  }

  @Override
  public Response listAgentAuditEvents(UUID agentId, Integer offset, Integer limit) {
    return agentActors.withActor(
        actor -> {
          var page = governance.listAuditEvents(actor, agentId, offset, limit);
          var wirePage =
              new AgentAuditEventPage(
                  page.items(),
                  AgentWireMapper.toPaging(
                      page.offset(), page.limit(), page.isLastPage(), page.totalCount()));
          return Response.ok(wirePage).build();
        });
  }

  @Override
  public Response publishAgent(
      String ifMatch,
      String xCorrelationID,
      UUID agentId,
      AgentPublishRequest agentPublishRequest) {
    return agentActors.withActor(
        actor -> {
          AgentDefinition agent =
              governance.publish(
                  actor,
                  agentId,
                  RevisionHeaders.parseIfMatch(ifMatch),
                  agentPublishRequest.getWorkflowId(),
                  agentPublishRequest.getRevisionId(),
                  xCorrelationID);
          return ok(agent);
        });
  }

  @Override
  public Response returnAgentToDraft(
      String ifMatch, UUID agentId, AgentLifecycleAction agentLifecycleAction) {
    return agentActors.withActor(
        actor -> {
          AgentDefinition agent =
              governance.returnToDraft(
                  actor,
                  agentId,
                  RevisionHeaders.parseIfMatch(ifMatch),
                  reasonOf(agentLifecycleAction));
          return ok(agent);
        });
  }

  @Override
  public Response submitAgentForReview(
      String ifMatch, UUID agentId, AgentLifecycleAction agentLifecycleAction) {
    return agentActors.withActor(
        actor -> {
          AgentDefinition agent =
              governance.submitForReview(
                  actor,
                  agentId,
                  RevisionHeaders.parseIfMatch(ifMatch),
                  reasonOf(agentLifecycleAction));
          return ok(agent);
        });
  }

  private static String reasonOf(AgentLifecycleAction action) {
    return action == null ? null : action.getReason();
  }

  private static Response ok(AgentDefinition agent) {
    return Response.ok(AgentWireMapper.toWire(agent))
        .tag(RevisionHeaders.etag(agent.revision()))
        .build();
  }
}
