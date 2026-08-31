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
package com.forwardmeasure.agentos.actorbinding.spring;

import com.forwardmeasure.agentos.domain.ActorReference;
import com.forwardmeasure.agentos.domain.ActorType;
import com.forwardmeasure.agentos.domain.AgentActor;
import com.forwardmeasure.agentos.domain.AgentActorResolver;
import com.forwardmeasure.jpa.identity.entity.Actor;
import com.forwardmeasure.jpa.identity.service.ActorService;
import com.forwardmeasure.jpa.tenancy.TenantSchema;
import com.forwardmeasure.jpa.tenancy.TenantScope;
import com.forwardmeasure.openworkflow.authorization.ActiveOrganization;
import com.forwardmeasure.openworkflow.authorization.KeycloakOrganizationClaims;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

// Resolves AgentActor as one atomic call - claims extraction, tenant-scope open, actor lookup,
// and the caller's work all happen inside withActor, never split across a filter pair (see
// AgentActorResolver's own doc comment for why: the one verified-safe real pattern in this
// ecosystem does it this way, and every framework binding matches it for a consistent contract
// regardless of which framework is hosting).
@Component
public class SpringAgentActorResolver implements AgentActorResolver {

  // The sole identity provider throughout this ecosystem - every JWT is Keycloak-issued, per
  // common-definitions.yaml's own BearerAuth description.
  private static final String IDENTITY_PROVIDER = "keycloak";

  private final ActorService actors;
  private final TenantScope tenants;
  private final String clientId;

  public SpringAgentActorResolver(
      ActorService actors,
      TenantScope tenants,
      @Value("${agent-os.security.client-id}") String clientId) {
    this.actors = Objects.requireNonNull(actors, "actors");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.clientId = Objects.requireNonNull(clientId, "clientId");
  }

  @Override
  public <T> T withActor(Function<AgentActor, T> work) {
    Objects.requireNonNull(work, "work");
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
      throw new SecurityException("No authenticated JWT principal for the current request");
    }
    ActiveOrganization organization =
        KeycloakOrganizationClaims.extract(jwtAuthentication.getToken().getClaims(), clientId);
    TenantSchema schema = TenantSchema.forTenant(organization.tenantId());
    return tenants.call(
        schema,
        () -> {
          Actor actor =
              actors
                  .findByIdentity(IDENTITY_PROVIDER, organization.actorId())
                  .orElseThrow(
                      () ->
                          new SecurityException(
                              "No Actor is provisioned for subject " + organization.actorId()));
          return work.apply(toAgentActor(actor, organization));
        });
  }

  private static AgentActor toAgentActor(Actor actor, ActiveOrganization organization) {
    ActorReference reference =
        new ActorReference(
            actor.getUuid(),
            actor.getSubjectIdentifier(),
            ActorType.valueOf(actor.getType().name()),
            actor.getEmail());
    return new AgentActor(reference, organization.tenantId().value());
  }
}
