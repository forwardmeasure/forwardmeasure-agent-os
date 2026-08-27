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
package com.forwardmeasure.agentos.apispecifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// A real openapi-generator 7.24.0 jaxrs-spec bug (docs/implementation-plan.md WP1, "Three real
// bugs"), precisely isolated by actually compiling generated output against both patterns, not
// assumed: a cross-file $ref to a schema that ITSELF has a nested schema $ref (as a property
// type) gets miscompiled - Map-backed additionalProperties methods on a class that doesn't
// implement Map. Flat schemas (only primitive-typed properties) work fine as real cross-file
// $refs. Of the shared schemas, only Problem has a nested $ref (to Violation); every other
// shared schema is flat and is a genuine cross-file $ref into common-definitions.yaml.
// common-definitions.yaml is the canonical source for all of them, including Problem's local
// copy. This test guards both directions: Problem's local copy must stay byte-identical to the
// canonical definition (drift guard), and every other shared schema must stay a real cross-file
// $ref, not silently duplicated again.
class SharedSchemaInvariantTest {

  private static final String OPENAPI_ROOT = "META-INF/openapi/";
  private static final String COMMON_DEFINITIONS = OPENAPI_ROOT + "common-definitions.yaml";

  // The one shared schema that must stay a local copy - it has a nested $ref (to Violation) as
  // a property type, which is what triggers the generator bug when cross-file $ref'd.
  private static final String MUST_STAY_LOCAL = "Problem";

  private static JsonNode schemasOf(String classpathResource) throws Exception {
    try (InputStream in =
        SharedSchemaInvariantTest.class.getClassLoader().getResourceAsStream(classpathResource)) {
      assertTrue(in != null, classpathResource + " not found on the classpath");
      JsonNode spec = new YAMLMapper().readTree(in);
      return spec.path("components").path("schemas");
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "agent-governance-management.openapi.yaml",
        "agent-execution-management.openapi.yaml"
      })
  void problemsLocalCopyMatchesCommonDefinitionsExactly(String serviceSpecFileName)
      throws Exception {
    JsonNode canonical = schemasOf(COMMON_DEFINITIONS);
    JsonNode serviceSchemas = schemasOf(OPENAPI_ROOT + serviceSpecFileName);

    assertTrue(
        serviceSchemas.has(MUST_STAY_LOCAL),
        serviceSpecFileName + " is missing " + MUST_STAY_LOCAL);
    assertEquals(
        canonical.get(MUST_STAY_LOCAL),
        serviceSchemas.get(MUST_STAY_LOCAL),
        () ->
            serviceSpecFileName
                + "'s local copy of '"
                + MUST_STAY_LOCAL
                + "' has drifted from common-definitions.yaml's canonical definition");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "agent-governance-management.openapi.yaml",
        "agent-execution-management.openapi.yaml"
      })
  void everyOtherSharedSchemaIsARealCrossFileRefNotADuplicate(String serviceSpecFileName)
      throws Exception {
    JsonNode canonical = schemasOf(COMMON_DEFINITIONS);
    JsonNode serviceSchemas = schemasOf(OPENAPI_ROOT + serviceSpecFileName);

    Iterator<String> canonicalNames = canonical.fieldNames();
    int checked = 0;
    while (canonicalNames.hasNext()) {
      String name = canonicalNames.next();
      if (name.equals(MUST_STAY_LOCAL) || !serviceSchemas.has(name)) {
        continue;
      }
      JsonNode entry = serviceSchemas.get(name);
      Set<String> keys = new HashSet<>();
      entry.fieldNames().forEachRemaining(keys::add);
      assertEquals(
          Set.of("$ref"),
          keys,
          () -> name + " in " + serviceSpecFileName + " is not a bare cross-file $ref");
      assertEquals(
          "./common-definitions.yaml#/components/schemas/" + name,
          entry.get("$ref").asText(),
          () -> name + " in " + serviceSpecFileName + " does not $ref common-definitions.yaml");
      checked++;
    }
    // A service spec sharing zero non-Problem schema names with common-definitions.yaml would
    // make this test vacuously pass without checking anything - fail loud instead of silently.
    assertTrue(
        checked > 0,
        serviceSpecFileName
            + " shares no non-"
            + MUST_STAY_LOCAL
            + " schema names with"
            + " common-definitions.yaml");
  }
}
