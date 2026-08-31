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

// The six wire-level states agent-governance-management.openapi.yaml's real AgentExecutionState
// documents: "wire-level state only... callers only ever observe this single state." OpenWorkflow's
// own Execution has nine (NEW/RUNNING/WAITING/PAUSING/PAUSED/CANCELLING/CANCELLED/COMPLETED/FAILED)
// - agent-os-openworkflow-client's mapper collapses the in-flight transient states
// (WAITING->RUNNING, PAUSING->RUNNING, CANCELLING->RUNNING) into the state they're transitioning
// away from, matching OpenWorkflow's own real "acknowledgement means the transition is durable,
// not that it has completed" semantics - a PAUSING execution is still running until it actually
// reaches PAUSED.
public enum AgentExecutionState {
  SUBMITTED("submitted"),
  RUNNING("running"),
  PAUSED("paused"),
  COMPLETED("completed"),
  FAILED("failed"),
  CANCELLED("cancelled");

  private static final Map<AgentExecutionState, Set<AgentExecutionState>> VALID_TRANSITIONS =
      new EnumMap<>(AgentExecutionState.class);

  static {
    VALID_TRANSITIONS.put(SUBMITTED, EnumSet.of(RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED));
    VALID_TRANSITIONS.put(RUNNING, EnumSet.of(PAUSED, COMPLETED, FAILED, CANCELLED));
    VALID_TRANSITIONS.put(PAUSED, EnumSet.of(RUNNING, CANCELLED));
    VALID_TRANSITIONS.put(COMPLETED, EnumSet.noneOf(AgentExecutionState.class));
    VALID_TRANSITIONS.put(FAILED, EnumSet.noneOf(AgentExecutionState.class));
    VALID_TRANSITIONS.put(CANCELLED, EnumSet.noneOf(AgentExecutionState.class));
  }

  private static final Map<String, AgentExecutionState> BY_WIRE_VALUE =
      Arrays.stream(values()).collect(Collectors.toMap(s -> s.wireValue, s -> s));

  private final String wireValue;

  AgentExecutionState(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static AgentExecutionState fromWireValue(String wireValue) {
    AgentExecutionState match = BY_WIRE_VALUE.get(wireValue);
    if (match == null) {
      throw new IllegalArgumentException("unknown AgentExecutionState wire value: " + wireValue);
    }
    return match;
  }

  public boolean canTransitionTo(AgentExecutionState target) {
    return VALID_TRANSITIONS.get(this).contains(target);
  }

  public void requireTransitionTo(AgentExecutionState target) {
    if (!canTransitionTo(target)) {
      throw new InvalidExecutionTransitionException(this, target);
    }
  }
}
