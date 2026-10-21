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
package com.forwardmeasure.agentos.actorbinding.micronaut;

import com.forwardmeasure.openworkflow.common.model.Problem;
import com.forwardmeasure.openworkflow.common.model.Violation;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.SerdeImport;

/**
 * Serde coverage for the platform's RFC 9457 {@link Problem}/{@link Violation} (from
 * forwardmeasure-platform-api-contracts) that every forwardmeasure-platform-micronaut-validation
 * handler returns - among them the 401/403 problem for a request Micronaut security turns away.
 * Nothing else introspects these imported classes, so without this the first such response fails
 * with "No serializable introspection present for type Problem" instead of returning its body. Same
 * fix forwardmeasure-entity-intelligence uses ({@code MicronautProblemModelIntrospection}).
 */
@Introspected(classes = {Problem.class, Violation.class})
@SerdeImport(Problem.class)
@SerdeImport(Violation.class)
public interface MicronautPlatformProblemIntrospection {}
