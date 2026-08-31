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

import com.forwardmeasure.agentos.policy.api.model.PolicyEvaluationRequest;
import com.forwardmeasure.agentos.policy.api.model.PolicyEvaluationResult;

// A governed call-out to an external policy engine (Open Policy Agent - see agent-os-policy-opa's
// own OpaPolicyEvaluator) that decides whether a proposed action is permitted, rejected, or
// requires human approval. Lives here, not in a single vertical's own application module, for the
// same reason WorkflowExecutionDispatcher and WorkflowReleaseResolver do: any vertical - execution
// gating a proposed agent action, governance gating a publish, a future caller not yet built - can
// depend on this one port without depending on another vertical's internals.
//
// Request and result are the real, generated agent-policy-management wire types, reused directly
// (not a hand-rolled parallel domain type) - PolicyEvaluationRequest/Result are flat data carriers
// with no independent domain behavior, exactly like AgentExecutionHistoryEntry.
public interface PolicyEvaluator {

  PolicyEvaluationResult evaluate(PolicyEvaluationRequest request);
}
