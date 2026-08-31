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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@WithPostgreSqlContainer(databaseName = "agent_os_spring_actor_binding_contract")
class SpringAgentActorResolverPostgreSqlIntegrationTest {

  private static final String CLIENT_ID = "agent-os";

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void resolvesTheAgentActorForAProvisionedSubject(PostgreSqlTestContainer database) {
    TenantId tenantId = new TenantId(UUID.randomUUID());
    TenantSchema tenant = prepare(database, tenantId);
    try (EntityManagerFactory entityManagers = entityManagers(database, tenant)) {
      String subject = "keycloak-subject-" + UUID.randomUUID();
      provisionActor(entityManagers, subject);

      SpringAgentActorResolver resolver = resolver(entityManagers);
      SecurityContextHolder.getContext().setAuthentication(jwtAuthentication(tenantId, subject));

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
      SpringAgentActorResolver resolver = resolver(entityManagers);
      SecurityContextHolder.getContext()
          .setAuthentication(jwtAuthentication(tenantId, "unknown-subject"));

      assertThrows(SecurityException.class, () -> resolver.withActor(actor -> actor));
    }
  }

  @Test
  void failsClosedWhenNoJwtIsAuthenticated(PostgreSqlTestContainer database) {
    TenantId tenantId = new TenantId(UUID.randomUUID());
    TenantSchema tenant = prepare(database, tenantId);
    try (EntityManagerFactory entityManagers = entityManagers(database, tenant)) {
      SpringAgentActorResolver resolver = resolver(entityManagers);
      // No SecurityContextHolder authentication set at all.
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
      SpringAgentActorResolver resolver = resolver(entityManagers);
      SecurityContextHolder.getContext().setAuthentication(jwtAuthentication(tenantId, subject));

      ThreadBoundTenantScope tenants = sharedTenants;
      assertTrue(tenants.current().isEmpty());
      java.util.function.Function<AgentActor, Void> throwing =
          actor -> {
            throw new IllegalStateException("caller failure");
          };
      assertThrows(IllegalStateException.class, () -> resolver.withActor(throwing));
      assertTrue(tenants.current().isEmpty(), "the tenant scope must be closed after the throw");
    }
  }

  private ThreadBoundTenantScope sharedTenants;

  private SpringAgentActorResolver resolver(EntityManagerFactory entityManagers) {
    sharedTenants = new ThreadBoundTenantScope();
    ActorRepository actorRepository = new ActorRepository();
    // A fresh EntityManager per resolver call would be more realistic (request-scoped in a real
    // app), but a single one bound outside any transaction is enough to prove the resolution
    // logic itself; TenantScope.call opens/closes around each withActor invocation regardless.
    EntityManager entityManager = entityManagers.createEntityManager();
    actorRepository.bindPersistenceContext(entityManager);
    return new SpringAgentActorResolver(
        new ActorServiceImpl(actorRepository), sharedTenants, CLIENT_ID);
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

  private JwtAuthenticationToken jwtAuthentication(TenantId tenantId, String subject) {
    Jwt jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .claim("sub", subject)
            .claim(
                "organization",
                Map.of(
                    "acme",
                    Map.of(
                        "id", "org-" + UUID.randomUUID(),
                        "forwardmeasure.tenant-id", tenantId.value().toString(),
                        "resource_access",
                            Map.of(CLIENT_ID, Map.of("roles", java.util.List.of("member"))))))
            .build();
    return new JwtAuthenticationToken(jwt);
  }

  private TenantSchema prepare(PostgreSqlTestContainer database, TenantId tenantId) {
    TenantSchema tenant = TenantSchema.forTenant(tenantId);
    database.createSchema(tenant.value());
    TenantSchemaMigrator migrator =
        new TenantSchemaMigrator(
            database.dataSource(),
            "db/changelog/agent-os-spring-actor-binding-test.xml",
            getClass().getClassLoader());
    assertTrue(migrator.validate(tenant).valid());
    migrator.migrate(tenant);
    assertTrue(migrator.status(tenant).current());
    return tenant;
  }

  private EntityManagerFactory entityManagers(
      PostgreSqlTestContainer database, TenantSchema tenant) {
    return Persistence.createEntityManagerFactory(
        "agent-os-spring-actor-binding-test",
        Map.of(
            "jakarta.persistence.jdbc.url", database.hostJdbcUrl(),
            "jakarta.persistence.jdbc.user", database.username(),
            "jakarta.persistence.jdbc.password", database.password(),
            "jakarta.persistence.jdbc.driver", "org.postgresql.Driver",
            "hibernate.default_schema", tenant.value()));
  }
}
