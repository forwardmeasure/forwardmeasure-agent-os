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
package com.forwardmeasure.agentos.governance.application;

import java.util.UUID;

// approveAgent's real 403 response: "The authenticated actor is not authorized, or is the owner
// of the revision being approved." Distinct from AgentDefinition's own
// InvalidLifecycleTransitionException (a 409, wrong status) and from AgentNotFoundException (a
// 404, tenant/visibility) - this is specifically the maker-checker conflict-of-interest rule.
public final class SelfApprovalNotAllowedException extends RuntimeException {

  public SelfApprovalNotAllowedException(UUID agentId) {
    super("the reviewer must differ from the owner for agent " + agentId);
  }
}
