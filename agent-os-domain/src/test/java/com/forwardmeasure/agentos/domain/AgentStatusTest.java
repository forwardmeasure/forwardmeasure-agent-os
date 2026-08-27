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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AgentStatusTest {

  // The exact real transition set, verified against agent-governance-management.openapi.yaml's
  // submit-for-review/return-to-draft/approve/publish/deprecate/archive path operations.
  @Test
  void validTransitionsMatchTheRealPathOperationsExactly() {
    assertEquals(EnumSet.of(AgentStatus.IN_REVIEW), transitionsFrom(AgentStatus.DRAFT));
    assertEquals(
        EnumSet.of(AgentStatus.DRAFT, AgentStatus.APPROVED),
        transitionsFrom(AgentStatus.IN_REVIEW));
    assertEquals(EnumSet.of(AgentStatus.PUBLISHED), transitionsFrom(AgentStatus.APPROVED));
    assertEquals(EnumSet.of(AgentStatus.DEPRECATED), transitionsFrom(AgentStatus.PUBLISHED));
    assertEquals(EnumSet.of(AgentStatus.ARCHIVED), transitionsFrom(AgentStatus.DEPRECATED));
  }

  @Test
  void archivedIsTerminal() {
    assertTrue(transitionsFrom(AgentStatus.ARCHIVED).isEmpty());
  }

  @Test
  void noTransitionSkipsAStep() {
    assertFalse(AgentStatus.DRAFT.canTransitionTo(AgentStatus.APPROVED));
    assertFalse(AgentStatus.DRAFT.canTransitionTo(AgentStatus.PUBLISHED));
    assertFalse(AgentStatus.APPROVED.canTransitionTo(AgentStatus.DEPRECATED));
    assertFalse(AgentStatus.PUBLISHED.canTransitionTo(AgentStatus.ARCHIVED));
  }

  @Test
  void requireTransitionToThrowsOnAnInvalidTransition() {
    InvalidLifecycleTransitionException ex =
        assertThrows(
            InvalidLifecycleTransitionException.class,
            () -> AgentStatus.DRAFT.requireTransitionTo(AgentStatus.PUBLISHED));
    assertEquals(AgentStatus.DRAFT, ex.from());
    assertEquals(AgentStatus.PUBLISHED, ex.to());
  }

  @ParameterizedTest
  @EnumSource(AgentStatus.class)
  void wireValueRoundTrips(AgentStatus status) {
    assertEquals(status, AgentStatus.fromWireValue(status.wireValue()));
  }

  // The exact wire values verified against agent-governance-management.openapi.yaml's
  // AgentStatus enum, not assumed.
  @Test
  void wireValuesMatchTheVerifiedSpecExactly() {
    assertEquals("draft", AgentStatus.DRAFT.wireValue());
    assertEquals("in_review", AgentStatus.IN_REVIEW.wireValue());
    assertEquals("approved", AgentStatus.APPROVED.wireValue());
    assertEquals("published", AgentStatus.PUBLISHED.wireValue());
    assertEquals("deprecated", AgentStatus.DEPRECATED.wireValue());
    assertEquals("archived", AgentStatus.ARCHIVED.wireValue());
  }

  private static Set<AgentStatus> transitionsFrom(AgentStatus status) {
    Set<AgentStatus> reachable = EnumSet.noneOf(AgentStatus.class);
    for (AgentStatus candidate : AgentStatus.values()) {
      if (status.canTransitionTo(candidate)) {
        reachable.add(candidate);
      }
    }
    return reachable;
  }
}
