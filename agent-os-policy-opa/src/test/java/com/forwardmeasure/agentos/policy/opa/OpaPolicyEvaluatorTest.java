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
package com.forwardmeasure.agentos.policy.opa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.agentos.domain.PolicyEvaluationUnavailableException;
import com.forwardmeasure.agentos.domain.PolicyNotFoundException;
import com.forwardmeasure.agentos.policy.api.model.PolicyEvaluationRequest;
import com.forwardmeasure.agentos.policy.api.model.PolicyEvaluationResult;
import com.forwardmeasure.agentos.policy.api.model.PolicyOutcome;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// A real HTTP round trip against a local com.sun.net.httpserver stub standing in for OPA - no new
// test dependency (WireMock/MockWebServer) needed for a contract this small, and this exercises
// OpaPolicyEvaluator's actual request/response wire handling, not a mocked HttpClient.
class OpaPolicyEvaluatorTest {

  private HttpServer server;
  private volatile String lastRequestPath;
  private volatile String lastRequestBody;
  private volatile String nextResponseBody;
  private volatile int nextResponseStatus;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", this::handle);
    server.start();
    nextResponseStatus = 200;
    nextResponseBody = "{}";
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void permittedDecisionRoundTrips() throws IOException, InterruptedException {
    nextResponseBody =
        "{\"result\":{\"outcome\":\"permitted\",\"reason\":\"ok\",\"obligations\":[\"audit:compliance\"]}}";

    PolicyEvaluationResult decision = evaluate(requestFor("agentos/execution/actionGate"));

    assertEquals(PolicyOutcome.PERMITTED, decision.getOutcome());
    assertEquals("ok", decision.getReason());
    assertEquals(1, decision.getObligations().size());
    assertEquals("v1/data/agentos/execution/actionGate", lastRequestPath.replaceFirst("^/", ""));
    ObjectMapper mapper = new ObjectMapper();
    JsonNode sentInput = mapper.readTree(lastRequestBody).path("input");
    assertEquals("wealth", sentInput.path("agent").path("namespace").asText());
    assertEquals(
        "recommend-fund-switch",
        sentInput.path("context").path("proposedAction").path("type").asText());
  }

  @Test
  void missingResultKeyMeansNoPolicyIsLoaded() {
    nextResponseBody = "{}";

    assertThrows(
        PolicyNotFoundException.class, () -> evaluate(requestFor("agentos/does/not/exist")));
  }

  @Test
  void nonTwoHundredIsTreatedAsUnavailableNeverAsImplicitPermit() {
    nextResponseStatus = 503;

    assertThrows(
        PolicyEvaluationUnavailableException.class,
        () -> evaluate(requestFor("agentos/execution/actionGate")));
  }

  @Test
  void malformedResultIsTreatedAsUnavailableNeverAsImplicitPermit() {
    nextResponseBody = "{\"result\":{\"outcome\":\"not-a-real-outcome\"}}";

    assertThrows(
        PolicyEvaluationUnavailableException.class,
        () -> evaluate(requestFor("agentos/execution/actionGate")));
  }

  private PolicyEvaluationResult evaluate(PolicyEvaluationRequest request)
      throws IOException, InterruptedException {
    OpaPolicyEvaluator evaluator =
        new OpaPolicyEvaluator(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            URI.create("http://localhost:" + server.getAddress().getPort() + "/"),
            Duration.ofSeconds(5));
    return evaluator.evaluate(request);
  }

  private static PolicyEvaluationRequest requestFor(String policyPath) {
    var request =
        new PolicyEvaluationRequest(
            policyPath,
            Map.of("proposedAction", Map.of("type", "recommend-fund-switch", "amountUsd", 75000)));
    request.agent(
        new com.forwardmeasure.agentos.policy.api.model.AgentCoordinates(
            "wealth", "mutual-fund-analyst", "1.0.0"));
    return request;
  }

  private void handle(HttpExchange exchange) throws IOException {
    lastRequestPath = exchange.getRequestURI().getPath();
    lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    byte[] response = nextResponseBody.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(nextResponseStatus, response.length);
    try (var body = exchange.getResponseBody()) {
      body.write(response);
    }
  }
}
