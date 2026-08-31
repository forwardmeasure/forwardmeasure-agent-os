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
package com.forwardmeasure.agentos.actorbinding.micronaut;

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
import io.micronaut.context.annotation.Value;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.utils.SecurityService;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.function.Function;

// Resolves AgentActor as one atomic call - see AgentActorResolver's own doc comment for why this
// is never split across a filter pair, even though Micronaut has no equivalent of Quarkus's real
// concurrency risk (Nimbus JWT's claims are plain java.util.Map/String - verified empirically,
// not assumed - so this could safely be a filter on its own; kept to the same explicit-call
// contract as every other framework binding regardless).
//
// Matches openworkflow's own real, working Micronaut binding (MicronautActiveOrganizationProvider)
// for how to reach the current request's Authentication - SecurityService.getAuthentication(),
// not direct Authentication constructor injection, so the "no authenticated JWT" case is an
// explicit check inside withActor like every other framework binding, not a DI-construction
// failure with a different exception shape.
@Singleton
public class MicronautAgentActorResolver implements AgentActorResolver {

  private static final String IDENTITY_PROVIDER = "keycloak";

  private final SecurityService security;
  private final ActorService actors;
  private final TenantScope tenants;
  private final String clientId;

  public MicronautAgentActorResolver(
      SecurityService security,
      ActorService actors,
      TenantScope tenants,
      @Value("${agent-os.security.client-id}") String clientId) {
    this.security = Objects.requireNonNull(security, "security");
    this.actors = Objects.requireNonNull(actors, "actors");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.clientId = Objects.requireNonNull(clientId, "clientId");
  }

  @Override
  public <T> T withActor(Function<AgentActor, T> work) {
    Objects.requireNonNull(work, "work");
    Authentication authentication =
        security
            .getAuthentication()
            .orElseThrow(
                () ->
                    new SecurityException(
                        "No authenticated JWT principal for the current request"));
    ActiveOrganization organization =
        KeycloakOrganizationClaims.extract(authentication.getAttributes(), clientId);
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
