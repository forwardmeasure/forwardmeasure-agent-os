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
package com.forwardmeasure.agentos.policy.jaxrs;

import com.forwardmeasure.agentos.domain.PolicyEvaluationUnavailableException;
import com.forwardmeasure.agentos.domain.PolicyNotFoundException;
import com.forwardmeasure.agentos.policy.api.model.Problem;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.lang.System.Logger.Level;

// Mirrors agent-os-execution-jaxrs's own ExecutionExceptionMapper shape exactly.
// PolicyEvaluationUnavailableException maps to 503, not 500 - the policy engine being unreachable
// is an operational condition the caller should retry, not a defect in this service.
@Provider
public class PolicyExceptionMapper implements ExceptionMapper<RuntimeException> {

  private static final System.Logger LOG = System.getLogger(PolicyExceptionMapper.class.getName());

  @Override
  public Response toResponse(RuntimeException exception) {
    int status;
    String title;
    if (exception instanceof PolicyNotFoundException) {
      status = 404;
      title = "Not Found";
    } else if (exception instanceof PolicyEvaluationUnavailableException) {
      status = 503;
      title = "Service Unavailable";
    } else if (exception instanceof SecurityException) {
      status = 401;
      title = "Unauthorized";
    } else if (exception instanceof IllegalArgumentException) {
      status = 400;
      title = "Bad Request";
    } else {
      status = 500;
      title = "Internal Server Error";
      LOG.log(
          Level.ERROR, "Unhandled failure while serving a policy evaluation request", exception);
    }
    Problem problem = new Problem("about:blank", title, status).detail(exception.getMessage());
    return Response.status(status)
        .type(MediaType.valueOf("application/problem+json"))
        .entity(problem)
        .build();
  }
}
