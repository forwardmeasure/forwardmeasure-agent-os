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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

// Real @SpringBootTest boot (not a hand-wired EntityManagerFactory) - proves
// SpringAgentActorResolver
// actually routes through real Hibernate multi-tenancy (SpringSchemaConnectionProvider, wired by
// ForwardMeasureJpaAutoConfiguration, reading TenantScope) the same way a real request would, not
// just that the resolver's own open/close bookkeeping works against a schema hand-pinned to the
// right place regardless of what TenantScope carries.
@SpringBootTest(classes = SpringAgentActorResolverPostgreSqlIntegrationTest.TestApplication.class)
class SpringAgentActorResolverPostgreSqlIntegrationTest {

  private static final String CLIENT_ID = "agent-os";

  private static final TenantDatabase TENANT_DATABASE =
      TenantDatabase.forAlias("agentosspringtest");
  private static final com.forwardmeasure.jpa.tenancy.Did TENANT_DID =
      com.forwardmeasure.jpa.tenancy.Did.parse("did:fwmtest:tenant:" + TENANT_DATABASE.alias());
  private static final TenantId TENANT_ID = TenantId.forDid(TENANT_DID);
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
                  PostgreSqlContainerConfiguration.DEFAULT_MEMORY_SWAP_BYTES))
          .start();

  static {
    DATABASE.createSchema(SCHEMA.schemaName());
    var registry = new com.forwardmeasure.jpa.liquibase.TenantRegistry(DATABASE.dataSource());
    registry.migrate();
    registry.register(TENANT_DID, TENANT_DATABASE.alias(), TENANT_DATABASE);
    new LiquibaseMigrationEngine(
            SpringAgentActorResolverPostgreSqlIntegrationTest.class.getClassLoader())
        .migrate(
            new MigrationRequest(
                DATABASE.dataSource(),
                DatabaseTarget.schema(SCHEMA.schemaName()),
                MigrationPlan.liquibase(
                    "forwardmeasure-jpa", "db/changelog/forwardmeasure-jpa.xml")));
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", DATABASE::hostJdbcUrl);
    properties.add("spring.datasource.username", DATABASE::username);
    properties.add("spring.datasource.password", DATABASE::password);
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    properties.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    properties.add("spring.jpa.open-in-view", () -> "false");
    properties.add("forwardmeasure.jpa.functional-schema", SCHEMA::name);
    properties.add("forwardmeasure.jpa.tenant-database.host", DATABASE::host);
    properties.add(
        "forwardmeasure.jpa.tenant-database.port", () -> String.valueOf(DATABASE.mappedPort()));
    properties.add("forwardmeasure.jpa.tenant-database.username", DATABASE::username);
    properties.add("forwardmeasure.jpa.tenant-database.password", DATABASE::password);
  }

  @Autowired TenantScope tenantScope;

  @Autowired TransactionTemplate transactions;

  @Autowired ActorRepository actorRepository;

  @Autowired ActorService actorService;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @AfterAll
  static void stopDatabase() {
    DATABASE.close();
  }

  @Test
  void resolvesTheAgentActorForAProvisionedSubject() {
    String subject = "keycloak-subject-" + UUID.randomUUID();
    provisionActor(subject);
    TenantId tenantId = TENANT_ID;

    SpringAgentActorResolver resolver = resolver();
    SecurityContextHolder.getContext().setAuthentication(jwtAuthentication(tenantId, subject));

    AgentActor resolved = resolver.withActor(actor -> actor);

    assertEquals(subject, resolved.actor().subject());
    assertEquals(tenantId.value(), resolved.tenantId());
  }

  @Test
  void failsClosedWhenNoActorIsProvisionedForTheSubject() {
    SpringAgentActorResolver resolver = resolver();
    SecurityContextHolder.getContext()
        .setAuthentication(jwtAuthentication(TENANT_ID, "unknown-subject"));

    assertThrows(SecurityException.class, () -> resolver.withActor(actor -> actor));
  }

  @Test
  void failsClosedWhenNoJwtIsAuthenticated() {
    SpringAgentActorResolver resolver = resolver();
    // No SecurityContextHolder authentication set at all.
    assertThrows(SecurityException.class, () -> resolver.withActor(actor -> actor));
  }

  @Test
  void closesTheTenantScopeEvenWhenTheCallerThrows() {
    String subject = "keycloak-subject-" + UUID.randomUUID();
    provisionActor(subject);
    SpringAgentActorResolver resolver = resolver();
    SecurityContextHolder.getContext().setAuthentication(jwtAuthentication(TENANT_ID, subject));

    assertTrue(tenantScope.current().isEmpty());
    Function<AgentActor, Void> throwing =
        actor -> {
          throw new IllegalStateException("caller failure");
        };
    assertThrows(IllegalStateException.class, () -> resolver.withActor(throwing));
    assertTrue(tenantScope.current().isEmpty(), "the tenant scope must be closed after the throw");
  }

  private SpringAgentActorResolver resolver() {
    return new SpringAgentActorResolver(actorService, tenantScope, CLIENT_ID);
  }

  private void provisionActor(String subject) {
    try (TenantScope.Scope ignored = tenantScope.open(TENANT_ID)) {
      transactions.executeWithoutResult(
          status ->
              actorRepository.persist(
                  Actor.builder()
                      .subjectIdentifier(subject)
                      .identityProvider("keycloak")
                      .type(IdentityType.HUMAN)
                      .build()));
    }
  }

  private JwtAuthenticationToken jwtAuthentication(TenantId tenantId, String subject) {
    // The organization claim's map key IS the alias TenantDatabase.forAlias(...) derives from -
    // must match TENANT_DATABASE's own alias exactly, since this test proves real routing, not a
    // schema hand-pinned regardless of what the JWT says.
    Jwt jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .claim("sub", subject)
            .claim(
                "organization",
                Map.of(
                    TENANT_DATABASE.alias(),
                    Map.of(
                        "id", "org-" + UUID.randomUUID(),
                        "forwardmeasure.tenant-did", TENANT_DID.value(),
                        "resource_access",
                            Map.of(CLIENT_ID, Map.of("roles", java.util.List.of("member"))))))
            .build();
    return new JwtAuthenticationToken(jwt);
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(basePackageClasses = Actor.class)
  static class TestApplication {}
}
