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
package com.forwardmeasure.agentos.execution.jpa.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

// An opaque, filter-bound keyset token for listAgentExecutions - mirrors the now-superseded
// forwardmeasure-agents repo's own A2aTaskPageToken shape (same PREFIX/digest(queryScope)/keyset
// document layout, same one-design-for-every-listing-endpoint intent the plan calls for), not
// invented fresh. digest(queryScope) binds the cursor to the exact filter set it was issued
// under - reusing a cursor against a different filter combination fails to decode rather than
// silently returning a wrong or unauthorized-looking page. This is scope-bound and tamper-evident
// (a client can't reuse a cursor across a different query), not tamper-proof - it carries no
// signing key, matching the real precedent exactly.
final class ExecutionCursor {

  private static final String PREFIX = "agent-executions:v1";

  private ExecutionCursor() {}

  record Position(OffsetDateTime createdAt, long id) {}

  static String encode(Position position, String queryScope) {
    Objects.requireNonNull(position, "position");
    String document =
        String.join(
            "\n",
            PREFIX,
            digest(queryScope),
            position.createdAt().toString(),
            Long.toString(position.id()));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(document.getBytes(StandardCharsets.UTF_8));
  }

  static Position decode(String token, String queryScope) {
    if (token == null || token.isBlank()) {
      return null;
    }
    try {
      String[] fields =
          new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\n", -1);
      if (fields.length != 4
          || !PREFIX.equals(fields[0])
          || !MessageDigest.isEqual(
              digest(queryScope).getBytes(StandardCharsets.US_ASCII),
              fields[1].getBytes(StandardCharsets.US_ASCII))) {
        throw new IllegalArgumentException();
      }
      return new Position(OffsetDateTime.parse(fields[2]), Long.parseLong(fields[3]));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("cursor is invalid or does not match the current filters");
    }
  }

  private static String digest(String queryScope) {
    Objects.requireNonNull(queryScope, "queryScope");
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(queryScope.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
