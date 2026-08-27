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
package com.forwardmeasure.agentos.schemavalidation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
import java.util.Set;
import org.junit.jupiter.api.Test;

// Exercises the build-time output of AgentDefinitionSchemaGenerator (bound to the
// exec-maven-plugin execution in pom.xml, not invoked directly here) - not the domain-object
// parity test, which is WP2's job once agent-os-domain has real record types to validate.
class AgentDefinitionSchemaGenerationTest {

  private static final String RESOURCE_PATH = "META-INF/schema/agent-definition.schema.json";

  // The exact transitive $ref closure of AgentDefinitionContent, verified against the real
  // agent-governance-management.openapi.yaml source, not assumed.
  private static final Set<String> EXPECTED_DEFS =
      Set.of(
          "AgentSkill",
          "AgentProtocols",
          "AgentA2uiPresentation",
          "AgentA2uiFieldPresentation",
          "AgentA2uiComponent");

  private JsonNode loadGeneratedSchema() throws Exception {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
      assertTrue(in != null, RESOURCE_PATH + " was not generated onto the classpath");
      return new ObjectMapper().readTree(in);
    }
  }

  @Test
  void rootSchemaIsSelfDescribingDraft2020_12() throws Exception {
    JsonNode schema = loadGeneratedSchema();
    assertEquals("https://json-schema.org/draft/2020-12/schema", schema.path("$schema").asText());
    assertEquals("object", schema.path("type").asText());
  }

  @Test
  void rootSchemaCarriesAgentDefinitionContentsOwnRequiredFields() throws Exception {
    JsonNode schema = loadGeneratedSchema();
    Set<String> required = new java.util.HashSet<>();
    schema.path("required").forEach(n -> required.add(n.asText()));
    assertEquals(
        Set.of("displayName", "skills", "inputSchema", "outputSchema", "protocols", "publicUri"),
        required);
  }

  @Test
  void defsContainExactlyTheVerifiedTransitiveClosureNoMoreNoLess() throws Exception {
    JsonNode schema = loadGeneratedSchema();
    Set<String> actualDefs = new java.util.HashSet<>();
    schema.path("$defs").fieldNames().forEachRemaining(actualDefs::add);
    assertEquals(EXPECTED_DEFS, actualDefs);
  }

  @Test
  void generatedDocumentIsAStructurallyValidJsonSchema() throws Exception {
    JsonNode schemaNode = loadGeneratedSchema();
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    Schema schema = registry.getSchema(schemaNode);
    // A schema com.networknt can load and run validation with, without throwing, is the
    // structural-validity bar this test exists to enforce.
    schema.validate(new ObjectMapper().createObjectNode());
  }
}
