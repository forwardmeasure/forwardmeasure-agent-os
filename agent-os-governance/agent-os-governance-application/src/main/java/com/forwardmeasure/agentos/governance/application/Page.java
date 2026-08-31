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
package com.forwardmeasure.agentos.governance.application;

import java.util.List;
import java.util.Objects;

// The application-service-level pagination envelope every AgentGovernanceService list operation
// returns. Deliberately not forwardmeasure-jpa's own Page<T> (that type is
// com.forwardmeasure.jpa..-scoped, which agent-os-governance-application must stay free of - it
// depends only on agent-os-domain) and not the generated wire Paging (that is
// agent-os-governance-jaxrs's job to build from this, matching the response envelope
// common-definitions.yaml actually defines).
public record Page<T>(List<T> items, long totalCount, int offset, int limit) {

  public Page {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    if (totalCount < 0) {
      throw new IllegalArgumentException("totalCount must be non-negative");
    }
    if (offset < 0) {
      throw new IllegalArgumentException("offset must be non-negative");
    }
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive");
    }
  }

  public boolean isLastPage() {
    return offset + (long) items.size() >= totalCount;
  }
}
