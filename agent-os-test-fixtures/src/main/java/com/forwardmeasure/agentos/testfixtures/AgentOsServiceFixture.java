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
package com.forwardmeasure.agentos.testfixtures;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.agentos.migration.AgentOsTenantMigrator;
import com.forwardmeasure.authzen.testkit.AuthzenKeycloakFixture;
import com.forwardmeasure.database.migration.api.DatabaseTarget;
import com.forwardmeasure.database.migration.api.MigrationPlan;
import com.forwardmeasure.database.migration.api.MigrationRequest;
import com.forwardmeasure.database.migration.liquibase.LiquibaseMigrationEngine;
import com.forwardmeasure.jpa.datasource.TenantDataSourceRegistry;
import com.forwardmeasure.jpa.datasource.TenantDataSourceTemplate;
import com.forwardmeasure.jpa.liquibase.TenantRegistry;
import com.forwardmeasure.jpa.tenancy.Did;
import com.forwardmeasure.jpa.tenancy.FunctionalSchema;
import com.forwardmeasure.jpa.tenancy.TenantDatabase;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The real environment an agent-os deployment's HTTP tests boot against: a Keycloak with one
 * Organization for the tenant, and a PostgreSQL whose default database plays the platform's
 * control-plane database. The tenant is provisioned exactly as the agent-os migration Job does it
 * ({@link AgentOsTenantMigrator}: runtime role, tenant-registry read grant, tenant database with
 * its identity and agent-os schemas) and registered in {@code tenant_registry}; the services
 * connect as the runtime role, as in production.
 *
 * <p>Four callers, each a genuine signed token from that Keycloak:
 *
 * <ul>
 *   <li>{@link #enabledActorToken()} - an Organization member with an enabled actor (seeded by the
 *       {@code agent-os-service-test-fixtures.xml} changelog);
 *   <li>{@link #disabledActorToken()} - a member whose actor's tenant access was removed;
 *   <li>{@link #unprovisionedActorToken()} - a member with no actor at all;
 *   <li>{@link #noOrganizationToken()} - a valid token carrying no active Organization.
 * </ul>
 */
public final class AgentOsServiceFixture implements AutoCloseable {

  /**
   * The Keycloak client whose Organization roles the services read ({@code
   * agent-os.security.client-id}).
   */
  public static final String CLIENT_ID = AuthzenKeycloakFixture.CLIENT_ID;

  public static final String RUNTIME_USERNAME = "agent_os_test_runtime";
  public static final String RUNTIME_PASSWORD = "agent-os-test-runtime";

  /** The tenant's Organization alias, and the alias its tenant database is named for. */
  public static final String TENANT_ALIAS = "agentostest";

  private static final String ROLE = "agent-os-member";
  private static final String SECRET = "agent-os-test-secret";
  private static final String DISABLED_CLIENT = "agent-os-disabled-actor";
  private static final String UNPROVISIONED_CLIENT = "agent-os-unprovisioned-actor";
  private static final String NO_ORGANIZATION_CLIENT = "agent-os-no-organization";
  private static final String FIXTURE_CHANGELOG = "db/changelog/agent-os-service-test-fixtures.xml";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final AuthzenKeycloakFixture keycloak;
  private final PostgreSqlTestContainer postgres;
  private final String unreachableUrl;

  private AgentOsServiceFixture(
      AuthzenKeycloakFixture keycloak, PostgreSqlTestContainer postgres, String unreachableUrl) {
    this.keycloak = keycloak;
    this.postgres = postgres;
    this.unreachableUrl = unreachableUrl;
  }

  public static AgentOsServiceFixture start() {
    AuthzenKeycloakFixture keycloak = AuthzenKeycloakFixture.start();
    PostgreSqlTestContainer postgres = null;
    try {
      postgres = new PostgreSqlTestContainer().start();
      provision(keycloak, postgres);
      return new AgentOsServiceFixture(keycloak, postgres, allocateUnreachableUrl());
    } catch (RuntimeException | Error failure) {
      if (postgres != null) {
        postgres.close();
      }
      keycloak.close();
      throw failure;
    }
  }

  private static void provision(AuthzenKeycloakFixture keycloak, PostgreSqlTestContainer postgres) {
    Did tenantDid = Did.parse("did:fwmtest:tenant:" + TENANT_ALIAS);
    TenantDatabase tenantDatabase = TenantDatabase.forAlias(TENANT_ALIAS);

    String organizationId = keycloak.provisionTenant(TENANT_ALIAS, tenantDid, ROLE);
    for (String client : List.of(DISABLED_CLIENT, UNPROVISIONED_CLIENT)) {
      keycloak.createServiceAccountClient(client, SECRET);
      keycloak.addServiceAccountToOrganization(organizationId, client, ROLE);
    }
    keycloak.createServiceAccountClient(NO_ORGANIZATION_CLIENT, SECRET);

    String jdbcUrl = postgres.hostJdbcUrl();
    String urlPrefix = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);
    try (TenantDataSourceRegistry tenantDataSources =
        new TenantDataSourceRegistry(
            new TenantDataSourceTemplate(urlPrefix, postgres.username(), postgres.password()))) {
      // Exactly the migration Job's sequence (AgentOsMigrationsMain).
      AgentOsTenantMigrator migrator =
          new AgentOsTenantMigrator(postgres.dataSource(), tenantDataSources, RUNTIME_USERNAME);
      migrator.ensureRuntimeRole(RUNTIME_PASSWORD);
      migrator.grantTenantRegistryRead();
      migrator.provisionAndMigrate(tenantDatabase);
      // fowf's migration Job owns tenant registration in production.
      new TenantRegistry(postgres.dataSource()).register(tenantDid, TENANT_ALIAS, tenantDatabase);

      // Actors are provisioned when tenant access is granted - here, by the fixture changelog.
      new LiquibaseMigrationEngine(AgentOsServiceFixture.class.getClassLoader())
          .migrate(
              new MigrationRequest(
                  tenantDataSources.dataSourceFor(tenantDatabase),
                  DatabaseTarget.schema(FunctionalSchema.AGENT_OS.schemaName()),
                  new MigrationPlan(
                      "agent-os-service-test-fixtures",
                      "liquibase",
                      List.of(FIXTURE_CHANGELOG),
                      Set.of(),
                      Set.of(),
                      Map.of(
                          "agentos.fixture.enabled-subject",
                          subjectOf(keycloak.mintUserToken()),
                          "agentos.fixture.disabled-subject",
                          subjectOf(keycloak.clientCredentialsToken(DISABLED_CLIENT, SECRET))))));
    }
  }

  /** The realm URL every token's {@code iss} carries. */
  public URI issuer() {
    return keycloak.issuer();
  }

  /** The realm's JWKS endpoint (Micronaut's signature configuration). */
  public String jwksUrl() {
    return keycloak.issuer() + "/protocol/openid-connect/certs";
  }

  /**
   * The control-plane database (holding {@code tenant_registry}): the services' default datasource.
   */
  public String controlPlaneJdbcUrl() {
    return postgres.hostJdbcUrl();
  }

  /**
   * Host the services reach tenant databases on ({@code forwardmeasure.jpa.tenant-database.host}).
   */
  public String databaseHost() {
    return postgres.host();
  }

  public int databasePort() {
    return postgres.mappedPort();
  }

  /** An http URL nothing listens on - an outbound dependency (OPA, OpenWorkflow) that is down. */
  public String unreachableUrl() {
    return unreachableUrl;
  }

  public String enabledActorToken() {
    return keycloak.mintUserToken();
  }

  public String disabledActorToken() {
    return keycloak.clientCredentialsToken(DISABLED_CLIENT, SECRET);
  }

  public String unprovisionedActorToken() {
    return keycloak.clientCredentialsToken(UNPROVISIONED_CLIENT, SECRET);
  }

  public String noOrganizationToken() {
    return keycloak.clientCredentialsToken(NO_ORGANIZATION_CLIENT, SECRET);
  }

  @Override
  public void close() {
    try {
      postgres.close();
    } finally {
      keycloak.close();
    }
  }

  private static String subjectOf(String jwt) {
    String[] segments = jwt.split("\\.");
    try {
      return JSON.readTree(Base64.getUrlDecoder().decode(segments[1])).path("sub").asText();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static String allocateUnreachableUrl() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return "http://127.0.0.1:" + socket.getLocalPort();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
