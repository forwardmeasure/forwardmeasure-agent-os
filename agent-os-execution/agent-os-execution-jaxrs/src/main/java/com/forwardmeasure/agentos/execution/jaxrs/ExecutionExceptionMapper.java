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

import com.forwardmeasure.agentos.domain.InvalidExecutionTransitionException;
import com.forwardmeasure.agentos.execution.api.model.Problem;
import com.forwardmeasure.agentos.execution.application.DuplicateIdempotencyKeyException;
import com.forwardmeasure.agentos.execution.application.ExecutionNotFoundException;
import com.forwardmeasure.agentos.execution.application.StaleExecutionRevisionException;
import com.forwardmeasure.agentos.governance.application.AgentNotFoundException;
import com.forwardmeasure.authzen.AuthenticationRequiredException;
import com.forwardmeasure.authzen.AuthorizationDeniedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.lang.System.Logger.Level;

// Mirrors agent-os-governance-jaxrs's own GovernanceExceptionMapper shape exactly. Reuses
// governance's own AgentNotFoundException directly for startAgentExecution's real 404 ("No
// published agent release exists at the given coordinates") - that's the exact exception
// AgentGovernanceService.getRelease already throws, and this module already depends on
// agent-os-governance-application for the release lookup itself, so importing its exception type
// too is not a layering violation, just using the one real source of that failure mode.
// Not final: Micronaut hosts it through a @Singleton subclass compiled with
// micronaut-jaxrs-processor; Spring registers it explicitly in its ResourceConfig.
@Provider
public class ExecutionExceptionMapper implements ExceptionMapper<RuntimeException> {

  private static final System.Logger LOG =
      System.getLogger(ExecutionExceptionMapper.class.getName());

  @Override
  public Response toResponse(RuntimeException exception) {
    if (exception instanceof AuthenticationRequiredException) {
      // No resolvable caller: a missing/invalid JWT or one without exactly one active
      // Organization (KeycloakOrganizationClaims.extract). The same challenge the HTTP layer
      // sends for a request with no token at all.
      return problem(401, "Unauthorized", exception.getMessage())
          .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
          .build();
    }
    if (exception instanceof AuthorizationDeniedException) {
      // An authenticated caller without tenant access here (no enabled actor) - the detail
      // carries only the correlation id the reason was logged under.
      return problem(403, "Forbidden", exception.getMessage()).build();
    }
    if (exception instanceof WebApplicationException web) {
      // A JAX-RS runtime's own HTTP failure (no such route, method not allowed, unacceptable
      // media type, ...) keeps its status, as an RFC 9457 problem - never a 500.
      Response.StatusType webStatus = web.getResponse().getStatusInfo();
      return problem(webStatus.getStatusCode(), webStatus.getReasonPhrase(), exception.getMessage())
          .build();
    }
    int status;
    String title;
    if (exception instanceof ExecutionNotFoundException
        || exception instanceof AgentNotFoundException) {
      status = 404;
      title = "Not Found";
    } else if (exception instanceof DuplicateIdempotencyKeyException
        || exception instanceof InvalidExecutionTransitionException
        || exception instanceof IllegalStateException) {
      status = 409;
      title = "Conflict";
    } else if (exception instanceof StaleExecutionRevisionException) {
      status = 412;
      title = "Precondition Failed";
    } else if (exception instanceof IllegalArgumentException) {
      status = 400;
      title = "Bad Request";
    } else {
      status = 500;
      title = "Internal Server Error";
      LOG.log(Level.ERROR, "Unhandled failure while serving an execution request", exception);
    }
    return problem(status, title, exception.getMessage()).build();
  }

  private static Response.ResponseBuilder problem(int status, String title, String detail) {
    Problem problem = new Problem("about:blank", title, status).detail(detail);
    return Response.status(status)
        .type(MediaType.valueOf("application/problem+json"))
        .entity(problem);
  }
}
