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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.agentos.domain.PolicyEvaluationUnavailableException;
import com.forwardmeasure.agentos.domain.PolicyNotFoundException;
import com.forwardmeasure.agentos.policy.api.model.AgentCoordinates;
import com.forwardmeasure.agentos.policy.api.model.PolicyEvaluationRequest;
import com.forwardmeasure.agentos.policy.api.model.PolicyEvaluationResult;
import com.forwardmeasure.agentos.policy.api.model.PolicyOutcome;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationRequest;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationResponse;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationServiceGrpc;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// A real in-process gRPC round trip against a stub EvaluationService - mirrors
// OpaPolicyEvaluatorTest's own com.sun.net.httpserver.HttpServer stub-server approach exactly
// (agent-os-policy-opa), just for gRPC transport instead of REST: no mocking framework, this
// exercises DroolsPolicyEvaluator's actual request/response wire handling.
class DroolsPolicyEvaluatorTest {

  private Server server;
  private ManagedChannel channel;
  private final AtomicReference<EvaluationRequest> lastRequest = new AtomicReference<>();
  private volatile EvaluationResponse nextResponse;
  private volatile Status nextErrorStatus;

  @BeforeEach
  void startServer() throws IOException {
    String serverName = "drools-policy-evaluator-test-" + UUID.randomUUID();
    server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(new StubEvaluationService())
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    nextErrorStatus = null;
    nextResponse = null;
  }

  @AfterEach
  void stopServer() {
    channel.shutdownNow();
    server.shutdownNow();
  }

  @Test
  void permittedDecisionRoundTrips() {
    nextResponse =
        EvaluationResponse.newBuilder()
            .setResult(
                Struct.newBuilder()
                    .putFields("outcome", stringValue("permitted"))
                    .putFields("reason", stringValue("ok"))
                    .putFields(
                        "obligations",
                        Value.newBuilder()
                            .setListValue(
                                com.google.protobuf.ListValue.newBuilder()
                                    .addValues(stringValue("audit:compliance")))
                            .build())
                    .build())
            .build();

    PolicyEvaluationResult decision = evaluate(requestFor("agentos/execution/actionGate"));

    assertEquals(PolicyOutcome.PERMITTED, decision.getOutcome());
    assertEquals("ok", decision.getReason());
    assertEquals(1, decision.getObligations().size());
    assertEquals("agentos/execution/actionGate", lastRequest.get().getRuleset());
    Struct sentInput = lastRequest.get().getInput();
    assertEquals(
        "wealth",
        sentInput
            .getFieldsOrThrow("agent")
            .getStructValue()
            .getFieldsOrThrow("namespace")
            .getStringValue());
    assertEquals(
        "recommend-fund-switch",
        sentInput
            .getFieldsOrThrow("context")
            .getStructValue()
            .getFieldsOrThrow("proposedAction")
            .getStructValue()
            .getFieldsOrThrow("type")
            .getStringValue());
  }

  @Test
  void notFoundStatusMeansNoRulesetIsLoaded() {
    nextErrorStatus = Status.NOT_FOUND;

    assertThrows(
        PolicyNotFoundException.class, () -> evaluate(requestFor("agentos/does/not/exist")));
  }

  @Test
  void unavailableStatusIsTreatedAsUnavailableNeverAsImplicitPermit() {
    nextErrorStatus = Status.UNAVAILABLE;

    assertThrows(
        PolicyEvaluationUnavailableException.class,
        () -> evaluate(requestFor("agentos/execution/actionGate")));
  }

  @Test
  void malformedResultIsTreatedAsUnavailableNeverAsImplicitPermit() {
    nextResponse =
        EvaluationResponse.newBuilder()
            .setResult(Struct.newBuilder().putFields("outcome", stringValue("not-a-real-outcome")))
            .build();

    assertThrows(
        PolicyEvaluationUnavailableException.class,
        () -> evaluate(requestFor("agentos/execution/actionGate")));
  }

  private PolicyEvaluationResult evaluate(PolicyEvaluationRequest request) {
    DroolsPolicyEvaluator evaluator =
        new DroolsPolicyEvaluator(channel, new ObjectMapper(), Duration.ofSeconds(5));
    return evaluator.evaluate(request);
  }

  private static PolicyEvaluationRequest requestFor(String policyPath) {
    var request =
        new PolicyEvaluationRequest(
            policyPath,
            Map.of("proposedAction", Map.of("type", "recommend-fund-switch", "amountUsd", 75000)));
    request.agent(new AgentCoordinates("wealth", "mutual-fund-analyst", "1.0.0"));
    return request;
  }

  private static Value stringValue(String value) {
    return Value.newBuilder().setStringValue(value).build();
  }

  private final class StubEvaluationService
      extends EvaluationServiceGrpc.EvaluationServiceImplBase {
    @Override
    public void evaluate(
        EvaluationRequest request, StreamObserver<EvaluationResponse> responseObserver) {
      lastRequest.set(request);
      if (nextErrorStatus != null) {
        responseObserver.onError(nextErrorStatus.asRuntimeException());
        return;
      }
      responseObserver.onNext(nextResponse);
      responseObserver.onCompleted();
    }
  }
}
