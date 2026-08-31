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

import com.forwardmeasure.database.migration.api.MigrationResult;
import com.forwardmeasure.jpa.liquibase.TenantSchemaMigrator;
import com.forwardmeasure.jpa.tenancy.TenantId;
import com.forwardmeasure.jpa.tenancy.TenantSchema;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.sql.DataSource;

// Deployment-owned schema creation, runtime-role provisioning, and composed migration runner -
// a direct port of openworkflow-migrations' own real, already-deployed OpenWorkflowTenantMigrator
// (found and consulted per explicit instruction, not designed fresh). This class always connects
// as the administrator credential (see AgentOsMigrationsMain for the two-credential wiring) -
// runtimeUsername names a role this class creates/rotates and grants scoped privileges to, never
// a role it connects as. The application services (agent-os-governance-{fw}/
// agent-os-execution-{fw}) connect only as that scoped runtime role, never the administrator one -
// they never hold CREATE SCHEMA/CREATE ROLE privileges, only this bounded migration job does.
public final class AgentOsTenantMigrator {
  public static final String CHANGELOG = "db/changelog/agent-os-master.xml";

  // Postgres role/identifier names; not user input - sourced from a Kubernetes Secret in a real
  // deployment - but validated anyway before use in DDL that can't be parameterized via JDBC bind
  // variables. Matches OpenWorkflowTenantMigrator's own real pattern exactly.
  private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]{0,62}");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final DataSource dataSource;
  private final TenantSchemaMigrator migrator;
  private final String runtimeUsername;

  public AgentOsTenantMigrator(DataSource dataSource, String runtimeUsername) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.runtimeUsername = requireSafeIdentifier(runtimeUsername, "runtimeUsername");
    this.migrator =
        new TenantSchemaMigrator(
            dataSource, CHANGELOG, Thread.currentThread().getContextClassLoader());
  }

  /**
   * Creates the runtime role if it does not already exist, and unconditionally syncs its password -
   * safe to call on every deploy, not just the first. Must run once, before any per-tenant work:
   * the runtime role is one role for the whole database, not per-tenant.
   */
  public void ensureRuntimeRole(String runtimePassword) {
    String safeUsername = runtimeUsername;
    Objects.requireNonNull(runtimePassword, "runtimePassword");
    String quotedIdentifier = quoteIdentifier(safeUsername);
    String passwordLiteral = dollarQuote(runtimePassword);
    String createIfMissing =
        "DO $$ BEGIN "
            + "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '"
            + safeUsername
            + "') THEN "
            + "CREATE ROLE "
            + quotedIdentifier
            + " WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD "
            + passwordLiteral
            + "; END IF; END $$;";
    String syncPassword =
        "ALTER ROLE " + quotedIdentifier + " WITH PASSWORD " + passwordLiteral + ";";
    // Role creation alone doesn't grant the creator membership in the new role - Postgres
    // requires SET ROLE privilege (i.e. membership) to later manage anything owned by it, and this
    // admin connection is deliberately NOSUPERUSER. Matches OpenWorkflowTenantMigrator's own real,
    // hard-won fix exactly (see that class's own comment for the concrete failure this avoids).
    String grantMembership = "GRANT " + quotedIdentifier + " TO CURRENT_USER;";
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(createIfMissing);
      statement.execute(syncPassword);
      statement.execute(grantMembership);
    } catch (SQLException exception) {
      throw new RuntimeRoleProvisioningException(safeUsername, exception);
    }
  }

  /** Creates the tenant's schema if missing, migrates it, then grants the runtime role access. */
  public MigrationResult provisionAndMigrate(TenantId tenantId) {
    TenantSchema schema = TenantSchema.forTenant(Objects.requireNonNull(tenantId, "tenantId"));
    createSchema(schema);
    MigrationResult result = migrator.migrate(schema);
    grantRuntimeAccess(schema);
    return result;
  }

  private void createSchema(TenantSchema schema) {
    // TenantSchema is the sole constructor of this identifier and accepts only t_{32 lowercase
    // hex}.
    String sql = "CREATE SCHEMA IF NOT EXISTS \"" + schema.value() + "\"";
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException exception) {
      throw new TenantSchemaProvisioningException(schema, exception);
    }
  }

  /**
   * Grants the runtime role only what it needs within this tenant's own schema - never ownership,
   * never anything outside this schema. ALTER DEFAULT PRIVILEGES covers tables/sequences created by
   * future migrations too, so a later deploy's new table doesn't need a manual re-grant.
   */
  private void grantRuntimeAccess(TenantSchema schema) {
    String quotedSchema = "\"" + schema.value() + "\"";
    String quotedIdentifier = quoteIdentifier(runtimeUsername);
    String sql =
        "GRANT USAGE ON SCHEMA "
            + quotedSchema
            + " TO "
            + quotedIdentifier
            + "; GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
            + quotedSchema
            + " TO "
            + quotedIdentifier
            + "; GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA "
            + quotedSchema
            + " TO "
            + quotedIdentifier
            + "; ALTER DEFAULT PRIVILEGES IN SCHEMA "
            + quotedSchema
            + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "
            + quotedIdentifier
            + "; ALTER DEFAULT PRIVILEGES IN SCHEMA "
            + quotedSchema
            + " GRANT USAGE, SELECT ON SEQUENCES TO "
            + quotedIdentifier
            + ";";
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException exception) {
      throw new TenantSchemaProvisioningException(schema, exception);
    }
  }

  private static String requireSafeIdentifier(String value, String name) {
    Objects.requireNonNull(value, name);
    if (!SAFE_IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " must be a safe SQL identifier: " + value);
    }
    return value;
  }

  private static String quoteIdentifier(String identifier) {
    return "\"" + identifier + "\"";
  }

  // Postgres DDL (CREATE ROLE / ALTER ROLE ... PASSWORD) does not support JDBC bind parameters -
  // dollar-quoting with a randomly generated tag safely embeds an arbitrary password literal
  // (including one containing quotes or backslashes) without string-concatenation escaping.
  private static String dollarQuote(String value) {
    String tag;
    do {
      tag = "pw" + Long.toHexString(RANDOM.nextLong());
    } while (value.contains("$" + tag + "$"));
    return "$" + tag + "$" + value + "$" + tag + "$";
  }
}
