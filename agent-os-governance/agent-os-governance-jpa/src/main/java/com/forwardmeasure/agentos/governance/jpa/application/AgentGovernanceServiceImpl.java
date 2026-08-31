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
package com.forwardmeasure.agentos.governance.jpa.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.forwardmeasure.agentos.domain.ActorReference;
import com.forwardmeasure.agentos.domain.ActorType;
import com.forwardmeasure.agentos.domain.AgentActor;
import com.forwardmeasure.agentos.domain.AgentCoordinates;
import com.forwardmeasure.agentos.domain.AgentDefinition;
import com.forwardmeasure.agentos.domain.AgentStatus;
import com.forwardmeasure.agentos.domain.WorkflowReleaseBinding;
import com.forwardmeasure.agentos.domain.WorkflowReleaseResolver;
import com.forwardmeasure.agentos.governance.api.model.AgentAuditEvent;
import com.forwardmeasure.agentos.governance.api.model.AgentAuditOperation;
import com.forwardmeasure.agentos.governance.api.model.AgentDefinitionContent;
import com.forwardmeasure.agentos.governance.application.AgentGovernanceService;
import com.forwardmeasure.agentos.governance.application.AgentNotDraftException;
import com.forwardmeasure.agentos.governance.application.AgentNotFoundException;
import com.forwardmeasure.agentos.governance.application.DuplicateAgentCoordinatesException;
import com.forwardmeasure.agentos.governance.application.Page;
import com.forwardmeasure.agentos.governance.application.SelfApprovalNotAllowedException;
import com.forwardmeasure.agentos.governance.application.StaleRevisionException;
import com.forwardmeasure.agentos.governance.jpa.entity.Agent;
import com.forwardmeasure.agentos.governance.jpa.entity.AgentAuditEventRecord;
import com.forwardmeasure.agentos.governance.jpa.service.AgentAuditEventService;
import com.forwardmeasure.agentos.governance.jpa.service.AgentService;
import com.forwardmeasure.jpa.core.query.JpaSpecification;
import com.forwardmeasure.jpa.core.query.PageRequest;
import com.forwardmeasure.jpa.identity.entity.IdentityType;
import com.forwardmeasure.jpa.identity.service.ActorService;
import jakarta.transaction.Transactional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

// The sole implementation of AgentGovernanceService (agent-os-governance-application) - see that
// interface's own doc comment for why this module depends on it, not the reverse. Bridges three
// representations per operation: the JPA entity (Agent, this package's own persistence shape), the
// domain aggregate (AgentDefinition, which owns the real lifecycle-transition validation this
// class delegates to rather than re-implementing), and the generated wire audit-event type
// (AgentAuditEvent/AgentAuditOperation, reused directly - see AgentGovernanceService's own doc
// comment on why).
//
// @Transactional here is jakarta.transaction's standard annotation, the same one
// AbstractBaseServiceImpl (forwardmeasure-jpa-core) already carries - both Quarkus and Spring
// recognise it natively on any container-managed bean regardless of how the bean was constructed,
// so no framework-specific wrapping is needed for those two. Micronaut is the one exception: its
// AOP interception requires compile-time bytecode weaving that a class built in this
// framework-neutral module never receives, so agent-os-governance-micronaut wraps this class in
// MicronautTransactionalServiceProxy instead of relying on this annotation - see that module's own
// wiring for why. Not final, so a framework binding may subclass it if it ever needs to (Spring's
// default proxy-target-class CGLIB strategy requires this).
@Transactional
public class AgentGovernanceServiceImpl implements AgentGovernanceService {

  private static final ObjectMapper CANONICAL_MAPPER =
      new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  private final AgentService agents;
  private final AgentAuditEventService auditEvents;
  private final ActorService actors;
  private final WorkflowReleaseResolver workflowReleases;

  public AgentGovernanceServiceImpl(
      AgentService agents,
      AgentAuditEventService auditEvents,
      ActorService actors,
      WorkflowReleaseResolver workflowReleases) {
    this.agents = Objects.requireNonNull(agents, "agents");
    this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents");
    this.actors = Objects.requireNonNull(actors, "actors");
    this.workflowReleases = Objects.requireNonNull(workflowReleases, "workflowReleases");
  }

