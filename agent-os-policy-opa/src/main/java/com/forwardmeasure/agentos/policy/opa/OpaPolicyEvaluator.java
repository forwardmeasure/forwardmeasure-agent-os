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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.forwardmeasure.agentos.domain.PolicyEvaluationUnavailableException;
import com.forwardmeasure.agentos.domain.PolicyEvaluator;
import com.forwardmeasure.agentos.domain.PolicyNotFoundException;
import com.forwardmeasure.agentos.policy.api.model.PolicyEvaluationRequest;
import com.forwardmeasure.agentos.policy.api.model.PolicyEvaluationResult;
import com.forwardmeasure.agentos.policy.api.model.PolicyOutcome;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// Translates the PolicyEvaluator port (agent-os-domain) into OPA's own REST contract - POST
// {opaBaseUrl}/v1/data/{policyPath} with {"input": ...}, read back {"result": ...}. Raw
// java.net.http.HttpClient plus Jackson, not a generated client - OPA is a third-party engine with
// no OpenAPI spec of its own in this reactor, so there is nothing to generate from. Mirrors
// OAuthClientCredentialsTokenSupplier's own style for calling an external JSON HTTP API
// (openworkflow-authorization-authzen) - the closest real precedent in this ecosystem.
public final class OpaPolicyEvaluator implements PolicyEvaluator {

  private final HttpClient client;
  private final ObjectMapper mapper;
  private final URI baseUrl;
  private final String bearerToken;
  private final Duration timeout;

  public OpaPolicyEvaluator(HttpClient client, ObjectMapper mapper, URI baseUrl, Duration timeout) {
    this(client, mapper, baseUrl, null, timeout);
  }

  public OpaPolicyEvaluator(
      HttpClient client, ObjectMapper mapper, URI baseUrl, String bearerToken, Duration timeout) {
    this.client = Objects.requireNonNull(client, "client");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
    this.bearerToken = bearerToken;
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public PolicyEvaluationResult evaluate(PolicyEvaluationRequest request) {
    Objects.requireNonNull(request, "request");
    String policyPath = request.getPolicyPath();
    URI dataUri = baseUrl.resolve("v1/data/" + policyPath);
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(dataUri)
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(opaRequestBody(request)));
      if (bearerToken != null && !bearerToken.isBlank()) {
        builder.header("Authorization", "Bearer " + bearerToken);
      }
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        throw unavailable("OPA returned HTTP " + response.statusCode(), null);
      }
      JsonNode responseBody = mapper.readTree(response.body());
      // OPA's own contract: a path with no loaded policy module returns {} - no "result" key at
      // all, distinct from a loaded policy that evaluated its (possibly default) rules to a real
      // value. Never interpret the former as a decision.
      if (!responseBody.hasNonNull("result")) {
        throw new PolicyNotFoundException(policyPath);
      }
      return decisionOf(responseBody.get("result"));
    } catch (IOException failure) {
      throw unavailable("OPA is unreachable", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw unavailable("policy evaluation was interrupted", failure);
    }
  }

  private byte[] opaRequestBody(PolicyEvaluationRequest request) throws IOException {
    ObjectNode input = mapper.createObjectNode();
    input.set("agent", mapper.valueToTree(request.getAgent()));
    input.set("context", mapper.valueToTree(request.getInput()));
    ObjectNode body = mapper.createObjectNode();
    body.set("input", input);
    return mapper.writeValueAsBytes(body);
  }

  // Every real policy is expected to declare an explicit, fail-closed default (never PERMITTED)
  // for inputs it doesn't recognize - this method trusts that convention rather than
  // second-guessing it, since OPA itself has no notion of "the policy didn't apply". A malformed
  // result (missing outcome, or a value not in PolicyOutcome's enum) is treated the same as an
  // unavailable engine, not as an implicit permit.
  private PolicyEvaluationResult decisionOf(JsonNode result) {
    JsonNode outcomeNode = result.path("outcome");
    if (!outcomeNode.isTextual()) {
      throw unavailable("OPA result is missing a valid outcome: " + result, null);
    }
    PolicyOutcome outcome;
    try {
      outcome = PolicyOutcome.fromValue(outcomeNode.asText());
    } catch (IllegalArgumentException invalid) {
      throw unavailable("OPA returned an unrecognized outcome: " + outcomeNode.asText(), invalid);
    }
    PolicyEvaluationResult decision = new PolicyEvaluationResult(outcome);
    JsonNode reasonNode = result.path("reason");
    if (reasonNode.isTextual()) {
      decision.reason(reasonNode.asText());
    }
    JsonNode obligationsNode = result.path("obligations");
    if (obligationsNode.isArray()) {
      List<String> obligations = new ArrayList<>();
      obligationsNode.forEach(node -> obligations.add(node.asText()));
      decision.obligations(obligations);
    }
    return decision;
  }

  private static PolicyEvaluationUnavailableException unavailable(String message, Throwable cause) {
    return new PolicyEvaluationUnavailableException(message, cause);
  }
}
