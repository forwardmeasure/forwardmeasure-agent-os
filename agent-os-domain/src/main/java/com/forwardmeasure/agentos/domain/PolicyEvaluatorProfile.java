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

// Which PolicyEvaluator implementation a deployment wires up, selected via the
// agent-os.policy.evaluator config property. Mirrors openworkflow-actor-engine's own
// PersistenceProfile enum/parse convention (openworkflow-persistence-core) - the closest existing
// precedent in this ecosystem for "one port, config-selected implementation". Lives here, not in
// agent-os-policy-opa or a future agent-os-policy-drools, so every framework binding can depend on
// one small, neutral type instead of depending on both candidate implementation modules just to
// read a config value.
public enum PolicyEvaluatorProfile {
  OPA("opa"),
  DROOLS("drools");

  private final String value;

  PolicyEvaluatorProfile(String value) {
    this.value = value;
  }

  public static PolicyEvaluatorProfile parse(String value) {
    for (PolicyEvaluatorProfile profile : values()) {
      if (profile.value.equals(value)) {
        return profile;
      }
    }
    throw new IllegalArgumentException("unrecognized agent-os.policy.evaluator value: " + value);
  }
}