  @Override
  public AgentDefinition createDraft(
      AgentActor caller, AgentCoordinates coordinates, AgentDefinitionContent content) {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(coordinates, "coordinates");
    Objects.requireNonNull(content, "content");
    if (agents
        .findByCoordinates(coordinates.namespace(), coordinates.name(), coordinates.version())
        .isPresent()) {
      throw new DuplicateAgentCoordinatesException(coordinates);
    }
    com.forwardmeasure.jpa.identity.entity.Actor owner = loadActorEntity(caller.actor());
    Agent entity =
        Agent.builder()
            .namespace(coordinates.namespace())
            .name(coordinates.name())
            .agentVersion(coordinates.version())
            .status(AgentStatus.DRAFT)
            .content(content)
            .owner(owner)
            .build();
    agents.persist(entity);
    agents.flush();
    recordAuditEvent(
        entity.getUuid(),
        AgentAuditOperation.CREATED,
        null,
        AgentStatus.DRAFT,
        caller.actor(),
        UUID.randomUUID().toString(),
        null,
        null,
        entity.getCreatedAt());
    return toDomain(entity);
  }

  @Override
  public AgentDefinition getAgent(AgentActor caller, UUID agentId) {
    return toDomain(loadEntity(agentId));
  }

  @Override
  public Page<AgentDefinition> listAgents(
      AgentActor caller, int offset, int limit, AgentStatus statusFilter) {
    JpaSpecification<Agent> specification =
        statusFilter == null
            ? null
            : (root, query, builder) -> builder.equal(root.get("status"), statusFilter);
    var jpaPage = agents.page(new PageRequest(offset, limit, List.of()), specification);
    return new Page<>(
        jpaPage.items().stream().map(this::toDomain).toList(),
        jpaPage.totalItems(),
        jpaPage.offset(),
        jpaPage.limit());
  }

  @Override
  public AgentDefinition updateDraft(
      AgentActor caller, UUID agentId, int expectedRevision, AgentDefinitionContent content) {
    Agent entity = loadEntity(agentId);
    checkRevision(agentId, entity, expectedRevision);
    toDomain(entity).updateContent(content, Instant.now());
    entity.setContent(content);
    agents.flush();
    recordAuditEvent(
        agentId,
        AgentAuditOperation.UPDATED,
        null,
        entity.getStatus(),
        caller.actor(),
        UUID.randomUUID().toString(),
        null,
        null,
        entity.getUpdatedAt());
    return toDomain(entity);
  }

  @Override
  public void deleteDraft(AgentActor caller, UUID agentId, int expectedRevision) {
    Agent entity = loadEntity(agentId);
    checkRevision(agentId, entity, expectedRevision);
    if (entity.getStatus() != AgentStatus.DRAFT) {
      throw new AgentNotDraftException(agentId, entity.getStatus());
    }
    recordAuditEvent(
        agentId,
        AgentAuditOperation.DELETED,
        null,
        AgentStatus.DRAFT,
        caller.actor(),
        UUID.randomUUID().toString(),
        null,
        null,
        OffsetDateTime.now(ZoneOffset.UTC));
    agents.delete(entity);
  }

  @Override
  public AgentDefinition submitForReview(
      AgentActor caller, UUID agentId, int expectedRevision, String reason) {
    Agent entity = loadEntity(agentId);
    checkRevision(agentId, entity, expectedRevision);
    AgentStatus from = entity.getStatus();
    AgentDefinition next = toDomain(entity).submitForReview(Instant.now());
    entity.setStatus(next.status());
    agents.flush();
    recordAuditEvent(
        agentId,
        AgentAuditOperation.REVIEW_SUBMITTED,
        from,
        next.status(),
        caller.actor(),
        UUID.randomUUID().toString(),
        null,
        reason,
        entity.getUpdatedAt());
    return toDomain(entity);
  }

  @Override
  public AgentDefinition returnToDraft(
      AgentActor caller, UUID agentId, int expectedRevision, String reason) {
    Agent entity = loadEntity(agentId);
    checkRevision(agentId, entity, expectedRevision);
    AgentStatus from = entity.getStatus();
    AgentDefinition next = toDomain(entity).returnToDraft(Instant.now());
    entity.setStatus(next.status());
    agents.flush();
    recordAuditEvent(
        agentId,
        AgentAuditOperation.RETURNED_TO_DRAFT,
        from,
        next.status(),
        caller.actor(),
        UUID.randomUUID().toString(),
        null,
        reason,
        entity.getUpdatedAt());
    return toDomain(entity);
  }

