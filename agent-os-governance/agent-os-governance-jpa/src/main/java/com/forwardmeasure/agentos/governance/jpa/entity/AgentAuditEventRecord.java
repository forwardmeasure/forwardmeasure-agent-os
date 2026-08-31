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

import com.forwardmeasure.agentos.governance.api.model.AgentAuditEvent;
import com.forwardmeasure.jpa.core.entity.AbstractBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
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

// An append-only audit trail row, one per real lifecycle mutation (create/update/submit-for-
// review/return-to-draft/approve/publish/deprecate/archive/delete) - see AgentAuditOperation. The
// full generated AgentAuditEvent is stored as its own JSON payload, exactly as Agent.content
// stores AgentDefinitionContent - not decomposed into individual columns, per the same
// "don't hand-roll the wire shape as something else" reasoning.
//
// agentId is a plain indexed UUID, deliberately not a real foreign key to agent.id: an audit
// trail must survive deleteAgentDraft's real hard delete of its subject row (only draft agents
// are deletable, and DELETED is itself a recorded operation) - a FK would force an impossible
// choice between ON DELETE CASCADE (erasing the very trail that recorded the deletion) and
// ON DELETE RESTRICT (making deletion of an audited agent impossible). occurredAt is duplicated
// as its own column (also present inside payload) purely so listAgentAuditEvents' real "ordered
// oldest first" contract can sort on an indexed column instead of a JSON path expression.
@Entity
@Table(name = "agent_audit_event")
@SequenceGenerator(
    name = "agent_audit_event_id_generator",
    sequenceName = "agent_audit_event_id_seq",
    allocationSize = 1)
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentAuditEventRecord extends AbstractBaseEntity<Long> {

  private static final long serialVersionUID = 1L;

  @Id
  @GeneratedValue(generator = "agent_audit_event_id_generator", strategy = GenerationType.SEQUENCE)
  @Column(name = "id")
  private Long id;

  @NotNull
  @Column(name = "agent_id", nullable = false, updatable = false)
  private UUID agentId;

  @NotNull
  @Column(name = "occurred_at", nullable = false, updatable = false)
  private OffsetDateTime occurredAt;

  @NotNull
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false, updatable = false)
  private AgentAuditEvent payload;
}
