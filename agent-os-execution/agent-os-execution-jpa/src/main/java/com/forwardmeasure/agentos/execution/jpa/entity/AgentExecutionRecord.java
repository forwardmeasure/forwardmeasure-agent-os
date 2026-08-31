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
package com.forwardmeasure.agentos.execution.jpa.entity;

import com.forwardmeasure.agentos.domain.AgentExecutionState;
import com.forwardmeasure.agentos.domain.AgentInvocationProtocol;
import com.forwardmeasure.jpa.identity.entity.OwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

// The durable record of one agent invocation - see agent-os-domain's AgentExecution for the real
// aggregate this table is the durable form of, and WorkflowExecutionDispatcher's own doc comment
// for why OpenWorkflow, not this table, is the source of truth for state once dispatched. Owned by
// the actor who started it (inherited from OwnedEntity), matching Agent's own visibility model -
// "List agent executions visible to the authenticated actor" is the real contract.
//
// commandId is the caller's Idempotency-Key, unique per tenant schema: a replay of the same key
// looks up this row directly rather than re-dispatching to OpenWorkflow (see
// AgentExecutionServiceImpl.start).
@Entity
@Table(
    name = "agent_execution",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_agent_execution_command_id",
            columnNames = {"command_id"}))
@SequenceGenerator(
    name = "agent_execution_id_generator",
    sequenceName = "agent_execution_id_seq",
    allocationSize = 1)
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentExecutionRecord extends OwnedEntity<Long> {

  private static final long serialVersionUID = 1L;

  @Id
  @GeneratedValue(generator = "agent_execution_id_generator", strategy = GenerationType.SEQUENCE)
  @Column(name = "id")
  private Long id;

  @NotNull
  @Column(name = "command_id", nullable = false, updatable = false)
  private String commandId;

  @NotNull
  @Column(name = "namespace", nullable = false, updatable = false)
  private String namespace;

  @NotNull
  @Column(name = "name", nullable = false, updatable = false)
  private String name;

  @NotNull
  @Column(name = "agent_version", nullable = false, updatable = false)
  private String agentVersion;

  @NotNull
  @Column(name = "workflow_id", nullable = false, updatable = false)
  private UUID workflowId;

  @NotNull
  @Column(name = "workflow_revision_id", nullable = false, updatable = false)
  private UUID workflowRevisionId;

  @NotNull
  @Column(name = "workflow_revision_digest", nullable = false, updatable = false)
  private String workflowRevisionDigest;

  @Column(name = "open_workflow_execution_id")
  private UUID openWorkflowExecutionId;

  @Column(name = "open_workflow_revision")
  private Long openWorkflowRevision;

  @Column(name = "engine_id")
  private String engineId;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "protocol", nullable = false, updatable = false)
  private AgentInvocationProtocol protocol;

  @Column(name = "context_id")
  private String contextId;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false)
  private AgentExecutionState state;

  @NotNull
  @Column(name = "correlation_id", nullable = false, updatable = false)
  private String correlationId;

  @NotNull
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "input", nullable = false, updatable = false)
  private Object input;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "output")
  private Object output;

  @Column(name = "last_failure")
  private String lastFailure;

  @Column(name = "accepted_at")
  private OffsetDateTime acceptedAt;
}
