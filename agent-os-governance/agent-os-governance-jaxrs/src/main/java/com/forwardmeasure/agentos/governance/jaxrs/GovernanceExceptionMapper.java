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

import com.forwardmeasure.agentos.domain.InvalidLifecycleTransitionException;
import com.forwardmeasure.agentos.domain.WorkflowReleaseUnavailableException;
import com.forwardmeasure.agentos.governance.api.model.Problem;
import com.forwardmeasure.agentos.governance.application.AgentNotDraftException;
import com.forwardmeasure.agentos.governance.application.AgentNotFoundException;
import com.forwardmeasure.agentos.governance.application.DuplicateAgentCoordinatesException;
import com.forwardmeasure.agentos.governance.application.SelfApprovalNotAllowedException;
import com.forwardmeasure.agentos.governance.application.StaleRevisionException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.lang.System.Logger.Level;

// One shared mapper for every AgentGovernanceService failure mode, matching each real operation's
// documented response-code table exactly (see agent-governance-management.openapi.yaml's paths).
// SecurityException covers both of AgentActorResolver's fail-closed cases (no authenticated JWT,
// and no provisioned Actor row for an otherwise-valid subject) - deliberately not distinguished
// further, so a caller can't use the response to tell which case it hit.
@Provider
public class GovernanceExceptionMapper implements ExceptionMapper<RuntimeException> {

  private static final System.Logger LOG =
      System.getLogger(GovernanceExceptionMapper.class.getName());

  @Override
  public Response toResponse(RuntimeException exception) {
    int status;
    String title;
    if (exception instanceof AgentNotFoundException
        || exception instanceof WorkflowReleaseUnavailableException) {
      status = 404;
      title = "Not Found";
    } else if (exception instanceof DuplicateAgentCoordinatesException
        || exception instanceof InvalidLifecycleTransitionException
        || exception instanceof AgentNotDraftException) {
      status = 409;
      title = "Conflict";
    } else if (exception instanceof StaleRevisionException) {
      status = 412;
      title = "Precondition Failed";
    } else if (exception instanceof SelfApprovalNotAllowedException) {
      status = 403;
      title = "Forbidden";
    } else if (exception instanceof SecurityException) {
      status = 401;
      title = "Unauthorized";
    } else if (exception instanceof IllegalArgumentException) {
      status = 400;
      title = "Bad Request";
    } else {
      status = 500;
      title = "Internal Server Error";
      LOG.log(Level.ERROR, "Unhandled failure while serving a governance request", exception);
    }
    Problem problem = new Problem("about:blank", title, status).detail(exception.getMessage());
    return Response.status(status)
        .type(MediaType.valueOf("application/problem+json"))
        .entity(problem)
        .build();
  }
}
