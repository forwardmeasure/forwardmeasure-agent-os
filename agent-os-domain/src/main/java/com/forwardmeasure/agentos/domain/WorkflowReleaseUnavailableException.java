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

// Thrown by WorkflowReleaseResolver when the referenced OpenWorkflow revision does not exist, or
// exists but is not a published, non-deprecated definition - the exact condition
// publishAgent's real 404 response documents ("The agent does not exist, or the referenced
// OpenWorkflow revision is not a published, non-deprecated definition").
public final class WorkflowReleaseUnavailableException extends RuntimeException {

  private final UUID workflowId;
  private final UUID revisionId;

  public WorkflowReleaseUnavailableException(UUID workflowId, UUID revisionId, String reason) {
    super(
        "OpenWorkflow revision "
            + revisionId
            + " of workflow "
            + workflowId
            + " is not available to bind: "
            + reason);
    this.workflowId = workflowId;
    this.revisionId = revisionId;
  }

  public UUID workflowId() {
    return workflowId;
  }

  public UUID revisionId() {
    return revisionId;
  }
}
