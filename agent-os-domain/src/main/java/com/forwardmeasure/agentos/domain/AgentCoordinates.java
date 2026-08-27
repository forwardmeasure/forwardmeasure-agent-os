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
package com.forwardmeasure.agentos.domain;

import java.util.Objects;
import java.util.regex.Pattern;

// Stable tenant-scoped agent identity, immutable after draft creation. Patterns verified against
// common-definitions.yaml's AgentCoordinates schema, not assumed.
public record AgentCoordinates(String namespace, String name, String version) {

  private static final Pattern SLUG = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
  private static final Pattern SEMVER = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");

  public AgentCoordinates {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(version, "version");
    if (!SLUG.matcher(namespace).matches()) {
      throw new IllegalArgumentException("namespace does not match required pattern: " + namespace);
    }
    if (!SLUG.matcher(name).matches()) {
      throw new IllegalArgumentException("name does not match required pattern: " + name);
    }
    if (!SEMVER.matcher(version).matches()) {
      throw new IllegalArgumentException("version does not match required pattern: " + version);
    }
  }
}
