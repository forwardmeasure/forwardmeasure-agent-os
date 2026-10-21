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
package com.forwardmeasure.agentos.policy.micronaut;

import com.forwardmeasure.agentos.testfixtures.AgentOsSecurityContract;
import com.forwardmeasure.agentos.testfixtures.AgentOsSecurityContract.Probe;
import com.forwardmeasure.agentos.testfixtures.AgentOsServiceFixture;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The HTTP security contract ({@link AgentOsSecurityContract}) against this Micronaut deployment of
 * agent-os policy, booted for real against {@link AgentOsServiceFixture}'s Keycloak and PostgreSQL:
 * health open, every rejected caller an RFC 9457 problem (401 for no, an invalid or an
 * Organization-less token; 403 for an Organization member with no or a disabled actor), and the
 * enabled actor admitted to the policy evaluator (OPA is deliberately unreachable, so its 503
 * proves the call got past authentication and actor resolution).
 */
@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentPolicyMicronautSecurityTest implements TestPropertyProvider {

  private static final Probe PROBE =
      Probe.post("/v1/policy-evaluations", "{\"policyPath\":\"agentos/test\",\"input\":{}}");
  private static final int ADMITTED = 503;

  // Breaks the generated contract's constraints (a pattern); validated before the operation runs.
  private static final Probe CONTRACT_VIOLATION =
      Probe.post("/v1/policy-evaluations", "{\"policyPath\":\"Not A Path\",\"input\":{}}");
  private static final Map<String, String> REJECTED_FIELDS = Map.of("policyPath", "Not A Path");

  private AgentOsServiceFixture fixture;

  @Inject EmbeddedServer server;

  @Override
  public Map<String, String> getProperties() {
    fixture = AgentOsServiceFixture.start();
    return Map.ofEntries(
        Map.entry("datasources.default.url", fixture.controlPlaneJdbcUrl()),
        Map.entry("datasources.default.username", AgentOsServiceFixture.RUNTIME_USERNAME),
        Map.entry("datasources.default.password", AgentOsServiceFixture.RUNTIME_PASSWORD),
        Map.entry("micronaut.security.token.jwt.signatures.jwks.keycloak.url", fixture.jwksUrl()),
        Map.entry(
            "micronaut.security.token.jwt.claims-validators.issuer", fixture.issuer().toString()),
        Map.entry("forwardmeasure.jpa.tenant-database.host", fixture.databaseHost()),
        Map.entry(
            "forwardmeasure.jpa.tenant-database.port", String.valueOf(fixture.databasePort())),
        Map.entry(
            "forwardmeasure.jpa.tenant-database.username", AgentOsServiceFixture.RUNTIME_USERNAME),
        Map.entry(
            "forwardmeasure.jpa.tenant-database.password", AgentOsServiceFixture.RUNTIME_PASSWORD),
        Map.entry("agent-os.security.client-id", AgentOsServiceFixture.CLIENT_ID),
        Map.entry("agent-os.policy.opa-base-url", fixture.unreachableUrl()));
  }

  @AfterAll
  void stopFixture() {
    if (fixture != null) {
      fixture.close();
      fixture = null;
    }
  }

  @Test
  void healthIsOpenWithoutAToken() {
    contract().assertHealthIsOpen("/health");
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
    return new AgentOsSecurityContract(server.getURI().resolve("/"), fixture);
  }
}
