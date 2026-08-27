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

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

// Immutable binding to one exact, published, non-deprecated OpenWorkflow revision - never a
// namespace/name/version coordinate, never an engine choice. Field naming deliberately matches
// OpenWorkflow's own Execution.workflowId/revisionId/revisionDigest, verified against
// common-definitions.yaml's WorkflowReleaseBinding schema and single-sourced here per WP2.
public record WorkflowReleaseBinding(UUID workflowId, UUID revisionId, String revisionDigest) {

  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

  public WorkflowReleaseBinding {
    Objects.requireNonNull(workflowId, "workflowId");
    Objects.requireNonNull(revisionId, "revisionId");
    Objects.requireNonNull(revisionDigest, "revisionDigest");
    if (!DIGEST.matcher(revisionDigest).matches()) {
      throw new IllegalArgumentException(
          "revisionDigest does not match required pattern: " + revisionDigest);
    }
  }
}
