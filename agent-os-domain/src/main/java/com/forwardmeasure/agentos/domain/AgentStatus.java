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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// The maker-checker lifecycle, verified against agent-governance-management.openapi.yaml's real
// path operations (submit-for-review/return-to-draft/approve/publish/deprecate/archive) rather
// than reconstructed from prose alone. return-to-draft is the sole recovery path from IN_REVIEW -
// there is no separate terminal-rejected status - and no transition skips a step.
public enum AgentStatus {
  DRAFT("draft"),
  IN_REVIEW("in_review"),
  APPROVED("approved"),
  PUBLISHED("published"),
  DEPRECATED("deprecated"),
  ARCHIVED("archived");

  private static final Map<AgentStatus, Set<AgentStatus>> VALID_TRANSITIONS =
      new EnumMap<>(AgentStatus.class);

  static {
    VALID_TRANSITIONS.put(DRAFT, EnumSet.of(IN_REVIEW));
    VALID_TRANSITIONS.put(IN_REVIEW, EnumSet.of(DRAFT, APPROVED));
    VALID_TRANSITIONS.put(APPROVED, EnumSet.of(PUBLISHED));
    VALID_TRANSITIONS.put(PUBLISHED, EnumSet.of(DEPRECATED));
    VALID_TRANSITIONS.put(DEPRECATED, EnumSet.of(ARCHIVED));
    VALID_TRANSITIONS.put(ARCHIVED, EnumSet.noneOf(AgentStatus.class));
  }

  private static final Map<String, AgentStatus> BY_WIRE_VALUE =
      Arrays.stream(values()).collect(Collectors.toMap(s -> s.wireValue, s -> s));

  private final String wireValue;

  AgentStatus(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static AgentStatus fromWireValue(String wireValue) {
    AgentStatus match = BY_WIRE_VALUE.get(wireValue);
    if (match == null) {
      throw new IllegalArgumentException("unknown AgentStatus wire value: " + wireValue);
    }
    return match;
  }

  public boolean canTransitionTo(AgentStatus target) {
    return VALID_TRANSITIONS.get(this).contains(target);
  }

  public void requireTransitionTo(AgentStatus target) {
    if (!canTransitionTo(target)) {
      throw new InvalidLifecycleTransitionException(this, target);
    }
  }
}
