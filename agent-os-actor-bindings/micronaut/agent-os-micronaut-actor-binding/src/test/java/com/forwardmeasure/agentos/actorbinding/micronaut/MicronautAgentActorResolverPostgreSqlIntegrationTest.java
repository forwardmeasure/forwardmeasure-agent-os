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
import com.forwardmeasure.database.migration.api.DatabaseTarget;
import com.forwardmeasure.database.migration.api.MigrationPlan;
import com.forwardmeasure.database.migration.api.MigrationRequest;
import com.forwardmeasure.database.migration.liquibase.LiquibaseMigrationEngine;
import com.forwardmeasure.jpa.identity.entity.Actor;
import com.forwardmeasure.jpa.identity.entity.IdentityType;
import com.forwardmeasure.jpa.identity.repository.ActorRepository;
import com.forwardmeasure.jpa.identity.service.ActorService;
import com.forwardmeasure.jpa.tenancy.FunctionalSchema;
import com.forwardmeasure.jpa.tenancy.TenantDatabase;
import com.forwardmeasure.jpa.tenancy.TenantId;
import com.forwardmeasure.jpa.tenancy.TenantScope;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlContainerConfiguration;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.authentication.ServerAuthentication;
import io.micronaut.security.utils.SecurityService;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

// Real @MicronautTest boot (not a hand-wired EntityManagerFactory) - proves
// MicronautAgentActorResolver
// actually routes through real Hibernate multi-tenancy (MicronautSchemaConnectionProvider, wired by
// ForwardMeasureJpaFactory, reading TenantScope) the same way a real request would, not just that
// the resolver's own open/close bookkeeping works against a schema hand-pinned to the right place
// regardless of what TenantScope carries.
@MicronautTest(startApplication = false, transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicronautAgentActorResolverPostgreSqlIntegrationTest implements TestPropertyProvider {

  private static final String CLIENT_ID = "agent-os";

  private static final TenantDatabase TENANT_DATABASE =
      TenantDatabase.forAlias("agentosmicronauttest");
  private static final FunctionalSchema SCHEMA = FunctionalSchema.AGENT_OS;

  private static final PostgreSqlTestContainer DATABASE =
      new PostgreSqlTestContainer(
          new PostgreSqlContainerConfiguration(
              PostgreSqlContainerConfiguration.DEFAULT_IMAGE,
              TENANT_DATABASE.value(),
              "forwardmeasure",
              "forwardmeasure-test-only",
              Optional.empty(),
              List.of(),
              PostgreSqlContainerConfiguration.DEFAULT_MEMORY_BYTES,
              PostgreSqlContainerConfiguration.DEFAULT_MEMORY_SWAP_BYTES));

  private static boolean initialized;

  @Inject TenantScope tenantScope;

  @Inject TransactionOperations<Session> transactions;

  @Inject ActorRepository actorRepository;

  @Inject ActorService actorService;

  @Override
  public synchronized Map<String, String> getProperties() {
    if (!initialized) {
      DATABASE.start();
      DATABASE.createSchema(SCHEMA.schemaName());
      new LiquibaseMigrationEngine(getClass().getClassLoader())
          .migrate(
              new MigrationRequest(
                  DATABASE.dataSource(),
                  DatabaseTarget.schema(SCHEMA.schemaName()),
                  MigrationPlan.liquibase(
                      "forwardmeasure-jpa", "db/changelog/forwardmeasure-jpa.xml")));
      initialized = true;
    }
    return Map.ofEntries(
        Map.entry("datasources.default.url", DATABASE.hostJdbcUrl()),
        Map.entry("datasources.default.username", DATABASE.username()),
        Map.entry("datasources.default.password", DATABASE.password()),
        Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
        Map.entry("jpa.default.properties.hibernate.hbm2ddl.auto", "none"),
        Map.entry("jpa.default.entity-scan.packages[0]", "com.forwardmeasure.jpa.identity.entity"),
        Map.entry("forwardmeasure.jpa.functional-schema", SCHEMA.name()),
        Map.entry("forwardmeasure.jpa.tenant-database.host", DATABASE.host()),
        Map.entry("forwardmeasure.jpa.tenant-database.port", String.valueOf(DATABASE.mappedPort())),
        Map.entry("forwardmeasure.jpa.tenant-database.username", DATABASE.username()),
        Map.entry("forwardmeasure.jpa.tenant-database.password", DATABASE.password()));
  }

  @Test
  void resolvesTheAgentActorForAProvisionedSubject() {
    String subject = "keycloak-subject-" + UUID.randomUUID();
    provisionActor(subject);
    TenantId tenantId = new TenantId(UUID.randomUUID());

    MicronautAgentActorResolver resolver =
        resolver(fakeSecurity(authentication(tenantId, subject)));

    AgentActor resolved = resolver.withActor(actor -> actor);

    assertEquals(subject, resolved.actor().subject());
    assertEquals(tenantId.value(), resolved.tenantId());
  }

  @Test
  void failsClosedWhenNoActorIsProvisionedForTheSubject() {
    MicronautAgentActorResolver resolver =
        resolver(fakeSecurity(authentication(new TenantId(UUID.randomUUID()), "unknown-subject")));
    assertThrows(SecurityException.class, () -> resolver.withActor(actor -> actor));
  }

  @Test
  void failsClosedWhenNoJwtIsAuthenticated() {
    MicronautAgentActorResolver resolver = resolver(fakeSecurity(null));
    assertThrows(SecurityException.class, () -> resolver.withActor(actor -> actor));
  }

  @Test
  void closesTheTenantScopeEvenWhenTheCallerThrows() {
    String subject = "keycloak-subject-" + UUID.randomUUID();
    provisionActor(subject);
    MicronautAgentActorResolver resolver =
        resolver(fakeSecurity(authentication(new TenantId(UUID.randomUUID()), subject)));

    assertTrue(tenantScope.current().isEmpty());
    Function<AgentActor, Void> throwing =
        actor -> {
          throw new IllegalStateException("caller failure");
        };
    assertThrows(IllegalStateException.class, () -> resolver.withActor(throwing));
    assertTrue(tenantScope.current().isEmpty(), "the tenant scope must be closed after the throw");
  }

  private MicronautAgentActorResolver resolver(SecurityService security) {
    return new MicronautAgentActorResolver(security, actorService, tenantScope, CLIENT_ID);
  }

  private void provisionActor(String subject) {
    try (TenantScope.Scope ignored = tenantScope.open(TENANT_DATABASE)) {
      transactions.executeWrite(
          status -> {
            actorRepository.persist(
                Actor.builder()
                    .subjectIdentifier(subject)
                    .identityProvider("keycloak")
                    .type(IdentityType.HUMAN)
                    .build());
            return null;
          });
    }
  }

  // MicronautAgentActorResolver only calls security.getAuthentication() - a minimal fake
  // returning a fixed Optional is enough, no real Micronaut security context needed.
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
    // The organization claim's map key IS the alias TenantDatabase.forAlias(...) derives from -
    // must match TENANT_DATABASE's own alias exactly, since this test proves real routing, not a
    // schema hand-pinned regardless of what the authentication attributes say.
    Map<String, Object> attributes =
        Map.of(
            "sub",
            subject,
            "organization",
            Map.of(
                TENANT_DATABASE.alias(),
                Map.of(
                    "id", "org-" + UUID.randomUUID(),
                    "forwardmeasure.tenant-id", tenantId.value().toString(),
                    "resource_access", Map.of(CLIENT_ID, Map.of("roles", List.of("member"))))));
    return new ServerAuthentication(subject, List.of(), attributes);
  }

  @AfterAll
  static synchronized void stopDatabase() {
    if (initialized) {
      DATABASE.close();
      initialized = false;
    }
  }
}
