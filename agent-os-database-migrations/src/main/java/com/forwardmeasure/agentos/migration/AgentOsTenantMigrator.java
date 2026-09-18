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
import com.forwardmeasure.jpa.datasource.TenantDataSourceRegistry;
import com.forwardmeasure.jpa.tenancy.FunctionalSchema;
import com.forwardmeasure.jpa.tenancy.TenantDatabase;
import com.forwardmeasure.openworkflow.migration.OpenWorkflowTenantMigrator;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Database-per-tenant, schema-per-product tenant provisioning for agent-os - a thin wrapper around
 * {@code openworkflow-migrations}' own real, already-deployed {@link OpenWorkflowTenantMigrator},
 * reused directly as a library per that class's own documented extension point (the same reuse
 * {@code forwardmeasure-entity-intelligence} and {@code forwardmeasure-decision-engine} already
 * rely on), rather than a second, parallel tenant-provisioning implementation. {@code
 * platformDataSource} connects to the platform's own small control-plane database; {@code
 * tenantDataSources} resolves each tenant's own physical database once it exists.
 */
public final class AgentOsTenantMigrator {
  public static final String CHANGELOG = "db/changelog/agent-os-master.xml";

  private final OpenWorkflowTenantMigrator delegate;

  public AgentOsTenantMigrator(
      DataSource platformDataSource,
      TenantDataSourceRegistry tenantDataSources,
      String runtimeUsername) {
    this.delegate =
        new OpenWorkflowTenantMigrator(
            Objects.requireNonNull(platformDataSource, "platformDataSource"),
            Objects.requireNonNull(tenantDataSources, "tenantDataSources"),
            runtimeUsername,
            CHANGELOG,
            FunctionalSchema.AGENT_OS);
  }

  /**
   * Creates the runtime role if it does not already exist, and unconditionally syncs its password -
   * safe to call on every deploy, not just the first. Must run once, before any per-tenant work.
   */
  public void ensureRuntimeRole(String runtimePassword) {
    delegate.ensureRuntimeRole(runtimePassword);
  }

  /**
   * Creates the tenant's own database if absent, creates agent-os's own functional schema within
   * it, migrates it, then grants the runtime role access - idempotent, safe to call redundantly
   * alongside another product's migration Job provisioning the same tenant database concurrently.
   */
  public MigrationResult provisionAndMigrate(TenantDatabase database) {
    return delegate.provisionAndMigrate(database);
  }
}
