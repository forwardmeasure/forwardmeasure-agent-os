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
package com.forwardmeasure.agentos.governance.jpa.entity;

import com.forwardmeasure.agentos.domain.AgentStatus;
import com.forwardmeasure.agentos.governance.api.model.AgentDefinitionContent;
import com.forwardmeasure.jpa.identity.entity.Actor;
import com.forwardmeasure.jpa.identity.entity.OwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// The governance-record wrapper (identity, status, provenance) plus the governed content,
// persisted directly - see agent-governance-management.openapi.yaml's Agent schema and
// agent-os-domain's AgentDefinition aggregate, which this table is the durable form of.
@Entity
@Table(
    name = "agent",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_agent_namespace_name_agent_version",
            columnNames = {"namespace", "name", "agent_version"}))
@SequenceGenerator(name = "agent_id_generator", sequenceName = "agent_id_seq", allocationSize = 1)
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class Agent extends OwnedEntity<Long> {

  private static final long serialVersionUID = 1L;

  @Id
  @GeneratedValue(generator = "agent_id_generator", strategy = GenerationType.SEQUENCE)
  @Column(name = "id")
  private Long id;

  @NotNull
  @Column(name = "namespace", nullable = false, updatable = false)
  private String namespace;

  @NotNull
  @Column(name = "name", nullable = false, updatable = false)
  private String name;

  // Named agentVersion, not version - AbstractBaseEntity already declares an inherited @Version
  // optimistic-lock column called "version"; this is the agent's own immutable semver coordinate.
  @NotNull
  @Column(name = "agent_version", nullable = false, updatable = false)
  private String agentVersion;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private AgentStatus status;

  @NotNull
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "content", nullable = false)
  private AgentDefinitionContent content;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "reviewer_id", referencedColumnName = "id")
  private Actor reviewer;

  @Column(name = "workflow_id")
  private UUID workflowId;

  @Column(name = "workflow_revision_id")
  private UUID workflowRevisionId;

  @Column(name = "workflow_revision_digest")
  private String workflowRevisionDigest;

  @Column(name = "agent_definition_sha256")
  private String agentDefinitionSha256;

  @Column(name = "published_at")
  private OffsetDateTime publishedAt;

  @Column(name = "deprecated_at")
  private OffsetDateTime deprecatedAt;

  @Column(name = "archived_at")
  private OffsetDateTime archivedAt;
}
