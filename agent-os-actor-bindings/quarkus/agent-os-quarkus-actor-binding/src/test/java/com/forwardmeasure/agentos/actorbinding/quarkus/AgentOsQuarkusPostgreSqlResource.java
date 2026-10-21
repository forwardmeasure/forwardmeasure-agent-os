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
package com.forwardmeasure.agentos.actorbinding.quarkus;

import com.forwardmeasure.database.migration.api.DatabaseTarget;
import com.forwardmeasure.database.migration.api.MigrationPlan;
import com.forwardmeasure.database.migration.api.MigrationRequest;
import com.forwardmeasure.database.migration.liquibase.LiquibaseMigrationEngine;
import com.forwardmeasure.jpa.liquibase.TenantIdentityMigrator;
import com.forwardmeasure.jpa.tenancy.FunctionalSchema;
import com.forwardmeasure.jpa.tenancy.TenantDatabase;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlContainerConfiguration;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Real Postgres testcontainer with the tenant database's shared identity schema ({@code
 * identity.actor}, the only table {@link QuarkusAgentActorResolverPostgreSqlIntegrationTest} needs)
 * migrated by {@link TenantIdentityMigrator}, fixture actors seeded by this module's test
 * changelog, and agent-os's own {@link FunctionalSchema#AGENT_OS} schema as the functional schema.
 */
public final class AgentOsQuarkusPostgreSqlResource implements QuarkusTestResourceLifecycleManager {

  /** Seeded by agent-os-quarkus-actor-binding-test.xml with tenant access. */
  static final String ENABLED_SUBJECT = "agent-os-enabled-subject";

  /** Seeded by agent-os-quarkus-actor-binding-test.xml with its tenant access removed. */
  static final String DISABLED_SUBJECT = "agent-os-disabled-subject";

  static final TenantDatabase TENANT_DATABASE = TenantDatabase.forAlias("agentosquarkustest");
  static final com.forwardmeasure.jpa.tenancy.Did TENANT_DID =
      com.forwardmeasure.jpa.tenancy.Did.parse("did:fwmtest:tenant:" + TENANT_DATABASE.alias());
  static final com.forwardmeasure.jpa.tenancy.TenantId TENANT_ID =
      com.forwardmeasure.jpa.tenancy.TenantId.forDid(TENANT_DID);
  static final FunctionalSchema SCHEMA = FunctionalSchema.AGENT_OS;

  private PostgreSqlTestContainer database;

  @Override
  public Map<String, String> start() {
    database =
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
    database.createSchema(SCHEMA.schemaName());
    var registry = new com.forwardmeasure.jpa.liquibase.TenantRegistry(database.dataSource());
    registry.migrate();
    registry.register(TENANT_DID, TENANT_DATABASE.alias(), TENANT_DATABASE);
    // The tenant's shared identity schema (identity.actor) first, as tenant provisioning does,
    // then this module's fixture changelog, which seeds the actors the tests authenticate as.
    new TenantIdentityMigrator(database.dataSource(), getClass().getClassLoader()).migrate();
    new LiquibaseMigrationEngine(getClass().getClassLoader())
        .migrate(
            new MigrationRequest(
                database.dataSource(),
                DatabaseTarget.schema(SCHEMA.schemaName()),
                MigrationPlan.liquibase(
                    "agent-os-quarkus-actor-binding-test",
                    "db/changelog/agent-os-quarkus-actor-binding-test.xml")));
    return Map.of(
        "quarkus.datasource.jdbc.url", database.hostJdbcUrl(),
        "quarkus.datasource.username", database.username(),
        "quarkus.datasource.password", database.password(),
        "forwardmeasure.jpa.functional-schema", SCHEMA.name(),
        "forwardmeasure.jpa.tenant-database.host", database.host(),
        "forwardmeasure.jpa.tenant-database.port", String.valueOf(database.mappedPort()),
        "forwardmeasure.jpa.tenant-database.username", database.username(),
        "forwardmeasure.jpa.tenant-database.password", database.password());
  }

  @Override
  public void stop() {
    if (database != null) {
      database.close();
      database = null;
    }
  }
}
