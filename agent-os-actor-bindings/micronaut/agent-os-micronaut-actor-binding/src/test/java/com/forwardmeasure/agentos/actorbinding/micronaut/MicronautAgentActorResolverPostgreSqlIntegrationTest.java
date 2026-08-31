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
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.authentication.ServerAuthentication;
import io.micronaut.security.utils.SecurityService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

@WithPostgreSqlContainer(databaseName = "agent_os_micronaut_actor_binding_contract")
class MicronautAgentActorResolverPostgreSqlIntegrationTest {

  private static final String CLIENT_ID = "agent-os";

  @Test
  void resolvesTheAgentActorForAProvisionedSubject(PostgreSqlTestContainer database) {
    TenantId tenantId = new TenantId(UUID.randomUUID());
    TenantSchema tenant = prepare(database, tenantId);
    try (EntityManagerFactory entityManagers = entityManagers(database, tenant)) {
      String subject = "keycloak-subject-" + UUID.randomUUID();
      provisionActor(entityManagers, subject);

      MicronautAgentActorResolver resolver =
          resolver(
              entityManagers,
              new ThreadBoundTenantScope(),
              fakeSecurity(authentication(tenantId, subject)));

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
      MicronautAgentActorResolver resolver =
          resolver(
              entityManagers,
              new ThreadBoundTenantScope(),
              fakeSecurity(authentication(tenantId, "unknown-subject")));

      assertThrows(SecurityException.class, () -> resolver.withActor(actor -> actor));
    }
  }

  @Test
  void failsClosedWhenNoJwtIsAuthenticated(PostgreSqlTestContainer database) {
    TenantId tenantId = new TenantId(UUID.randomUUID());
    TenantSchema tenant = prepare(database, tenantId);
    try (EntityManagerFactory entityManagers = entityManagers(database, tenant)) {
      MicronautAgentActorResolver resolver =
          resolver(entityManagers, new ThreadBoundTenantScope(), fakeSecurity(null));

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
      MicronautAgentActorResolver resolver =
          resolver(entityManagers, tenants, fakeSecurity(authentication(tenantId, subject)));

      assertTrue(tenants.current().isEmpty());
      Function<AgentActor, Void> throwing =
          actor -> {
            throw new IllegalStateException("caller failure");
          };
      assertThrows(IllegalStateException.class, () -> resolver.withActor(throwing));
      assertTrue(tenants.current().isEmpty(), "the tenant scope must be closed after the throw");
    }
  }

  private MicronautAgentActorResolver resolver(
      EntityManagerFactory entityManagers,
      ThreadBoundTenantScope tenants,
      SecurityService security) {
    ActorRepository actorRepository = new ActorRepository();
    EntityManager entityManager = entityManagers.createEntityManager();
    actorRepository.bindPersistenceContext(entityManager);
    return new MicronautAgentActorResolver(
        security, new ActorServiceImpl(actorRepository), tenants, CLIENT_ID);
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

  // MicronautAgentActorResolver only calls security.getAuthentication() - a minimal fake
  // returning a fixed Optional is enough, no real Micronaut context needed.
  private static SecurityService fakeSecurity(Authentication authentication) {
    Optional<Authentication> value = Optional.ofNullable(authentication);
    return new SecurityService() {
      @Override
      public Optional<String> username() {
        return value.map(Authentication::getName);
      }

      @Override
      public Optional<Authentication> getAuthentication() {
        return value;
      }

      @Override
      public boolean isAuthenticated() {
        return value.isPresent();
      }

      @Override
      public boolean hasRole(String role) {
        return value.map(a -> a.getRoles().contains(role)).orElse(false);
      }
    };
  }

  private Authentication authentication(TenantId tenantId, String subject) {
    Map<String, Object> attributes =
        Map.of(
            "sub",
            subject,
            "organization",
            Map.of(
                "acme",
                Map.of(
                    "id", "org-" + UUID.randomUUID(),
                    "forwardmeasure.tenant-id", tenantId.value().toString(),
                    "resource_access", Map.of(CLIENT_ID, Map.of("roles", List.of("member"))))));
    return new ServerAuthentication(subject, List.of(), attributes);
  }

  private TenantSchema prepare(PostgreSqlTestContainer database, TenantId tenantId) {
    TenantSchema tenant = TenantSchema.forTenant(tenantId);
    database.createSchema(tenant.value());
    TenantSchemaMigrator migrator =
        new TenantSchemaMigrator(
            database.dataSource(),
            "db/changelog/agent-os-micronaut-actor-binding-test.xml",
            getClass().getClassLoader());
    assertTrue(migrator.validate(tenant).valid());
    migrator.migrate(tenant);
    assertTrue(migrator.status(tenant).current());
    return tenant;
  }

  private EntityManagerFactory entityManagers(
      PostgreSqlTestContainer database, TenantSchema tenant) {
    return Persistence.createEntityManagerFactory(
        "agent-os-micronaut-actor-binding-test",
        Map.of(
            "jakarta.persistence.jdbc.url", database.hostJdbcUrl(),
            "jakarta.persistence.jdbc.user", database.username(),
            "jakarta.persistence.jdbc.password", database.password(),
            "jakarta.persistence.jdbc.driver", "org.postgresql.Driver",
            "hibernate.default_schema", tenant.value()));
  }
}
