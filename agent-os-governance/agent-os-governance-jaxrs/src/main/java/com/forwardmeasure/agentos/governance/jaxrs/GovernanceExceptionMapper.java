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
import com.forwardmeasure.authzen.AuthenticationRequiredException;
import com.forwardmeasure.authzen.AuthorizationDeniedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.lang.System.Logger.Level;

// One shared mapper for every AgentGovernanceService failure mode, matching each real operation's
// documented response-code table exactly (see agent-governance-management.openapi.yaml's paths).
// AgentActorResolver's fail-closed cases: no resolvable caller (AuthenticationRequiredException,
// 401) and an authenticated caller with no enabled actor in the tenant
// (AuthorizationDeniedException,
// 403 - unknown and disabled actors deliberately answer alike, so a caller can't tell which).
// Not final: Micronaut hosts it through a @Singleton subclass compiled with
// micronaut-jaxrs-processor (MicronautGovernanceExceptionMapper); Spring registers it explicitly.
@Provider
public class GovernanceExceptionMapper implements ExceptionMapper<RuntimeException> {

  private static final System.Logger LOG =
      System.getLogger(GovernanceExceptionMapper.class.getName());

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
    } else if (exception instanceof IllegalArgumentException) {
      status = 400;
      title = "Bad Request";
    } else {
      status = 500;
      title = "Internal Server Error";
      LOG.log(Level.ERROR, "Unhandled failure while serving a governance request", exception);
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
