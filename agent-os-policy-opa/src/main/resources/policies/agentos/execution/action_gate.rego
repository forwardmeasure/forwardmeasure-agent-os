# Licensed to the Apache Software Foundation (ASF) under one or more contributor license
# agreements. See the NOTICE file distributed with this work for additional information regarding
# copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with the License. You may obtain a
# copy of the License at https://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable
# law or agreed to in writing, software distributed under the License is distributed on an "AS IS"
# BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
# for the specific language governing permissions and limitations under the License.

# Starter policy for the agentos/execution/actionGate policyPath - the gate an agent's own
# reasoning loop calls, via an OpenWorkflow `call http` step, before acting on its own proposed
# action. Deliberately fail-closed: default outcome is REQUIRES_APPROVAL, never PERMITTED - a
# proposed action this policy doesn't explicitly recognize is escalated to a human, not silently
# allowed through. OPA's own input contract (see OpaPolicyEvaluator): input.agent is the
# AgentCoordinates this evaluation concerns, input.context is the caller-supplied opaque payload.
package agentos.execution.actionGate

import future.keywords.if

default outcome := "REQUIRES_APPROVAL"
default reason := "no rule matched this proposed action; escalating by default"
default obligations := []

materiality_threshold_usd := 50000

# A fund-switch recommendation below the materiality threshold proceeds without a human in the
# loop - the common case, so the agent isn't blocked on every routine rebalancing suggestion.
outcome := "PERMITTED" if {
	input.context.proposedAction.type == "recommend-fund-switch"
	input.context.proposedAction.amountUsd <= materiality_threshold_usd
}

reason := sprintf(
	"proposed switch of $%v is within the $%v materiality threshold",
	[input.context.proposedAction.amountUsd, materiality_threshold_usd],
) if {
	input.context.proposedAction.type == "recommend-fund-switch"
	input.context.proposedAction.amountUsd <= materiality_threshold_usd
}

# Above the threshold, a human must approve before the agent's recommendation is acted on -
# durably paused (AgentExecutionState.paused), not rejected outright.
outcome := "REQUIRES_APPROVAL" if {
	input.context.proposedAction.type == "recommend-fund-switch"
	input.context.proposedAction.amountUsd > materiality_threshold_usd
}

reason := sprintf(
	"proposed switch of $%v exceeds the $%v materiality threshold",
	[input.context.proposedAction.amountUsd, materiality_threshold_usd],
) if {
	input.context.proposedAction.type == "recommend-fund-switch"
	input.context.proposedAction.amountUsd > materiality_threshold_usd
}

obligations := ["audit:compliance"] if {
	input.context.proposedAction.type == "recommend-fund-switch"
	input.context.proposedAction.amountUsd > materiality_threshold_usd
}

# Read-only analysis actions never need a human in the loop.
outcome := "PERMITTED" if {
	input.context.proposedAction.type in {"fund-profile", "fund-risk-metrics", "fund-comparison"}
}

reason := "read-only analysis action, no materiality to assess" if {
	input.context.proposedAction.type in {"fund-profile", "fund-risk-metrics", "fund-comparison"}
}
