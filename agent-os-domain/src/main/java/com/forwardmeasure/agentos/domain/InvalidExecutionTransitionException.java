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

// AgentExecutionState's own transition-guard exception - not a reuse of
// InvalidLifecycleTransitionException, which is typed specifically to AgentStatus. The two state
// machines are genuinely different shapes (a jump straight from SUBMITTED/RUNNING to a terminal
// state is real and common for executions; AgentStatus's maker-checker chain is strictly linear),
// so a shared generic exception would buy nothing but a weaker (Object/Enum<?>) signature on both.
public final class InvalidExecutionTransitionException extends RuntimeException {

  private final AgentExecutionState from;
  private final AgentExecutionState to;

  public InvalidExecutionTransitionException(AgentExecutionState from, AgentExecutionState to) {
    super("cannot transition agent execution from " + from + " to " + to);
    this.from = from;
    this.to = to;
  }

  public AgentExecutionState from() {
    return from;
  }

  public AgentExecutionState to() {
    return to;
  }
}
