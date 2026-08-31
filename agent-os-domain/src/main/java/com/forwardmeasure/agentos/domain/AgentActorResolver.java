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

import java.util.function.Consumer;
import java.util.function.Function;

// The port every agent-os-{fw}-actor-binding implements: resolve the current request's
// AgentActor and run work within its tenant scope, as one atomic call - never a transparent
// filter, even on frameworks where that would be safe on its own. Verified against Quarkus
// RESTEasy Reactive's real dispatch behavior: the one safe precedent in this ecosystem opens/
// closes forwardmeasure-jpa's TenantScope inside the resource method body, not across a
// ContainerRequestFilter/ContainerResponseFilter pair (a real gap exists there - @PreMatching
// filters run before Quarkus's blocking-thread decision is made). Building every framework
// around this same explicit shape keeps the contract identical regardless of which framework is
// hosting - matches TenantScope's own call/run pairing, not invented fresh.
public interface AgentActorResolver {

  <T> T withActor(Function<AgentActor, T> work);

  default void withActor(Consumer<AgentActor> work) {
    withActor(
        actor -> {
          work.accept(actor);
          return null;
        });
  }
}
