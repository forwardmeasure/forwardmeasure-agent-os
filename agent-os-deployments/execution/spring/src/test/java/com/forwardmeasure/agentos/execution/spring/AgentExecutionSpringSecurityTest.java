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
package com.forwardmeasure.agentos.execution.spring;

import com.forwardmeasure.agentos.testfixtures.AgentOsSecurityContract;
import com.forwardmeasure.agentos.testfixtures.AgentOsSecurityContract.Probe;
import com.forwardmeasure.agentos.testfixtures.AgentOsServiceFixture;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The HTTP security contract ({@link AgentOsSecurityContract}) against this Spring deployment of
 * agent-os execution, booted for real against {@link AgentOsServiceFixture}'s Keycloak and
 * PostgreSQL: health open, every rejected caller an RFC 9457 problem (401 for no, an invalid or an
 * Organization-less token; 403 for an Organization member with no or a disabled actor), and the
 * enabled actor admitted to the execution list.
 */
@SpringBootTest(
    classes = AgentExecutionSpringApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentExecutionSpringSecurityTest {

  private static final Probe PROBE = Probe.get("/v1/agent-executions");
  private static final int ADMITTED = 200;

  // Breaks the generated contract's constraints (a field nested in an object); validated before the
  // operation runs.
  private static final Probe CONTRACT_VIOLATION =
      Probe.post(
              "/v1/agent-executions",
              "{\"agent\":{\"namespace\":\"Not Valid\",\"name\":\"invoice-agent\","
                  + "\"version\":\"1.0.0\"},\"input\":{}}")
          .withHeader("Idempotency-Key", "contract-violation");
  private static final Map<String, String> REJECTED_FIELDS = Map.of("agent.namespace", "Not Valid");

  private static AgentOsServiceFixture fixture;

  @LocalServerPort private int port;

  // Started on first use: @DynamicPropertySource is read while the context is created, which may
  // happen before or after JUnit's @BeforeAll depending on context caching.
  private static synchronized AgentOsServiceFixture fixture() {
    if (fixture == null) {
      fixture = AgentOsServiceFixture.start();
    }
    return fixture;
  }

  @DynamicPropertySource
  static void serviceProperties(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> fixture().controlPlaneJdbcUrl());
    properties.add("spring.datasource.username", () -> AgentOsServiceFixture.RUNTIME_USERNAME);
    properties.add("spring.datasource.password", () -> AgentOsServiceFixture.RUNTIME_PASSWORD);
    properties.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        () -> fixture().issuer().toString());
    properties.add("forwardmeasure.jpa.tenant-database.host", () -> fixture().databaseHost());
    properties.add(
        "forwardmeasure.jpa.tenant-database.port", () -> String.valueOf(fixture().databasePort()));
    properties.add(
        "forwardmeasure.jpa.tenant-database.username",
        () -> AgentOsServiceFixture.RUNTIME_USERNAME);
    properties.add(
        "forwardmeasure.jpa.tenant-database.password",
        () -> AgentOsServiceFixture.RUNTIME_PASSWORD);
    properties.add("agent-os.security.client-id", () -> AgentOsServiceFixture.CLIENT_ID);
    properties.add("agent-os.openworkflow.client-secret", () -> "unused-by-these-tests");
  }

  @AfterAll
  static synchronized void stopFixture() {
    if (fixture != null) {
      fixture.close();
      fixture = null;
    }
  }

  @Test
  void healthIsOpenWithoutAToken() {
    contract().assertHealthIsOpen("/actuator/health");
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
    return new AgentOsSecurityContract(URI.create("http://localhost:" + port + "/"), fixture());
  }
}
