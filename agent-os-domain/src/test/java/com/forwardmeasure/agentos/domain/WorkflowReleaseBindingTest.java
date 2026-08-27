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

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowReleaseBindingTest {

  @Test
  void acceptsAValidDigest() {
    new WorkflowReleaseBinding(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64));
  }

  // revisionDigest pattern: ^[0-9a-f]{64}$, verified against common-definitions.yaml's
  // WorkflowReleaseBinding schema.
  @Test
  void rejectsAMalformedDigest() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkflowReleaseBinding(UUID.randomUUID(), UUID.randomUUID(), "not-a-digest"));
  }
}
