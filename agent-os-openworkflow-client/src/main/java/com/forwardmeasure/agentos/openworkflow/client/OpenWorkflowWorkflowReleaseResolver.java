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
package com.forwardmeasure.agentos.openworkflow.client;

import com.forwardmeasure.agentos.domain.WorkflowReleaseBinding;
import com.forwardmeasure.agentos.domain.WorkflowReleaseResolver;
import com.forwardmeasure.agentos.domain.WorkflowReleaseUnavailableException;
import com.forwardmeasure.openworkflow.authorization.authzen.BearerTokenSupplier;
import com.forwardmeasure.openworkflow.definition.management.api.model.WorkflowDefinition;
import com.forwardmeasure.openworkflow.definition.management.api.model.WorkflowDefinitionStatus;
import com.forwardmeasure.openworkflow.definition.management.client.ApiClient;
import com.forwardmeasure.openworkflow.definition.management.client.ApiException;
import com.forwardmeasure.openworkflow.definition.management.client.api.WorkflowDefinitionsApi;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;

// Wraps OpenWorkflow's generated public definition-management ApacheHttp client behind
// agent-os-domain's WorkflowReleaseResolver port - see that interface's own doc comment for why
// it lives in agent-os-domain rather than agent-os-governance-application. Authenticates as
// agent-os's own service identity (OAuth2 client-credentials via the already-real, already-cached
// OAuthClientCredentialsTokenSupplier from openworkflow-authorization-authzen - reused rather than
// hand-rolling a second token fetcher) rather than forwarding the end user's own bearer token:
// AgentActorResolver's contract deliberately reduces the caller down to
// AgentActor(ActorReference, tenantId) with no raw token retained, and that contract was settled
// deliberately earlier in this effort - this class doesn't reopen it. The publish check itself
// ("is this workflow revision published and non-deprecated") is a factual query, not one that
// needs to be scoped to the calling user's own OpenWorkflow permissions.
//
// Mirrors forwardmeasure-agents' now-superseded OpenWorkflowRevisionClient (same generated client
// classes, same ApiClient.setBearerToken/getWorkflowDefinition/ApiException.getCode() shape) - not
// invented fresh. Differs from it in one real way: agent-governance-management.openapi.yaml's
// AgentPublishRequest never accepts a caller-supplied digest (WorkflowReleaseBinding.revisionDigest
// is always server-resolved here, taken directly from OpenWorkflow's response), so there is no
// caller-supplied digest to verify against.
public final class OpenWorkflowWorkflowReleaseResolver implements WorkflowReleaseResolver {

  private final URI endpoint;
  private final BearerTokenSupplier tokens;

  public OpenWorkflowWorkflowReleaseResolver(URI endpoint, BearerTokenSupplier tokens) {
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    this.tokens = Objects.requireNonNull(tokens, "tokens");
  }

  @Override
  public WorkflowReleaseBinding resolvePublishedRevision(UUID workflowId, UUID revisionId) {
    Objects.requireNonNull(workflowId, "workflowId");
    Objects.requireNonNull(revisionId, "revisionId");
    ApiClient client =
        new ApiClient().setBasePath(endpoint.toString()).setBearerToken(tokens.bearerToken());
    try {
      WorkflowDefinition revision =
          new WorkflowDefinitionsApi(client).getWorkflowDefinition(workflowId, revisionId);
      if (!workflowId.equals(revision.getWorkflowId()) || !revisionId.equals(revision.getId())) {
        throw new WorkflowReleaseUnavailableException(
            workflowId, revisionId, "OpenWorkflow returned a different revision identity");
      }
      if (revision.getStatus() != WorkflowDefinitionStatus.PUBLISHED) {
        throw new WorkflowReleaseUnavailableException(
            workflowId,
            revisionId,
            "not a published, non-deprecated definition (status=" + revision.getStatus() + ")");
      }
      return new WorkflowReleaseBinding(workflowId, revisionId, revision.getDefinitionSha256());
    } catch (ApiException failure) {
      if (failure.getCode() == 404) {
        throw new WorkflowReleaseUnavailableException(
            workflowId, revisionId, "no such OpenWorkflow revision");
      }
      throw new IllegalStateException(
          "OpenWorkflow definition-management call failed with HTTP " + failure.getCode(), failure);
    }
  }
}
