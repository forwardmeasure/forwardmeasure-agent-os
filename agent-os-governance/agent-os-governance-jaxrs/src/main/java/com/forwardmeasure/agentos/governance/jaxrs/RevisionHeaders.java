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
package com.forwardmeasure.agentos.governance.jaxrs;

// If-Match's real pattern (every generated *Api interface): ^"[0-9]+"$ - the numeric revision
// field used directly as the ETag value, quoted. Every generated interface already enforces the
// pattern via @Pattern before a resource method body runs, so parsing here never needs to handle
// a malformed value.
final class RevisionHeaders {

  private RevisionHeaders() {}

  static int parseIfMatch(String ifMatch) {
    return Integer.parseInt(ifMatch.substring(1, ifMatch.length() - 1));
  }

  static String etag(int revision) {
    return "\"" + revision + "\"";
  }
}
