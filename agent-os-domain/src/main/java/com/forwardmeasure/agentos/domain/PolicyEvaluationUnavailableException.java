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

// The policy engine could not be reached, or returned a response this port could not interpret as
// a decision. Fail closed: every caller of PolicyEvaluator must treat this the same as a rejected
// evaluation, never as an implicit permit - "I could not get a decision" is not "the decision was
// yes".
public final class PolicyEvaluationUnavailableException extends RuntimeException {

  public PolicyEvaluationUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
