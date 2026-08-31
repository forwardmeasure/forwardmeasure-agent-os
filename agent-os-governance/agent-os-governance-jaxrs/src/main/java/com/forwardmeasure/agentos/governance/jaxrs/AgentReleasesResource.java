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
import com.forwardmeasure.agentos.governance.api.AgentReleasesApi;
import com.forwardmeasure.agentos.governance.api.model.AgentReleasePage;
import com.forwardmeasure.agentos.governance.application.AgentGovernanceService;
import jakarta.ws.rs.core.Response;
import java.util.Objects;

// Discovery-facing, read-only: no ETag/If-Match here, matching the real spec (neither operation
// declares an If-Match header or a 412 response) - execution reads this same governance data
// directly, per AgentReleasesApi's own description.
public class AgentReleasesResource implements AgentReleasesApi {

  private final AgentGovernanceService governance;
  private final AgentActorResolver agentActors;

  public AgentReleasesResource(AgentGovernanceService governance, AgentActorResolver agentActors) {
    this.governance = Objects.requireNonNull(governance, "governance");
    this.agentActors = Objects.requireNonNull(agentActors, "agentActors");
  }

  @Override
  public Response getAgentRelease(String namespace, String name, String version) {
    return agentActors.withActor(
        actor -> {
          var release =
              governance.getRelease(actor, new AgentCoordinates(namespace, name, version));
          return Response.ok(AgentWireMapper.toReleaseWire(release)).build();
        });
  }

  @Override
  public Response listAgentReleases(Integer offset, Integer limit) {
    return agentActors.withActor(
        actor -> {
          var page = governance.listReleases(actor, offset, limit);
          var wirePage =
              new AgentReleasePage(
                  page.items().stream().map(AgentWireMapper::toReleaseWire).toList(),
                  AgentWireMapper.toPaging(
                      page.offset(), page.limit(), page.isLastPage(), page.totalCount()));
          return Response.ok(wirePage).build();
        });
  }
}
