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
package com.forwardmeasure.agentos.execution.jaxrs;

import com.forwardmeasure.agentos.execution.api.AgentExecutionsApi;
import com.forwardmeasure.platform.server.jaxrs.CommaSeparatedQueryParameterFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Splits the execution API's comma-separated array query parameters - {@code listAgentExecutions}'s
 * {@code state}, {@code ?state=running,paused} - so Quarkus and Jersey don't answer them 404. The
 * platform's {@link CommaSeparatedQueryParameterFilter} applied to {@link AgentExecutionsApi}.
 */
@Provider
@PreMatching
public class AgentExecutionCommaSeparatedQueryParameterFilter
    extends CommaSeparatedQueryParameterFilter {
  public AgentExecutionCommaSeparatedQueryParameterFilter() {
    super(AgentExecutionsApi.class);
  }
}
