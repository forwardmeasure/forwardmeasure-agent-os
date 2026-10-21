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
import com.forwardmeasure.authzen.ActiveOrganization;
import com.forwardmeasure.authzen.AuthenticationRequiredException;
import com.forwardmeasure.authzen.AuthorizationDeniedException;
import com.forwardmeasure.authzen.KeycloakOrganizationClaims;
import com.forwardmeasure.jpa.identity.entity.Actor;
import com.forwardmeasure.jpa.identity.service.ActorService;
import com.forwardmeasure.jpa.tenancy.TenantScope;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.UUID;
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
  private static final Logger LOG = System.getLogger(SpringAgentActorResolver.class.getName());

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
      throw new AuthenticationRequiredException(
          "No authenticated JWT principal for the current request");
    }
    ActiveOrganization organization =
        KeycloakOrganizationClaims.extract(jwtAuthentication.getToken().getClaims(), clientId);
    return tenants.call(
        organization.tenantId(),
        () -> {
          Actor actor =
              actors
                  .findEnabledByIdentity(IDENTITY_PROVIDER, organization.actorId())
                  .orElseThrow(() -> noTenantAccess(organization));
          return work.apply(toAgentActor(actor, organization));
        });
  }

  // Unknown or disabled actor: the caller is authenticated (a verified token for a member of the
  // tenant's Organization) but has no tenant access here - 403, never 401 and never a created
  // actor. Actors are provisioned when tenant access is granted and disabled when it is removed.
  // The response carries only a correlation id; the reason is logged under it.
  private static AuthorizationDeniedException noTenantAccess(ActiveOrganization organization) {
    String correlationId = UUID.randomUUID().toString();
    LOG.log(
        Level.WARNING,
        "No enabled actor for subject {0} in tenant {1}; correlationId={2}",
        organization.actorId(),
        organization.tenantId().value(),
        correlationId);
    return new AuthorizationDeniedException(correlationId);
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
