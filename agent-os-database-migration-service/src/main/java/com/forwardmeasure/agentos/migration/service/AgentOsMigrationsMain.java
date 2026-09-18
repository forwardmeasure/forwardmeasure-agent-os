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
package com.forwardmeasure.agentos.migration.service;

import com.forwardmeasure.agentos.migration.AgentOsTenantMigrator;
import com.forwardmeasure.jpa.datasource.TenantDataSourceRegistry;
import com.forwardmeasure.jpa.datasource.TenantDataSourceTemplate;
import com.forwardmeasure.jpa.tenancy.TenantDatabase;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.logging.Logger;
import javax.sql.DataSource;

// Bounded Kubernetes migration-job entry point - database-per-tenant, schema-per-product, mirroring
// forwardmeasure-entity-intelligence's own real EntityIntelligenceMigrationsMain exactly (same
// alias-derivation, same TenantDataSourceRegistry-per-run shape) rather than openworkflow-
// migrations' own OpenWorkflowMigrationsMain, which additionally owns Cassandra and "additional
// runtime databases" (single-tenant apps like Keycloak/Superset) that don't apply here: agent-os is
// Postgres-only and does not provision database roles on behalf of other applications.
public final class AgentOsMigrationsMain {
  private AgentOsMigrationsMain() {}

  public static void main(String[] arguments) {
    // Administrator credential - this process's own connection to the platform's own small
    // control-plane database. Used only to create each tenant's own database (idempotent) and
    // create/rotate agent-os's own runtime role; never the role application services connect as.
    String url = required("AGENT_OS_DATABASE_URL");
    String username = required("AGENT_OS_DATABASE_USERNAME");
    String password = required("AGENT_OS_DATABASE_PASSWORD");
    // Runtime credential - never connected as here, only created/rotated and granted scoped,
    // per-tenant-database privileges - agent-os's own runtime role.
    String runtimeUsername = required("AGENT_OS_RUNTIME_DATABASE_USERNAME");
    String runtimePassword = required("AGENT_OS_RUNTIME_DATABASE_PASSWORD");

    DataSource platformDataSource = new DriverManagerDataSource(url, username, password);
    TenantDataSourceRegistry tenantDataSources =
        new TenantDataSourceRegistry(
            new TenantDataSourceTemplate(databaseUrlPrefix(url), username, password));
    AgentOsTenantMigrator migrator =
        new AgentOsTenantMigrator(platformDataSource, tenantDataSources, runtimeUsername);
    migrator.ensureRuntimeRole(runtimePassword);
    // Same alias-derivation as fowf's own OpenWorkflowMigrationsMain and fei's own
    // EntityIntelligenceMigrationsMain - TenantDatabase is always derived from the tenant's alias,
    // never supplied directly.
    Arrays.stream(required("AGENT_OS_TENANTS").split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .map(value -> value.split(":", 2))
        .forEach(
            parts -> {
              if (parts.length != 2) {
                throw new IllegalArgumentException("Tenant entries must be alias:display-name");
              }
              migrator.provisionAndMigrate(TenantDatabase.forAlias(parts[0]));
            });
  }

  /**
   * Derives the tenant-database-per-tenant JDBC URL prefix from {@code AGENT_OS_DATABASE_URL} (a
   * complete URL to the platform database) by stripping its trailing database-name segment.
   */
  static String databaseUrlPrefix(String url) {
    int lastSlash = url.lastIndexOf('/');
    if (lastSlash < 0) {
      throw new IllegalArgumentException(
          "AGENT_OS_DATABASE_URL must include a database name: " + url);
    }
    return url.substring(0, lastSlash + 1);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value.trim();
  }

  private record DriverManagerDataSource(String url, String username, String password)
      implements DataSource {
    @Override
    public Connection getConnection() throws SQLException {
      return DriverManager.getConnection(url, username, password);
    }

    @Override
    public Connection getConnection(String suppliedUsername, String suppliedPassword)
        throws SQLException {
      return DriverManager.getConnection(url, suppliedUsername, suppliedPassword);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
      return DriverManager.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter output) throws SQLException {
      DriverManager.setLogWriter(output);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
      DriverManager.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return DriverManager.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
      throw new SQLException("Not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
      return false;
    }
  }
}
