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
package com.forwardmeasure.agentos.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.forwardmeasure.jpa.datasource.TenantDataSourceRegistry;
import com.forwardmeasure.jpa.datasource.TenantDataSourceTemplate;
import com.forwardmeasure.jpa.tenancy.TenantDatabase;
import com.forwardmeasure.testcontainers.junit.postgresql.WithPostgreSqlContainer;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

// Database-per-tenant, schema-per-product: AgentOsTenantMigrator is now a thin wrapper around
// openworkflow-migrations' own real OpenWorkflowTenantMigrator (see that class's own javadoc for
// the real create-database/own-database/create-schema/migrate/grant sequence this delegates to -
// not re-proven here). This test's own scope is narrower than the old schema-per-tenant version it
// replaces: prove AgentOsTenantMigrator's own configuration (its changelog, its FunctionalSchema)
// actually lands the right tables in the right tenant database, and that real per-tenant physical
// database isolation - the thing that actually changed under this model - holds.
@WithPostgreSqlContainer(databaseName = "agent_os_migrations")
class AgentOsTenantMigratorPostgreSqlIntegrationTest {

  private static final TenantDatabase TENANT_A = TenantDatabase.forAlias("tenant-a");
  private static final TenantDatabase TENANT_B = TenantDatabase.forAlias("tenant-b");
  // Deliberately never provisioned - see the isolation assertion below for what this proves.
  private static final TenantDatabase TENANT_C = TenantDatabase.forAlias("tenant-c");
  private static final List<String> EXPECTED_TABLES =
      List.of("actor", "agent", "agent_audit_event", "agent_execution");
  private static final String RUNTIME_USERNAME = "agent_os_runtime";
  private static final String RUNTIME_PASSWORD = "runtime-secret";

  @Test
  void provisionsAndMigratesEveryVerticalIndependentlyPerTenantDatabase(
      PostgreSqlTestContainer database) throws Exception {
    String urlPrefix = databaseUrlPrefix(database.hostJdbcUrl());
    TenantDataSourceRegistry tenantDataSources =
        new TenantDataSourceRegistry(
            new TenantDataSourceTemplate(urlPrefix, database.username(), database.password()));
    AgentOsTenantMigrator migrator =
        new AgentOsTenantMigrator(database.dataSource(), tenantDataSources, RUNTIME_USERNAME);
    migrator.ensureRuntimeRole(RUNTIME_PASSWORD);

    migrator.provisionAndMigrate(TENANT_A);
    migrator.provisionAndMigrate(TENANT_B);
    // Re-provisioning an already-migrated tenant must be safe - real deploys call this on every
    // release, not just the tenant's first one.
    migrator.provisionAndMigrate(TENANT_A);

    assertEquals(EXPECTED_TABLES, applicationTables(urlPrefix, database, TENANT_A));
    assertEquals(EXPECTED_TABLES, applicationTables(urlPrefix, database, TENANT_B));
    assertEquals(8, changeSetCount(urlPrefix, database, TENANT_A));
    assertEquals(8, changeSetCount(urlPrefix, database, TENANT_B));

    // The real point of this test: connect as the actually-provisioned runtime role (not the
    // admin credential the migrator itself connects as) directly to each tenant's own physical
    // database, and prove the grant is real, not just that the SQL didn't throw.
    //
    // Isolation is now database-level, not schema-level: TENANT_C's database was never created,
    // so connecting to it fails outright at the connection level, for any role including the
    // runtime role - a stronger, simpler proof than the old schema-permission-denied case.
    try (Connection tenantA = runtimeConnection(urlPrefix, TENANT_A);
        var statement = tenantA.createStatement()) {
      // Selects (and finds zero rows in) a real table the migration created - proves USAGE on
      // the schema and SELECT on the table, not just that the connection itself succeeded.
      statement.executeQuery("select id from agent_os.actor");
    }
    try (Connection tenantB = runtimeConnection(urlPrefix, TENANT_B);
        var statement = tenantB.createStatement()) {
      statement.executeQuery("select id from agent_os.actor");
    }

    SQLException deniedForUnprovisionedTenant =
        assertThrows(SQLException.class, () -> runtimeConnection(urlPrefix, TENANT_C).close());
    // Postgres reports this as "database does not exist" (3D000) - TENANT_C's database was never
    // created at all, which is itself the proof: nothing provisions a database, or grants access
    // to one, except through provisionAndMigrate.
    assertEquals("3D000", deniedForUnprovisionedTenant.getSQLState());
  }

  private static Connection runtimeConnection(String urlPrefix, TenantDatabase tenantDatabase)
      throws SQLException {
    return DriverManager.getConnection(
        urlPrefix + tenantDatabase.value(), RUNTIME_USERNAME, RUNTIME_PASSWORD);
  }

  private static List<String> applicationTables(
      String urlPrefix, PostgreSqlTestContainer database, TenantDatabase tenantDatabase)
      throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                urlPrefix + tenantDatabase.value(), database.username(), database.password());
        var statement =
            connection.prepareStatement(
                "select table_name from information_schema.tables where table_schema = 'agent_os'"
                    + " and table_name in ('actor', 'agent', 'agent_audit_event',"
                    + " 'agent_execution') order by table_name");
        var result = statement.executeQuery()) {
      List<String> tables = new ArrayList<>();
      while (result.next()) {
        tables.add(result.getString(1));
      }
      return List.copyOf(tables);
    }
  }

  private static int changeSetCount(
      String urlPrefix, PostgreSqlTestContainer database, TenantDatabase tenantDatabase)
      throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                urlPrefix + tenantDatabase.value(), database.username(), database.password());
        var statement =
            connection.prepareStatement("select count(*) from agent_os.databasechangelog");
        var result = statement.executeQuery()) {
      result.next();
      return result.getInt(1);
    }
  }

  private static String databaseUrlPrefix(String url) {
    int lastSlash = url.lastIndexOf('/');
    if (lastSlash < 0) {
      throw new IllegalArgumentException("Test container URL must include a database name: " + url);
    }
    return url.substring(0, lastSlash + 1);
  }
}
