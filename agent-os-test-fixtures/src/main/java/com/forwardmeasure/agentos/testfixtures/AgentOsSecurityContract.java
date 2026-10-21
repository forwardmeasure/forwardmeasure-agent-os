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
package com.forwardmeasure.agentos.testfixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The HTTP security contract every agent-os service answers identically on Quarkus, Spring and
 * Micronaut, checked over real HTTP against a booted service: an open health endpoint, and an RFC
 * 9457 problem for every caller turned away - 401 with a {@code Bearer} challenge for no or an
 * unusable token, 403 for an authenticated caller without tenant access (no actor, or a disabled
 * one).
 *
 * <p>A {@link Probe} names one protected operation of the service under test; {@link
 * #assertCallerAdmitted} proves the enabled actor gets past both the HTTP layer and the actor
 * resolver to that operation, so the rejections are not blanket. {@link
 * #assertContractViolationIsRejected} checks the request-contract half: a body breaking the
 * generated constraints is the same 400 problem on every framework.
 */
public final class AgentOsSecurityContract {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private final URI baseUri;
  private final AgentOsServiceFixture fixture;

  public AgentOsSecurityContract(URI baseUri, AgentOsServiceFixture fixture) {
    this.baseUri = baseUri;
    this.fixture = fixture;
  }

  /** One protected operation: method, path, extra headers, and (for a POST) its JSON body. */
  public record Probe(String method, String path, Map<String, String> headers, String jsonBody) {
    public Probe {
      headers = Map.copyOf(headers);
    }

    public static Probe get(String path) {
      return new Probe("GET", path, Map.of(), null);
    }

    public static Probe post(String path, String jsonBody) {
      return new Probe("POST", path, Map.of(), jsonBody);
    }

    public Probe withHeader(String name, String value) {
      Map<String, String> all = new LinkedHashMap<>(headers);
      all.put(name, value);
      return new Probe(method, path, all, jsonBody);
    }
  }

  /** A response as the contract reads it. */
  public record Result(int status, String contentType, Optional<String> challenge, JsonNode body) {}

  /** Health is reachable with no token - kubelet's probes send none. */
  public void assertHealthIsOpen(String healthPath) {
    Result result = send(Probe.get(healthPath), null);
    assertEquals(200, result.status(), () -> healthPath + " must be open: " + result);
  }

  /** No token at all: 401 problem with the Bearer challenge, before the operation runs. */
  public void assertUnauthenticatedRequestIsRejected(Probe probe) {
    assertUnauthorizedProblem(send(probe, null));
  }

  /** A token that fails validation is a 401 too - never treated as anonymous. */
  public void assertInvalidTokenIsRejected(Probe probe) {
    assertUnauthorizedProblem(send(probe, "not-a-jwt"));
  }

  /** A genuine token with no active Organization: 401 problem (AuthenticationRequiredException). */
  public void assertTokenWithoutOrganizationIsRejected(Probe probe) {
    assertUnauthorizedProblem(send(probe, fixture.noOrganizationToken()));
  }

  /** An Organization member with no actor in the tenant: 403 problem, no actor created. */
  public void assertUnprovisionedActorIsForbidden(Probe probe) {
    assertForbiddenProblem(send(probe, fixture.unprovisionedActorToken()));
    // Never created on first sight: a second call is denied the same way.
    assertForbiddenProblem(send(probe, fixture.unprovisionedActorToken()));
  }

  /** A member whose actor's tenant access was removed (disabled): 403 problem. */
  public void assertDisabledActorIsForbidden(Probe probe) {
    assertForbiddenProblem(send(probe, fixture.disabledActorToken()));
  }

  /**
   * The enabled actor reaches the operation itself: the response is {@code expectedStatus}, never
   * the 401/403 the other callers get.
   */
  public Result assertCallerAdmitted(Probe probe, int expectedStatus) {
    Result result = send(probe, fixture.enabledActorToken());
    assertNotEquals(401, result.status(), () -> "enabled actor rejected: " + result);
    assertNotEquals(403, result.status(), () -> "enabled actor forbidden: " + result);
    assertEquals(expectedStatus, result.status(), () -> "enabled actor: " + result);
    return result;
  }

  /**
   * The enabled actor sends a body that breaks the generated contract's constraints: the shared 400
   * problem (forwardmeasure-platform-server-jaxrs's ConstraintViolationProblems on Quarkus and
   * Spring, forwardmeasure-platform-micronaut-validation on Micronaut), naming exactly the
   * offending body fields by their JSON paths, each with its rejected value - identical on every
   * framework, nested fields included.
   */
  public void assertContractViolationIsRejected(Probe probe, Map<String, String> rejectedFields) {
    Result result = send(probe, fixture.enabledActorToken());
    assertProblem(result, 400);
    assertEquals("Bad Request", result.body().path("title").asText(), () -> "title: " + result);
    Map<String, String> violations = new TreeMap<>();
    for (JsonNode violation : result.body().path("violations")) {
      violations.put(violation.path("field").asText(), violation.path("rejected_value").asText());
      assertFalse(
          violation.path("message").asText().isBlank(), () -> "violation message: " + result);
    }
    assertEquals(new TreeMap<>(rejectedFields), violations, () -> "violations: " + result);
  }

  public Result send(Probe probe, String bearerToken) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(baseUri.resolve(probe.path()))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json, application/problem+json")
            .header("X-Correlation-ID", UUID.randomUUID().toString());
    probe.headers().forEach(request::header);
    if (bearerToken != null) {
      request.header("Authorization", "Bearer " + bearerToken);
    }
    if (probe.jsonBody() == null) {
      request.method(probe.method(), HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Content-Type", "application/json")
          .method(probe.method(), HttpRequest.BodyPublishers.ofString(probe.jsonBody()));
    }
    try {
      HttpResponse<String> response =
          HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
      String body = response.body();
      return new Result(
          response.statusCode(),
          response.headers().firstValue("Content-Type").orElse(""),
          response.headers().firstValue("WWW-Authenticate"),
          body == null || body.isBlank() ? JSON.missingNode() : readJson(body));
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static JsonNode readJson(String body) {
    try {
      return JSON.readTree(body);
    } catch (IOException notJson) {
      return JSON.getNodeFactory().textNode(body);
    }
  }

  private static void assertUnauthorizedProblem(Result result) {
    assertProblem(result, 401);
    assertTrue(
        result.challenge().filter(value -> value.startsWith("Bearer")).isPresent(),
        () -> "a 401 must carry the Bearer challenge: " + result);
  }

  private static void assertForbiddenProblem(Result result) {
    assertProblem(result, 403);
  }

  /** RFC 9457: application/problem+json whose {@code status} member matches the HTTP status. */
  public static void assertProblem(Result result, int status) {
    assertEquals(status, result.status(), () -> "status: " + result);
    assertTrue(
        result.contentType().startsWith("application/problem+json"),
        () -> "an RFC 9457 problem is expected: " + result);
    assertEquals(status, result.body().path("status").asInt(), () -> "problem status: " + result);
    assertTrue(result.body().hasNonNull("title"), () -> "problem title: " + result);
  }
}
