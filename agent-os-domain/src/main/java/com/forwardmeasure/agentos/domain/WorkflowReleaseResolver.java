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
package com.forwardmeasure.agentos.domain;

import java.util.UUID;

// Validates a caller-supplied (workflowId, revisionId) pair against OpenWorkflow's own
// definition-management API and resolves the revision's immutable content digest - the digest is
// never caller-supplied (agent-governance-management.openapi.yaml's AgentPublishRequest
// deliberately omits it; WorkflowReleaseBinding.revisionDigest is always server-resolved). Lives
// here, not in agent-os-governance-application, so agent-os-openworkflow-client - which must stay
// usable by both the governance and execution verticals - implements it without depending on
// either vertical's application module. The DomainNeutralityArchitectureTest rules that block
// agent-os-domain from depending on com.forwardmeasure.openworkflow.. mean this interface itself
// must stay free of any OpenWorkflow client type; only the implementation in
// agent-os-openworkflow-client may reference those.
public interface WorkflowReleaseResolver {

  WorkflowReleaseBinding resolvePublishedRevision(UUID workflowId, UUID revisionId);
}
