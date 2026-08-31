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

// No policy is loaded at the given policyPath - a caller/configuration error, not a decision.
// Deliberately distinct from a REJECTED PolicyEvaluationResult: a real, loaded policy that
// evaluates its input to "reject" is a normal, expected outcome; an unknown policyPath means
// nothing evaluated at all, and must never be silently treated as either permit or reject.
public final class PolicyNotFoundException extends RuntimeException {

  private final String policyPath;

  public PolicyNotFoundException(String policyPath) {
    super("no policy is loaded at path: " + policyPath);
    this.policyPath = policyPath;
  }

  public String policyPath() {
    return policyPath;
  }
}
