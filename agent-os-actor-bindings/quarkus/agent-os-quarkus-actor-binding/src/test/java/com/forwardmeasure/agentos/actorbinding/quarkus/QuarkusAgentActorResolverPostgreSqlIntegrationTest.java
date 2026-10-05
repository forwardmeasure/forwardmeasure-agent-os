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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.agentos.domain.AgentActor;
import com.forwardmeasure.jpa.identity.entity.Actor;
import com.forwardmeasure.jpa.identity.entity.IdentityType;
import com.forwardmeasure.jpa.identity.repository.ActorRepository;
import com.forwardmeasure.jpa.identity.service.ActorService;
import com.forwardmeasure.jpa.tenancy.TenantId;
import com.forwardmeasure.jpa.tenancy.TenantScope;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

// Real @QuarkusTest boot (not a hand-wired EntityManagerFactory) - proves QuarkusAgentActorResolver
// actually routes through real Hibernate multi-tenancy (QuarkusTenantConnectionResolver, injected
// by Quarkus's own build-time augmentation, reading TenantScope) the same way a real request would,
// not just that the resolver's own open/close bookkeeping works against a schema hand-pinned to the
// right place regardless of what TenantScope carries.
@QuarkusTest
@QuarkusTestResource(AgentOsQuarkusPostgreSqlResource.class)
class QuarkusAgentActorResolverPostgreSqlIntegrationTest {

  private static final String CLIENT_ID = "agent-os";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject TenantScope tenantScope;

  @Inject UserTransaction transaction;

  @Inject ActorRepository actorRepository;

  @Inject ActorService actorService;

  @Test
  void resolvesTheAgentActorForAProvisionedSubject() throws Exception {
    String subject = "keycloak-subject-" + UUID.randomUUID();
    provisionActor(subject);
    TenantId tenantId = AgentOsQuarkusPostgreSqlResource.TENANT_ID;

    QuarkusAgentActorResolver resolver = resolver(rawToken(tenantId, subject));

    AgentActor resolved = resolver.withActor(actor -> actor);

    assertEquals(subject, resolved.actor().subject());
    assertEquals(tenantId.value(), resolved.tenantId());
  }

  @Test
  void failsClosedWhenNoActorIsProvisionedForTheSubject() {
    QuarkusAgentActorResolver resolver =
        resolver(rawToken(AgentOsQuarkusPostgreSqlResource.TENANT_ID, "unknown-subject"));
    assertThrows(SecurityException.class, () -> resolver.withActor(actor -> actor));
  }

  @Test
  void failsClosedWhenNoJwtIsAuthenticated() {
    // A null raw token is exactly what JsonWebToken.getRawToken() returns for an unauthenticated
    // request in a real Quarkus app.
    QuarkusAgentActorResolver resolver = resolver(null);
    assertThrows(SecurityException.class, () -> resolver.withActor(actor -> actor));
  }

  @Test
  void closesTheTenantScopeEvenWhenTheCallerThrows() throws Exception {
    String subject = "keycloak-subject-" + UUID.randomUUID();
    provisionActor(subject);
    QuarkusAgentActorResolver resolver =
        resolver(rawToken(AgentOsQuarkusPostgreSqlResource.TENANT_ID, subject));

    assertTrue(tenantScope.current().isEmpty());
    Function<AgentActor, Void> throwing =
        actor -> {
          throw new IllegalStateException("caller failure");
        };
    assertThrows(IllegalStateException.class, () -> resolver.withActor(throwing));
    assertTrue(tenantScope.current().isEmpty(), "the tenant scope must be closed after the throw");
  }

  private QuarkusAgentActorResolver resolver(String rawToken) {
    return new QuarkusAgentActorResolver(
        fakeToken(rawToken), JSON, actorService, tenantScope, CLIENT_ID);
  }

  private void provisionActor(String subject) throws Exception {
    transaction.begin();
    try (var ignored = tenantScope.open(AgentOsQuarkusPostgreSqlResource.TENANT_ID)) {
      actorRepository.persist(
          Actor.builder()
              .subjectIdentifier(subject)
              .identityProvider("keycloak")
              .type(IdentityType.HUMAN)
              .build());
      transaction.commit();
    } catch (Exception | Error failure) {
      transaction.rollback();
      throw failure;
    }
  }

  // QuarkusAgentActorResolver only calls getRawToken() - it deliberately never touches
  // getClaim(), so a minimal double is enough and avoids needing a real smallrye-jwt-parsed
  // token for this test.
  private static JsonWebToken fakeToken(String rawToken) {
    return new JsonWebToken() {
      @Override
      public String getName() {
        return null;
      }

      @Override
      public Set<String> getClaimNames() {
        return Set.of();
      }

      @Override
      public <T> T getClaim(String claimName) {
        return null;
      }

      @Override
      public String getRawToken() {
        return rawToken;
      }
    };
  }

  private String rawToken(TenantId tenantId, String subject) {
    try {
      String header = segment(Map.of("alg", "none"));
      // The organization claim's map key IS the alias TenantDatabase.forAlias(...) derives from -
      // must match AgentOsQuarkusPostgreSqlResource.TENANT_DATABASE's own alias exactly, since
      // this test proves real routing, not a schema hand-pinned regardless of what the JWT says.
      // tenantId itself is independent of the alias (TenantDatabase is no longer a pure function
      // of TenantId - see that class's own javadoc), so any value is fine here.
      Map<String, Object> claims =
          Map.of(
              "sub",
              subject,
              "organization",
              Map.of(
                  AgentOsQuarkusPostgreSqlResource.TENANT_DATABASE.alias(),
                  Map.of(
                      "id", "org-" + UUID.randomUUID(),
                      "forwardmeasure.tenant-did",
                          AgentOsQuarkusPostgreSqlResource.TENANT_DID.value(),
                      "resource_access",
                          Map.of(CLIENT_ID, Map.of("roles", java.util.List.of("member"))))));
      String payload = segment(claims);
      // A real Keycloak-issued token always has a non-empty signature segment; an empty one
      // (e.g. bare "header.payload.") would trip java.lang.String#split's default behavior of
      // dropping trailing empty strings, which is a test-construction artifact, not something
      // the resolver needs to handle - Keycloak never issues alg=none tokens.
      return header + "." + payload + ".sig";
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static String segment(Object value) throws Exception {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(value));
  }
}