  @Override
  public AgentDefinition approve(
      AgentActor caller, UUID agentId, int expectedRevision, String reason) {
    Agent entity = loadEntity(agentId);
    checkRevision(agentId, entity, expectedRevision);
    if (caller.actor().id().equals(entity.getOwner().getUuid())) {
      throw new SelfApprovalNotAllowedException(agentId);
    }
    AgentStatus from = entity.getStatus();
    AgentDefinition next = toDomain(entity).approve(caller.actor(), Instant.now());
    entity.setStatus(next.status());
    entity.setReviewer(loadActorEntity(caller.actor()));
    agents.flush();
    recordAuditEvent(
        agentId,
        AgentAuditOperation.APPROVED,
        from,
        next.status(),
        caller.actor(),
        UUID.randomUUID().toString(),
        null,
        reason,
        entity.getUpdatedAt());
    return toDomain(entity);
  }

  @Override
  public AgentDefinition publish(
      AgentActor caller,
      UUID agentId,
      int expectedRevision,
      UUID workflowId,
      UUID workflowRevisionId,
      String correlationId) {
    Agent entity = loadEntity(agentId);
    checkRevision(agentId, entity, expectedRevision);
    AgentStatus from = entity.getStatus();
    WorkflowReleaseBinding binding =
        workflowReleases.resolvePublishedRevision(workflowId, workflowRevisionId);
    String definitionSha256 = computeSha256(entity.getContent());
    AgentDefinition next = toDomain(entity).publish(binding, definitionSha256, Instant.now());
    entity.setStatus(next.status());
    entity.setWorkflowId(binding.workflowId());
    entity.setWorkflowRevisionId(binding.revisionId());
    entity.setWorkflowRevisionDigest(binding.revisionDigest());
    entity.setAgentDefinitionSha256(definitionSha256);
    entity.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
    agents.flush();
    recordAuditEvent(
        agentId,
        AgentAuditOperation.PUBLISHED,
        from,
        next.status(),
        caller.actor(),
        correlationId,
        definitionSha256,
        null,
        entity.getUpdatedAt());
    return toDomain(entity);
  }

  @Override
  public AgentDefinition deprecate(
      AgentActor caller, UUID agentId, int expectedRevision, String reason) {
    Agent entity = loadEntity(agentId);
    checkRevision(agentId, entity, expectedRevision);
    AgentStatus from = entity.getStatus();
    AgentDefinition next = toDomain(entity).deprecate(Instant.now());
    entity.setStatus(next.status());
    entity.setDeprecatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    agents.flush();
    recordAuditEvent(
        agentId,
        AgentAuditOperation.DEPRECATED,
        from,
        next.status(),
        caller.actor(),
        UUID.randomUUID().toString(),
        null,
        reason,
        entity.getUpdatedAt());
    return toDomain(entity);
  }

  @Override
  public AgentDefinition archive(
      AgentActor caller, UUID agentId, int expectedRevision, String reason) {
    Agent entity = loadEntity(agentId);
    checkRevision(agentId, entity, expectedRevision);
    AgentStatus from = entity.getStatus();
    AgentDefinition next = toDomain(entity).archive(Instant.now());
    entity.setStatus(next.status());
    entity.setArchivedAt(OffsetDateTime.now(ZoneOffset.UTC));
    agents.flush();
    recordAuditEvent(
        agentId,
        AgentAuditOperation.ARCHIVED,
        from,
        next.status(),
        caller.actor(),
        UUID.randomUUID().toString(),
        null,
        reason,
        entity.getUpdatedAt());
    return toDomain(entity);
  }

  @Override
  public Page<AgentAuditEvent> listAuditEvents(
      AgentActor caller, UUID agentId, int offset, int limit) {
    loadEntity(agentId);
    var jpaPage = auditEvents.findByAgentId(agentId, offset, limit);
    return new Page<>(
        jpaPage.items().stream().map(AgentAuditEventRecord::getPayload).toList(),
        jpaPage.totalItems(),
        jpaPage.offset(),
        jpaPage.limit());
  }

  @Override
  public Page<AgentDefinition> listReleases(AgentActor caller, int offset, int limit) {
    JpaSpecification<Agent> specification =
        (root, query, builder) -> builder.equal(root.get("status"), AgentStatus.PUBLISHED);
    var jpaPage = agents.page(new PageRequest(offset, limit, List.of()), specification);
    return new Page<>(
        jpaPage.items().stream().map(this::toDomain).toList(),
        jpaPage.totalItems(),
        jpaPage.offset(),
        jpaPage.limit());
  }

  @Override
  public AgentDefinition getRelease(AgentActor caller, AgentCoordinates coordinates) {
    Agent entity =
        agents
            .findAvailableRelease(
                coordinates.namespace(), coordinates.name(), coordinates.version())
            .orElseThrow(
                () ->
                    new AgentNotFoundException(
                        coordinates.namespace(), coordinates.name(), coordinates.version()));
    return toDomain(entity);
  }

