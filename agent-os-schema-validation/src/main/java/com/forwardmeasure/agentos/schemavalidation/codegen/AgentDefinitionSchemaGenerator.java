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
package com.forwardmeasure.agentos.schemavalidation.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// Build-time only, invoked by exec-maven-plugin (see pom.xml) - not part of this
// module's runtime API. Bundles AgentDefinitionContent and every OpenAPI component
// schema it transitively $refs into one self-contained JSON Schema (draft 2020-12)
// document, per docs/implementation-plan.md §2.6.
public final class AgentDefinitionSchemaGenerator {

  private static final String ROOT_SCHEMA_NAME = "AgentDefinitionContent";
  private static final String REF_PREFIX = "#/components/schemas/";
  private static final String SCHEMA_ID =
      "https://schemas.forwardmeasure.com/agent-os/agent-definition.schema.json";

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException(
          "usage: AgentDefinitionSchemaGenerator <openapi-spec-yaml> <output-json>");
    }
    generate(Path.of(args[0]), Path.of(args[1]));
  }

  static void generate(Path specYaml, Path outputJson) throws Exception {
    ObjectMapper json = new ObjectMapper();
    JsonNode spec = new YAMLMapper().readTree(specYaml.toFile());
    JsonNode schemas = spec.path("components").path("schemas");
    if (!schemas.has(ROOT_SCHEMA_NAME)) {
      throw new IllegalStateException(ROOT_SCHEMA_NAME + " not found in " + specYaml);
    }

    Set<String> visited = new HashSet<>();
    visited.add(ROOT_SCHEMA_NAME);
    Deque<String> pending = new ArrayDeque<>();
    pending.add(ROOT_SCHEMA_NAME);

    Map<String, JsonNode> defs = new LinkedHashMap<>();
    JsonNode rootRewritten = null;
    while (!pending.isEmpty()) {
      String name = pending.removeFirst();
      if (!schemas.has(name)) {
        throw new IllegalStateException(
            "schema '" + name + "' referenced but not defined in " + specYaml);
      }
      JsonNode rewritten = rewriteRefs(schemas.path(name), visited, pending, json);
      if (name.equals(ROOT_SCHEMA_NAME)) {
        rootRewritten = rewritten;
      } else {
        defs.put(name, rewritten);
      }
    }

    ObjectNode out = json.createObjectNode();
    out.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    out.put("$id", SCHEMA_ID);
    out.setAll((ObjectNode) rootRewritten);
    if (!defs.isEmpty()) {
      ObjectNode defsNode = json.createObjectNode();
      defs.forEach(defsNode::set);
      out.set("$defs", defsNode);
    }

    Files.createDirectories(outputJson.getParent());
    json.writerWithDefaultPrettyPrinter().writeValue(outputJson.toFile(), out);
  }

  // Deep-copies a schema node, rewriting #/components/schemas/X refs to #/$defs/X
  // and queuing X for its own closure walk the first time it's seen.
  private static JsonNode rewriteRefs(
      JsonNode node, Set<String> visited, Deque<String> pending, ObjectMapper json) {
    if (node.isObject()) {
      ObjectNode copy = json.createObjectNode();
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        JsonNode value = field.getValue();
        if (field.getKey().equals("$ref")
            && value.isTextual()
            && value.asText().startsWith(REF_PREFIX)) {
          String refName = value.asText().substring(REF_PREFIX.length());
          if (visited.add(refName)) {
            pending.addLast(refName);
          }
          copy.put("$ref", "#/$defs/" + refName);
        } else {
          copy.set(field.getKey(), rewriteRefs(value, visited, pending, json));
        }
      }
      return copy;
    }
    if (node.isArray()) {
      var arr = json.createArrayNode();
      node.forEach(el -> arr.add(rewriteRefs(el, visited, pending, json)));
      return arr;
    }
    return node.deepCopy();
  }

  private AgentDefinitionSchemaGenerator() {}
}
