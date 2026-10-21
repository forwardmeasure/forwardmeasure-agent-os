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
package com.forwardmeasure.agentos.actorbinding.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.platform.spring.security.ProblemAccessDeniedHandler;
import com.forwardmeasure.platform.spring.security.ProblemAuthenticationEntryPoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The one HTTP security policy every agent-os Spring service runs, matching the Quarkus ({@code
 * quarkus.http.auth.permission.*}) and Micronaut ({@code micronaut.security.intercept-url-map})
 * deployments: health is open (kubelet's probes send no token), every other path - an unknown one
 * too - needs a verified bearer token. A 401/403 here is the same RFC 9457 problem Quarkus and
 * Micronaut return (forwardmeasure-platform-spring-security), not Spring Security's empty body; the
 * handlers go to both {@code exceptionHandling} (no token) and {@code oauth2ResourceServer} (a
 * token that fails validation).
 *
 * <p>Lives in this shared binding, not per vertical, so a deployment that hosts more than one
 * vertical still gets exactly one chain. No tenant-scope filter: {@code
 * AgentActorResolver.withActor} opens and closes the tenant scope itself.
 */
@Configuration(proxyBeanMethods = false)
public class AgentOsSpringSecurityConfiguration {

  @Bean
  SecurityFilterChain agentOsSecurity(HttpSecurity http, ObjectProvider<ObjectMapper> mappers)
      throws Exception {
    ObjectMapper mapper = mappers.getIfAvailable(ObjectMapper::new);
    var unauthenticated = new ProblemAuthenticationEntryPoint(mapper);
    var denied = new ProblemAccessDeniedHandler(mapper);
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            exceptions ->
                exceptions.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied))
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    .authenticationEntryPoint(unauthenticated)
                    .accessDeniedHandler(denied)
                    .jwt(Customizer.withDefaults()))
        .build();
  }
}
