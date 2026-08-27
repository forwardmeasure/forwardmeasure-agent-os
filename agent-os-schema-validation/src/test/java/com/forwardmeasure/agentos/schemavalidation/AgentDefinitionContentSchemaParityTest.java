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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.forwardmeasure.agentos.governance.api.model.AgentA2uiComponent;
import com.forwardmeasure.agentos.governance.api.model.AgentA2uiFieldPresentation;
import com.forwardmeasure.agentos.governance.api.model.AgentA2uiPresentation;
import com.forwardmeasure.agentos.governance.api.model.AgentDefinitionContent;
import com.forwardmeasure.agentos.governance.api.model.AgentProtocols;
import com.forwardmeasure.agentos.governance.api.model.AgentSkill;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// The parity test docs/implementation-plan.md WP2 calls for: the real OpenAPI-generated
// AgentDefinitionContent (agent-os-governance-management-models, WP1 - not a hand-rolled parallel
// domain type, and not an untyped JsonNode either) must satisfy the JSON Schema WP1's generator
// built from the same OpenAPI source of truth. Both come from the same spec, so this is less
// "parity between two independently-maintained shapes" and more a regression guard against the
// two generation paths (jaxrs-spec models vs. the custom $ref-closure schema bundler) drifting
// from each other despite sharing a source.
class AgentDefinitionContentSchemaParityTest {

  private static final String RESOURCE_PATH = "META-INF/schema/agent-definition.schema.json";

  // The generated model has no @JsonInclude(NON_NULL) of its own (openapi-generator's jaxrs-spec
  // templates don't emit one) - a plain ObjectMapper serializes an unset optional field as a
  // literal JSON null. "nullable: true" is an OpenAPI/3.0-era extension, not a standard JSON
  // Schema keyword, so a standards-compliant draft-2020-12 validator ignores it and a literal
  // null fails {"type":"string"}. Whatever serializes this model for real (WP3+) must configure
  // NON_NULL inclusion; this test's mapper does the same, and is the regression guard proving
  // that configuration is what parity actually depends on.
  private static ObjectMapper mapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
    return mapper;
  }

  private static Schema loadSchema() throws Exception {
    try (InputStream in =
        AgentDefinitionContentSchemaParityTest.class
            .getClassLoader()
            .getResourceAsStream(RESOURCE_PATH)) {
      assertTrue(in != null, RESOURCE_PATH + " was not generated onto the classpath");
      JsonNode schemaNode = new ObjectMapper().readTree(in);
      SchemaRegistry registry =
          SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
      return registry.getSchema(schemaNode);
    }
  }

  private static AgentDefinitionContent validContent() {
    AgentSkill skill =
        new AgentSkill(
                "reconcile-invoice",
                "Reconcile Invoice",
                "Matches an invoice line to its purchase order")
            .tags(List.of("finance"))
            .inputModes(List.of("json"))
            .outputModes(List.of("json"));
    AgentA2uiPresentation presentation =
        new AgentA2uiPresentation()
            .title("Reconcile an Invoice")
            .submitLabel("Submit")
            .fieldOrder(List.of("invoiceId"))
            .putFieldsItem(
                "invoiceId",
                new AgentA2uiFieldPresentation()
                    .label("Invoice ID")
                    .component(AgentA2uiComponent.TEXT_FIELD));
    return new AgentDefinitionContent(
            "Invoice Reconciliation Agent",
            List.of(skill),
            Map.of("type", "object"),
            Map.of("type", "object"),
            new AgentProtocols(true, true),
            URI.create("https://agents.example.com/invoice-reconciliation"))
        .description("Reconciles vendor invoices against purchase orders.")
        .a2uiPresentation(presentation);
  }

  @Test
  void aValidGeneratedModelInstanceSatisfiesTheGeneratedSchema() throws Exception {
    Schema schema = loadSchema();
    JsonNode serialized = mapper().valueToTree(validContent());
    List<Error> errors = schema.validate(serialized);
    assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
  }

  @Test
  void aMissingRequiredWireFieldFailsValidation() throws Exception {
    Schema schema = loadSchema();
    ObjectNode serialized = (ObjectNode) mapper().valueToTree(validContent());
    serialized.remove("publicUri");

    List<Error> errors = schema.validate(serialized);
    assertFalse(errors.isEmpty(), "expected the missing publicUri to be flagged");
  }

  @Test
  void theSchemaRejectsAnAdditionalPropertyNotInAgentDefinitionContent() throws Exception {
    Schema schema = loadSchema();
    ObjectNode serialized = (ObjectNode) mapper().valueToTree(validContent());
    serialized.put("notARealField", "drift");

    List<Error> errors = schema.validate(serialized);
    assertFalse(errors.isEmpty(), "expected the unknown property to be flagged");
  }

  @Test
  void withNonNullInclusionUnsetOptionalFieldsAreOmittedNotSerializedAsNull() throws Exception {
    AgentDefinitionContent minimal =
        new AgentDefinitionContent(
            "Minimal Agent",
            List.of(new AgentSkill("s", "S", "d")),
            Map.of("type", "object"),
            Map.of("type", "object"),
            new AgentProtocols(true, false),
            URI.create("https://agents.example.com/minimal"));
    // description and a2uiPresentation deliberately left unset.

    ObjectNode serialized = (ObjectNode) mapper().valueToTree(minimal);
    assertFalse(serialized.has("description"));
    assertFalse(serialized.has("a2uiPresentation"));

    Schema schema = loadSchema();
    List<Error> errors = schema.validate(serialized);
    assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
  }

  @Test
  void withoutNonNullInclusionUnsetOptionalFieldsSerializeAsLiteralNullAndFailValidation()
      throws Exception {
    // The negative case proving the ObjectMapper configuration above is load-bearing, not
    // incidental - a default-configured mapper genuinely breaks parity for this generated model.
    AgentDefinitionContent minimal =
        new AgentDefinitionContent(
            "Minimal Agent",
            List.of(new AgentSkill("s", "S", "d")),
            Map.of("type", "object"),
            Map.of("type", "object"),
            new AgentProtocols(true, false),
            URI.create("https://agents.example.com/minimal"));

    ObjectNode serialized = (ObjectNode) new ObjectMapper().valueToTree(minimal);
    assertTrue(serialized.has("description"));
    assertEquals(
        com.fasterxml.jackson.databind.node.NullNode.getInstance(), serialized.get("description"));

    Schema schema = loadSchema();
    List<Error> errors = schema.validate(serialized);
    assertFalse(errors.isEmpty(), "expected the literal null description to be flagged");
  }
}
