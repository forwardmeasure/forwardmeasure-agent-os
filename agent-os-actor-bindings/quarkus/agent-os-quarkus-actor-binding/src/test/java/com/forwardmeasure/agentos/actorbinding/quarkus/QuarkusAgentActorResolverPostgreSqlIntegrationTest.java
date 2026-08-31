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
import com.forwardmeasure.jpa.identity.service.impl.ActorServiceImpl;
import com.forwardmeasure.jpa.liquibase.TenantSchemaMigrator;
import com.forwardmeasure.jpa.tenancy.TenantId;
import com.forwardmeasure.jpa.tenancy.TenantSchema;
import com.forwardmeasure.jpa.tenancy.ThreadBoundTenantScope;
import com.forwardmeasure.testcontainers.junit.postgresql.WithPostgreSqlContainer;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

@WithPostgreSqlContainer(databaseName = "agent_os_quarkus_actor_binding_contract")
class QuarkusAgentActorResolverPostgreSqlIntegrationTest {

  private static final String CLIENT_ID = "agent-os";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void resolvesTheAgentActorForAProvisionedSubject(PostgreSqlTestContainer database) {
    TenantId tenantId = new TenantId(UUID.randomUUID());
    TenantSchema tenant = prepare(database, tenantId);
    try (EntityManagerFactory entityManagers = entityManagers(database, tenant)) {
      String subject = "keycloak-subject-" + UUID.randomUUID();
      provisionActor(entityManagers, subject);

      QuarkusAgentActorResolver resolver =
          resolver(entityManagers, new ThreadBoundTenantScope(), rawToken(tenantId, subject));

      AgentActor resolved = resolver.withActor(actor -> actor);

      assertEquals(subject, resolved.actor().subject());
      assertEquals(tenantId.value(), resolved.tenantId());
    }
  }

  @Test
  void failsClosedWhenNoActorIsProvisionedForTheSubject(PostgreSqlTestContainer database) {
    TenantId tenantId = new TenantId(UUID.randomUUID());
    TenantSchema tenant = prepare(database, tenantId);
    try (EntityManagerFactory entityManagers = entityManagers(database, tenant)) {
      QuarkusAgentActorResolver resolver =
          resolver(
              entityManagers, new ThreadBoundTenantScope(), rawToken(tenantId, "unknown-subject"));

      assertThrows(SecurityException.class, () -> resolver.withActor(actor -> actor));
    }
  }

  @Test
  void failsClosedWhenNoJwtIsAuthenticated(PostgreSqlTestContainer database) {
    TenantId tenantId = new TenantId(UUID.randomUUID());
    TenantSchema tenant = prepare(database, tenantId);
    try (EntityManagerFactory entityManagers = entityManagers(database, tenant)) {
      // A null raw token is exactly what JsonWebToken.getRawToken() returns for an
      // unauthenticated request in a real Quarkus app.
      QuarkusAgentActorResolver resolver =
          resolver(entityManagers, new ThreadBoundTenantScope(), null);

      assertThrows(SecurityException.class, () -> resolver.withActor(actor -> actor));
    }
  }

  @Test
  void closesTheTenantScopeEvenWhenTheCallerThrows(PostgreSqlTestContainer database) {
    TenantId tenantId = new TenantId(UUID.randomUUID());
    TenantSchema tenant = prepare(database, tenantId);
    try (EntityManagerFactory entityManagers = entityManagers(database, tenant)) {
      String subject = "keycloak-subject-" + UUID.randomUUID();
      provisionActor(entityManagers, subject);
      ThreadBoundTenantScope tenants = new ThreadBoundTenantScope();
      QuarkusAgentActorResolver resolver =
          resolver(entityManagers, tenants, rawToken(tenantId, subject));

      assertTrue(tenants.current().isEmpty());
      Function<AgentActor, Void> throwing =
          actor -> {
            throw new IllegalStateException("caller failure");
          };
      assertThrows(IllegalStateException.class, () -> resolver.withActor(throwing));
      assertTrue(tenants.current().isEmpty(), "the tenant scope must be closed after the throw");
    }
  }

  private QuarkusAgentActorResolver resolver(
      EntityManagerFactory entityManagers, ThreadBoundTenantScope tenants, String rawToken) {
    ActorRepository actorRepository = new ActorRepository();
    EntityManager entityManager = entityManagers.createEntityManager();
    actorRepository.bindPersistenceContext(entityManager);
    return new QuarkusAgentActorResolver(
        fakeToken(rawToken), JSON, new ActorServiceImpl(actorRepository), tenants, CLIENT_ID);
  }

  private void provisionActor(EntityManagerFactory entityManagers, String subject) {
    EntityManager entityManager = entityManagers.createEntityManager();
    var transaction = entityManager.getTransaction();
    try {
      transaction.begin();
      ActorRepository actors = new ActorRepository();
      actors.bindPersistenceContext(entityManager);
      actors.persist(
          Actor.builder()
              .subjectIdentifier(subject)
              .identityProvider("keycloak")
              .type(IdentityType.HUMAN)
              .build());
      transaction.commit();
    } finally {
      entityManager.close();
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
      Map<String, Object> claims =
          Map.of(
              "sub",
              subject,
              "organization",
              Map.of(
                  "acme",
                  Map.of(
                      "id", "org-" + UUID.randomUUID(),
                      "forwardmeasure.tenant-id", tenantId.value().toString(),
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

  private TenantSchema prepare(PostgreSqlTestContainer database, TenantId tenantId) {
    TenantSchema tenant = TenantSchema.forTenant(tenantId);
    database.createSchema(tenant.value());
    TenantSchemaMigrator migrator =
        new TenantSchemaMigrator(
            database.dataSource(),
            "db/changelog/agent-os-quarkus-actor-binding-test.xml",
            getClass().getClassLoader());
    assertTrue(migrator.validate(tenant).valid());
    migrator.migrate(tenant);
    assertTrue(migrator.status(tenant).current());
    return tenant;
  }

  private EntityManagerFactory entityManagers(
      PostgreSqlTestContainer database, TenantSchema tenant) {
    return Persistence.createEntityManagerFactory(
        "agent-os-quarkus-actor-binding-test",
        Map.of(
            "jakarta.persistence.jdbc.url", database.hostJdbcUrl(),
            "jakarta.persistence.jdbc.user", database.username(),
            "jakarta.persistence.jdbc.password", database.password(),
            "jakarta.persistence.jdbc.driver", "org.postgresql.Driver",
            "hibernate.default_schema", tenant.value()));
  }
}
