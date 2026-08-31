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
package com.forwardmeasure.agentos.execution.application;

import java.util.UUID;

// The supplied If-Match value does not identify the current revision - pause/resume/cancel's real
// 412 response, checked against agent-os's own persisted revision before any OpenWorkflow call is
// attempted.
public final class StaleExecutionRevisionException extends RuntimeException {

  public StaleExecutionRevisionException(
      UUID executionId, long suppliedRevision, long actualRevision) {
    super(
        "If-Match revision "
            + suppliedRevision
            + " does not match the current revision "
            + actualRevision
            + " for agent execution "
            + executionId);
  }
}
