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
package com.forwardmeasure.agentos.execution.application;

import java.util.List;
import java.util.Objects;

// listAgentExecutions' real contract: "Cursor-paginated, not offset-paginated - this listing is
// time-ordered and actively appended to, and keyset pagination avoids the skipped/duplicate-row
// problem offset pagination has under concurrent inserts." Deliberately not governance's own
// Page<T> (offset/limit/totalCount) - a genuinely different pagination shape, matching the wire
// AgentExecutionPage's own (items, nextCursor) fields exactly, no total count.
public record CursorPage<T>(List<T> items, String nextCursor) {

  public CursorPage {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
  }
}
