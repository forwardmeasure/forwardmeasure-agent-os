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
package com.forwardmeasure.agentos.policy.quarkus;

import com.forwardmeasure.agentos.testfixtures.AgentOsSecurityContract;
import com.forwardmeasure.agentos.testfixtures.AgentOsSecurityContract.Probe;
import com.forwardmeasure.agentos.testfixtures.AgentOsServiceFixture;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The HTTP security contract ({@link AgentOsSecurityContract}) against this Quarkus deployment of
 * agent-os policy, booted for real against {@link AgentOsServiceFixture}'s Keycloak and PostgreSQL:
 * health open, every rejected caller an RFC 9457 problem (401 for no, an invalid or an
 * Organization-less token; 403 for an Organization member with no or a disabled actor), and the
 * enabled actor admitted to the policy evaluator (OPA is deliberately unreachable, so its 503
 * proves the call got past authentication and actor resolution).
 */
@QuarkusTest
// restrictToAnnotatedClass: a resource is global by default, merging every resource's config in
// the module into one boot.
@QuarkusTestResource(
    value = AgentPolicyQuarkusSecurityTest.Environment.class,
    restrictToAnnotatedClass = true)
class AgentPolicyQuarkusSecurityTest {

  private static final Probe PROBE =
      Probe.post("/v1/policy-evaluations", "{\"policyPath\":\"agentos/test\",\"input\":{}}");
  private static final int ADMITTED = 503;

  // Breaks the generated contract's constraints (a pattern); validated before the operation runs.
  private static final Probe CONTRACT_VIOLATION =
      Probe.post("/v1/policy-evaluations", "{\"policyPath\":\"Not A Path\",\"input\":{}}");
  private static final Map<String, String> REJECTED_FIELDS = Map.of("policyPath", "Not A Path");

  @TestHTTPResource("/")
  URL baseUrl;

  @Test
  void healthIsOpenWithoutAToken() {
    contract().assertHealthIsOpen("/q/health/live");
  }

  @Test
  void rejectsARequestWithNoToken() {
    contract().assertUnauthenticatedRequestIsRejected(PROBE);
  }

  @Test
  void rejectsATokenThatFailsValidation() {
    contract().assertInvalidTokenIsRejected(PROBE);
  }

  @Test
  void rejectsATokenWithoutAnActiveOrganization() {
    contract().assertTokenWithoutOrganizationIsRejected(PROBE);
  }

  @Test
  void forbidsAnOrganizationMemberWithNoActor() {
    contract().assertUnprovisionedActorIsForbidden(PROBE);
  }

  @Test
  void forbidsADisabledActor() {
    contract().assertDisabledActorIsForbidden(PROBE);
  }

  @Test
  void rejectsABodyThatBreaksTheContractWithTheSharedProblem() {
    contract().assertContractViolationIsRejected(CONTRACT_VIOLATION, REJECTED_FIELDS);
  }

  @Test
  void admitsAnEnabledActor() {
    contract().assertCallerAdmitted(PROBE, ADMITTED);
  }

  private AgentOsSecurityContract contract() {
    try {
      return new AgentOsSecurityContract(baseUrl.toURI(), Environment.fixture);
    } catch (URISyntaxException failure) {
      throw new IllegalStateException(failure);
    }
  }

  /** Starts the fixture before Quarkus boots and points the service at it. */
  public static final class Environment implements QuarkusTestResourceLifecycleManager {
    static volatile AgentOsServiceFixture fixture;

    @Override
    public Map<String, String> start() {
      fixture = AgentOsServiceFixture.start();
      return Map.ofEntries(
          Map.entry("quarkus.http.test-port", "0"),
          Map.entry("quarkus.datasource.devservices.enabled", "false"),
          Map.entry("quarkus.keycloak.devservices.enabled", "false"),
          Map.entry("quarkus.datasource.jdbc.url", fixture.controlPlaneJdbcUrl()),
          Map.entry("quarkus.datasource.username", AgentOsServiceFixture.RUNTIME_USERNAME),
          Map.entry("quarkus.datasource.password", AgentOsServiceFixture.RUNTIME_PASSWORD),
          Map.entry("quarkus.oidc.auth-server-url", fixture.issuer().toString()),
          Map.entry("quarkus.oidc.client-id", AgentOsServiceFixture.CLIENT_ID),
          Map.entry("forwardmeasure.jpa.tenant-database.host", fixture.databaseHost()),
          Map.entry(
              "forwardmeasure.jpa.tenant-database.port", String.valueOf(fixture.databasePort())),
          Map.entry(
              "forwardmeasure.jpa.tenant-database.username",
              AgentOsServiceFixture.RUNTIME_USERNAME),
          Map.entry(
              "forwardmeasure.jpa.tenant-database.password",
              AgentOsServiceFixture.RUNTIME_PASSWORD),
          Map.entry("agent-os.security.client-id", AgentOsServiceFixture.CLIENT_ID),
          Map.entry("agent-os.policy.opa-base-url", fixture.unreachableUrl()));
    }

    @Override
    public void stop() {
      if (fixture != null) {
        fixture.close();
        fixture = null;
      }
    }
  }
}
