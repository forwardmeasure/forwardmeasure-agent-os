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
package com.forwardmeasure.agentos.architecturetests;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

// agent-os-domain must stay transport- and framework-neutral (docs/implementation-plan.md WP2) -
// these rules exist from day one, before any framework binding could plausibly creep in, per
// WP2's own "enforced by an ArchUnit rule from day one, not added after the fact." They will
// trivially pass today; their value is as a regression guard once governance/execution get real
// implementations in WP3/WP4.
class DomainNeutralityArchitectureTest {

  private static final String DOMAIN_PACKAGE = "com.forwardmeasure.agentos.domain..";

  private static final JavaClasses IMPORTED =
      new ClassFileImporter().importPackages("com.forwardmeasure.agentos");

  @Test
  void domainDoesNotDependOnAnyHostingFramework() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(DOMAIN_PACKAGE)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.quarkus..", "org.springframework..", "io.micronaut..");
    rule.check(IMPORTED);
  }

  @Test
  void domainDoesNotDependOnPersistence() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(DOMAIN_PACKAGE)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "jakarta.persistence..", "org.hibernate..", "com.forwardmeasure.jpa..");
    rule.check(IMPORTED);
  }

  @Test
  void domainDoesNotDependOnTheOpenWorkflowClient() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(DOMAIN_PACKAGE)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.forwardmeasure.openworkflow..");
    rule.check(IMPORTED);
  }

  @Test
  void domainDoesNotDependOnJaxRs() {
    // JAX-RS hosting is the -jaxrs layer's job, not the domain's - keeps domain reusable
    // by a non-HTTP caller (e.g. a future CLI or message consumer) without dragging in a
    // transport dependency.
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(DOMAIN_PACKAGE)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("jakarta.ws.rs..");
    rule.check(IMPORTED);
  }
}
