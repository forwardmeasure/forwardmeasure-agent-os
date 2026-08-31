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
package com.forwardmeasure.agentos.policy.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

// No entities of policy's own - unlike AgentExecutionSpringApplication, this scans only
// forwardmeasure-jpa-identity's Actor entity, needed solely for agent-os-spring-actor-binding's
// own DB-backed actor lookup - this service persists nothing of its own.
@SpringBootApplication(scanBasePackages = "com.forwardmeasure.agentos")
@EntityScan(basePackages = "com.forwardmeasure.jpa.identity.entity")
public class AgentPolicySpringApplication {

  public static void main(String[] arguments) {
    SpringApplication.run(AgentPolicySpringApplication.class, arguments);
  }
}
