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
import com.forwardmeasure.jpa.tenancy.TenantId;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.logging.Logger;
import javax.sql.DataSource;

// Bounded Kubernetes migration-job entry point - a direct port of openworkflow-migrations' own
// real, already-deployed OpenWorkflowMigrationsMain (consulted per explicit instruction), with the
// Cassandra and "additional runtime databases" (single-tenant apps like Keycloak/Superset sharing
// the same Postgres cluster) branches dropped: agent-os is Postgres-only and does not provision
// database roles on behalf of other applications.
public final class AgentOsMigrationsMain {
  private AgentOsMigrationsMain() {}

  public static void main(String[] arguments) {
    // Administrator credential - this process's own connection. Creates/rotates the runtime role
    // below and applies schema migrations; never the role application services connect as.
    String url = required("AGENT_OS_DATABASE_URL");
    String username = required("AGENT_OS_DATABASE_USERNAME");
    String password = required("AGENT_OS_DATABASE_PASSWORD");
    // Runtime credential - never connected as here, only used to create/rotate that role and grant
    // it scoped, per-tenant-schema privileges. agent-os-governance-{fw}/agent-os-execution-{fw}
    // connect as this role at request-serving time, never as the administrator credential above.
    String runtimeUsername = required("AGENT_OS_RUNTIME_DATABASE_USERNAME");
    String runtimePassword = required("AGENT_OS_RUNTIME_DATABASE_PASSWORD");
    AgentOsTenantMigrator migrator =
        new AgentOsTenantMigrator(
            new DriverManagerDataSource(url, username, password), runtimeUsername);
    migrator.ensureRuntimeRole(runtimePassword);
    Arrays.stream(required("AGENT_OS_TENANT_IDS").split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .map(TenantId::parse)
        .forEach(migrator::provisionAndMigrate);
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
