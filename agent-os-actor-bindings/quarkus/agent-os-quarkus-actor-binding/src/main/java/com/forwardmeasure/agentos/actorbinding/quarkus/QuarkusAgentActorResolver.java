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
package com.forwardmeasure.agentos.actorbinding.quarkus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

// Resolves AgentActor as one atomic call - see AgentActorResolver's own doc comment for why
// this is never split across a filter pair on Quarkus specifically (a real, verified concurrency
// risk with forwardmeasure-jpa's ThreadLocal-backed TenantScope under RESTEasy Reactive).
//
// Claims are read from the RAW JWT payload (Base64url-decoded compact-representation segment,
// reparsed with plain Jackson), not via JsonWebToken.getClaim(name) directly - verified
// empirically that smallrye-jwt wraps nested claim values in jakarta.json types
// (JsonObjectImpl/JsonStringImpl), which fail KeycloakOrganizationClaims' plain
// java.util.Map/java.lang.String instanceof checks even though the outer JsonObjectImpl does
// satisfy instanceof Map (jakarta.json.JsonObject extends Map<String,JsonValue> - only the
// leaf values are the problem). Matches openworkflow's own real, working Quarkus binding
// (QuarkusActiveOrganizationProvider) exactly, not invented fresh.
@RequestScoped
public class QuarkusAgentActorResolver implements AgentActorResolver {

  private static final String IDENTITY_PROVIDER = "keycloak";

  private final JsonWebToken token;
  private final ObjectMapper json;
  private final ActorService actors;
  private final TenantScope tenants;
  private final String clientId;

  @Inject
  public QuarkusAgentActorResolver(
      JsonWebToken token,
      ObjectMapper json,
      ActorService actors,
      TenantScope tenants,
      @ConfigProperty(name = "agent-os.security.client-id") String clientId) {
    this.token = Objects.requireNonNull(token, "token");
    this.json = Objects.requireNonNull(json, "json");
    this.actors = Objects.requireNonNull(actors, "actors");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.clientId = Objects.requireNonNull(clientId, "clientId");
  }

  @Override
  public <T> T withActor(Function<AgentActor, T> work) {
    Objects.requireNonNull(work, "work");
    ActiveOrganization organization = KeycloakOrganizationClaims.extract(rawClaims(), clientId);
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

  private Map<String, Object> rawClaims() {
    String raw = token.getRawToken();
    if (raw == null) {
      throw new SecurityException("No authenticated JWT principal for the current request");
    }
    String[] segments = raw.split("\\.");
    if (segments.length != 3) {
      throw new SecurityException("Verified JWT has an invalid compact representation");
    }
    try {
      byte[] payload = Base64.getUrlDecoder().decode(segments[1]);
      return json.readValue(payload, new TypeReference<Map<String, Object>>() {});
    } catch (IllegalArgumentException | IOException exception) {
      throw new SecurityException("Verified JWT claims could not be decoded", exception);
    }
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