  private Agent loadEntity(UUID agentId) {
    Objects.requireNonNull(agentId, "agentId");
    return agents.findByUuid(agentId).orElseThrow(() -> new AgentNotFoundException(agentId));
  }

  private void checkRevision(UUID agentId, Agent entity, int expectedRevision) {
    int actual = entity.getVersion();
    if (actual != expectedRevision) {
      throw new StaleRevisionException(agentId, expectedRevision, actual);
    }
  }

  private com.forwardmeasure.jpa.identity.entity.Actor loadActorEntity(ActorReference reference) {
    return actors
        .findByUuid(reference.id())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "no Actor row for "
                        + reference.id()
                        + " - AgentActorResolver already"
                        + " resolved this subject earlier in the same request"));
  }

  private AgentDefinition toDomain(Agent entity) {
    WorkflowReleaseBinding binding =
        entity.getWorkflowId() == null
            ? null
            : new WorkflowReleaseBinding(
                entity.getWorkflowId(),
                entity.getWorkflowRevisionId(),
                entity.getWorkflowRevisionDigest());
    return new AgentDefinition(
        entity.getUuid(),
        entity.getVersion(),
        new AgentCoordinates(entity.getNamespace(), entity.getName(), entity.getAgentVersion()),
        entity.getStatus(),
        entity.getContent(),
        toDomainActor(entity.getOwner()),
        entity.getReviewer() == null ? null : toDomainActor(entity.getReviewer()),
        binding,
        entity.getAgentDefinitionSha256(),
        entity.getCreatedAt().toInstant(),
        entity.getUpdatedAt().toInstant(),
        entity.getPublishedAt() == null ? null : entity.getPublishedAt().toInstant(),
        entity.getDeprecatedAt() == null ? null : entity.getDeprecatedAt().toInstant(),
        entity.getArchivedAt() == null ? null : entity.getArchivedAt().toInstant());
  }

  private static ActorReference toDomainActor(com.forwardmeasure.jpa.identity.entity.Actor actor) {
    return new ActorReference(
        actor.getUuid(),
        actor.getSubjectIdentifier(),
        actor.getType() == IdentityType.HUMAN ? ActorType.HUMAN : ActorType.SERVICE,
        actor.getEmail());
  }

  private static com.forwardmeasure.agentos.governance.api.model.ActorReference toWireActor(
      ActorReference actor) {
    var wireType =
        actor.type() == ActorType.HUMAN
            ? com.forwardmeasure.agentos.governance.api.model.ActorReference.TypeEnum.HUMAN
            : com.forwardmeasure.agentos.governance.api.model.ActorReference.TypeEnum.SERVICE;
    return new com.forwardmeasure.agentos.governance.api.model.ActorReference(
            actor.id(), actor.subject(), wireType)
        .displayName(actor.displayName());
  }

  private void recordAuditEvent(
      UUID agentId,
      AgentAuditOperation operation,
      AgentStatus from,
      AgentStatus to,
      ActorReference actor,
      String correlationId,
      String definitionSha256,
      String details,
      OffsetDateTime occurredAt) {
    AgentAuditEvent payload =
        new AgentAuditEvent(
            UUID.randomUUID(),
            agentId,
            operation,
            toWireStatus(to),
            toWireActor(actor),
            correlationId,
            Date.from(occurredAt.toInstant()));
    if (from != null) {
      payload.fromStatus(toWireStatus(from));
    }
    if (definitionSha256 != null) {
      payload.definitionSha256(definitionSha256);
    }
    if (details != null) {
      payload.details(details);
    }
    auditEvents.persist(
        AgentAuditEventRecord.builder()
            .agentId(agentId)
            .occurredAt(occurredAt)
            .payload(payload)
            .build());
  }

  private static com.forwardmeasure.agentos.governance.api.model.AgentStatus toWireStatus(
      AgentStatus status) {
    return com.forwardmeasure.agentos.governance.api.model.AgentStatus.fromValue(
        status.wireValue());
  }

  private static String computeSha256(AgentDefinitionContent content) {
    try {
      byte[] bytes = CANONICAL_MAPPER.writeValueAsBytes(content);
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      return toHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    } catch (Exception e) {
      throw new IllegalStateException("failed to compute the agent definition digest", e);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder hex = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16));
      hex.append(Character.forDigit(b & 0xF, 16));
    }
    return hex.toString();
  }
}
