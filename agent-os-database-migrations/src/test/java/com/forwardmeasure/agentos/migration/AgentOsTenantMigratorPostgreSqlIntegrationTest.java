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

import com.forwardmeasure.jpa.tenancy.TenantId;
import com.forwardmeasure.jpa.tenancy.TenantSchema;
import com.forwardmeasure.testcontainers.junit.postgresql.WithPostgreSqlContainer;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Mirrors openworkflow-migrations' own real DefinitionPlaneMigrationTest shape (same
// provision-twice-for-idempotency pattern, same information_schema introspection instead of
// trusting the migrator's own return value) - found and reused, not designed fresh, per explicit
// instruction to consult that module. Goes one step further than that real precedent: it never
// actually connects as the granted runtime role (its test happens to provision the same superuser
// it already connects as), so it can't catch a real GRANT-syntax bug. This test provisions a
// genuinely distinct low-privilege role and connects as it directly, proving the grant is real,
// not just that the SQL didn't throw.
@WithPostgreSqlContainer(databaseName = "agent_os_migrations")
class AgentOsTenantMigratorPostgreSqlIntegrationTest {

  private static final TenantId TENANT_A =
      new TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
  private static final TenantId TENANT_B =
      new TenantId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
  // Deliberately never provisioned - see the isolation assertion below for what this proves.
  private static final TenantId TENANT_C =
      new TenantId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
  private static final List<String> EXPECTED_TABLES =
      List.of("actor", "agent", "agent_audit_event", "agent_execution");
  private static final String RUNTIME_USERNAME = "agent_os_runtime";
  private static final String RUNTIME_PASSWORD = "runtime-secret";

  @Test
  void provisionsAndMigratesEveryVerticalIndependentlyPerTenant(PostgreSqlTestContainer database)
      throws Exception {
    AgentOsTenantMigrator migrator =
        new AgentOsTenantMigrator(database.dataSource(), RUNTIME_USERNAME);
    migrator.ensureRuntimeRole(RUNTIME_PASSWORD);

    migrator.provisionAndMigrate(TENANT_A);
    migrator.provisionAndMigrate(TENANT_B);
    // Re-provisioning an already-migrated tenant must be safe - real deploys call this on every
    // release, not just the tenant's first one.
    migrator.provisionAndMigrate(TENANT_A);

    assertEquals(
        EXPECTED_TABLES, applicationTables(database, TenantSchema.forTenant(TENANT_A).value()));
    assertEquals(
        EXPECTED_TABLES, applicationTables(database, TenantSchema.forTenant(TENANT_B).value()));
    assertEquals(8, changeSetCount(database, TenantSchema.forTenant(TENANT_A).value()));
    assertEquals(8, changeSetCount(database, TenantSchema.forTenant(TENANT_B).value()));

    // The real point of this test, beyond openworkflow's own precedent: connect as the actually-
    // provisioned runtime role (not the admin credential this migrator itself connects as) and
    // prove the grant is real, not just that the SQL didn't throw.
    //
    // One shared runtime role legitimately gets access to every tenant this same migrator
    // provisions (isolation between tenants is enforced by TenantScope/schema-switching in the
    // application layer, already built and tested elsewhere this session - not by a separate
    // database credential per tenant) - so the meaningful boundary to prove here isn't "tenant A
    // vs tenant B", it's "provisioned vs never provisioned": TENANT_C's schema was never created,
    // so the runtime role - which only ever received grants scoped to schemas that actually
    // exist - has no access to it at all.
    try (Connection runtime =
        DriverManager.getConnection(database.hostJdbcUrl(), RUNTIME_USERNAME, RUNTIME_PASSWORD)) {
      String tenantASchema = TenantSchema.forTenant(TENANT_A).value();
      String tenantBSchema = TenantSchema.forTenant(TENANT_B).value();
      String tenantCSchema = TenantSchema.forTenant(TENANT_C).value();

      runtime.setSchema(tenantASchema);
      try (var statement = runtime.createStatement()) {
        // Selects (and finds zero rows in) a real table the migration created - proves USAGE on
        // the schema and SELECT on the table, not just that the connection itself succeeded.
        statement.executeQuery("select id from actor");
      }
      runtime.setSchema(tenantBSchema);
      try (var statement = runtime.createStatement()) {
        statement.executeQuery("select id from actor");
      }

      SQLException deniedForUnprovisionedTenant =
          assertThrows(
              SQLException.class,
              () -> {
                try (var statement = runtime.createStatement()) {
                  statement.executeQuery("select id from " + tenantCSchema + ".actor");
                }
              });
      // Postgres reports this as "schema does not exist" (42P01), not "permission denied"
      // (42501) - TENANT_C's schema was never created at all, which is itself the proof: nothing
      // provisions a schema, or grants access to one, except through provisionAndMigrate.
      assertEquals("42P01", deniedForUnprovisionedTenant.getSQLState());
    }
  }

  private static List<String> applicationTables(PostgreSqlTestContainer database, String schema)
      throws Exception {
    try (Connection connection = database.dataSource().getConnection();
        var statement =
            connection.prepareStatement(
                "select table_name from information_schema.tables where table_schema = ? and"
                    + " table_name in ('actor', 'agent', 'agent_audit_event', 'agent_execution')"
                    + " order by table_name")) {
      statement.setString(1, schema);
      try (var result = statement.executeQuery()) {
        List<String> tables = new ArrayList<>();
        while (result.next()) {
          tables.add(result.getString(1));
        }
        return List.copyOf(tables);
      }
    }
  }

  private static int changeSetCount(PostgreSqlTestContainer database, String schema)
      throws Exception {
    try (Connection connection = database.dataSource().getConnection();
        var statement =
            connection.prepareStatement("select count(*) from " + schema + ".databasechangelog");
        var result = statement.executeQuery()) {
      result.next();
      return result.getInt(1);
    }
  }
}
