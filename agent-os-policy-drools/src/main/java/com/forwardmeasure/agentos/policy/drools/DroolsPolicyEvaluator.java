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
package com.forwardmeasure.agentos.policy.drools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.forwardmeasure.agentos.domain.PolicyEvaluationUnavailableException;
import com.forwardmeasure.agentos.domain.PolicyEvaluator;
import com.forwardmeasure.agentos.domain.PolicyNotFoundException;
import com.forwardmeasure.agentos.policy.api.model.PolicyEvaluationRequest;
import com.forwardmeasure.agentos.policy.api.model.PolicyEvaluationResult;
import com.forwardmeasure.agentos.policy.api.model.PolicyOutcome;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationRequest;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationResponse;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationServiceGrpc;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Channel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// Translates the PolicyEvaluator port (agent-os-domain) into forwardmeasure-decision-engine's own
// gRPC contract - EvaluationService.Evaluate(ruleset=policyPath, input={agent, context}) - the
// Drools-based alternative to OpaPolicyEvaluator's REST call to OPA (agent-os-policy-opa). Selected
// at runtime via agent-os.policy.evaluator=drools (PolicyEvaluatorProfile).
//
// This evaluator only ever calls decision-engine in STATELESS mode: PolicyEvaluationRequest has no
// session concept, so session_key is left empty and ruleset_version is left unset (meaning "use
// whichever version is active" - decision-engine's own IMPLEMENTATION-SPEC.md Section 6.1). A
// STATEFUL ruleset called this way is INVALID_ARGUMENT on decision-engine's side, by that project's
// own fail-closed design - not a bug here, a known and deliberate scope limit of this adapter until
// PolicyEvaluator itself grows a session concept.
public final class DroolsPolicyEvaluator implements PolicyEvaluator {

  private final EvaluationServiceGrpc.EvaluationServiceBlockingStub stub;
  private final ObjectMapper mapper;
  private final Duration timeout;

  public DroolsPolicyEvaluator(Channel channel, ObjectMapper mapper, Duration timeout) {
    this.stub = EvaluationServiceGrpc.newBlockingStub(Objects.requireNonNull(channel, "channel"));
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  // target: a gRPC name-resolver target string, e.g. "dns:///decision-engine.decision-engine.svc.
  // cluster.local:9000" or "plaintext.decision-engine:9000" - plain, unauthenticated transport
  // only (usePlaintext). If decision-engine is ever exposed with TLS, add a second constructor
  // rather than branching on a boolean here - mirrors ItinerisManagerClient's own dual-constructor
  // shape (fmgraph/itineris-grpc), the closest precedent for a typed generated-stub gRPC client in
  // this ecosystem.
  public DroolsPolicyEvaluator(String target, ObjectMapper mapper, Duration timeout) {
    this(ManagedChannelBuilder.forTarget(target).usePlaintext().build(), mapper, timeout);
  }

  @Override
  public PolicyEvaluationResult evaluate(PolicyEvaluationRequest request) {
    Objects.requireNonNull(request, "request");
    String policyPath = request.getPolicyPath();
    EvaluationRequest grpcRequest;
    try {
      grpcRequest =
          EvaluationRequest.newBuilder()
              .setRuleset(policyPath)
              .setInput(toInputStruct(request))
              .setCorrelationId(UUID.randomUUID().toString())
              .build();
    } catch (IOException encodingFailure) {
      throw unavailable("could not encode policy evaluation input", encodingFailure);
    }
    EvaluationResponse response;
    try {
      response =
          stub.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS).evaluate(grpcRequest);
    } catch (StatusRuntimeException failure) {
      if (failure.getStatus().getCode() == Status.Code.NOT_FOUND) {
        throw new PolicyNotFoundException(policyPath);
      }
      throw unavailable("decision-engine is unreachable", failure);
    }
    return decisionOf(response.getResult());
  }

  // Same {"agent": ..., "context": ...} shape OpaPolicyEvaluator sends to OPA (its own
  // opaRequestBody) - so the same input can be authored against equivalent Rego and DRL rulesets,
  // making the OPA-vs-Drools comparison the user asked for actually apples-to-apples rather than
  // two adapters that happen to implement the same Java interface but see differently-shaped data.
  private Struct toInputStruct(PolicyEvaluationRequest request) throws IOException {
    ObjectNode input = mapper.createObjectNode();
    input.set("agent", mapper.valueToTree(request.getAgent()));
    input.set("context", mapper.valueToTree(request.getInput()));
    Struct.Builder builder = Struct.newBuilder();
    JsonFormat.parser().merge(mapper.writeValueAsString(input), builder);
    return builder.build();
  }

  // Mirrors OpaPolicyEvaluator.decisionOf(...) exactly - same fail-closed contract, same
  // outcome/reason/obligations shape. decision-engine's own IMPLEMENTATION-SPEC.md Section 7.1
  // deliberately keeps a ruleset's `result` global parallel to Rego's own outcome/reason/
  // obligations bindings for this reason: a malformed result (missing outcome, or a value not in
  // PolicyOutcome's enum) is treated the same as an unavailable engine, never as an implicit
  // permit.
  private PolicyEvaluationResult decisionOf(Struct result) {
    Value outcomeValue = result.getFieldsMap().get("outcome");
    if (outcomeValue == null || outcomeValue.getKindCase() != Value.KindCase.STRING_VALUE) {
      throw unavailable("decision-engine result is missing a valid outcome: " + result, null);
    }
    PolicyOutcome outcome;
    try {
      outcome = PolicyOutcome.fromValue(outcomeValue.getStringValue());
    } catch (IllegalArgumentException invalid) {
      throw unavailable(
          "decision-engine returned an unrecognized outcome: " + outcomeValue.getStringValue(),
          invalid);
    }
    PolicyEvaluationResult decision = new PolicyEvaluationResult(outcome);
    Value reasonValue = result.getFieldsMap().get("reason");
    if (reasonValue != null && reasonValue.getKindCase() == Value.KindCase.STRING_VALUE) {
      decision.reason(reasonValue.getStringValue());
    }
    Value obligationsValue = result.getFieldsMap().get("obligations");
    if (obligationsValue != null && obligationsValue.getKindCase() == Value.KindCase.LIST_VALUE) {
      List<String> obligations = new ArrayList<>();
      obligationsValue
          .getListValue()
          .getValuesList()
          .forEach(
              v -> {
                if (v.getKindCase() == Value.KindCase.STRING_VALUE) {
                  obligations.add(v.getStringValue());
                }
              });
      decision.obligations(obligations);
    }
    return decision;
  }

  private static PolicyEvaluationUnavailableException unavailable(String message, Throwable cause) {
    return new PolicyEvaluationUnavailableException(message, cause);
  }
}
