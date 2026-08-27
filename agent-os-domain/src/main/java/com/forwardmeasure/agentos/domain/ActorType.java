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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public enum ActorType {
  HUMAN("human"),
  SERVICE("service");

  private static final Map<String, ActorType> BY_WIRE_VALUE =
      Arrays.stream(values()).collect(Collectors.toMap(t -> t.wireValue, t -> t));

  private final String wireValue;

  ActorType(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static ActorType fromWireValue(String wireValue) {
    ActorType match = BY_WIRE_VALUE.get(wireValue);
    if (match == null) {
      throw new IllegalArgumentException("unknown ActorType wire value: " + wireValue);
    }
    return match;
  }
}
