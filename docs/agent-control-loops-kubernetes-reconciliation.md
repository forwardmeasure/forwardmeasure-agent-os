# Agent Control Loops Using Kubernetes Reconciliation and OpenWorkflow

**OpenWorkflow Agent Architecture — a conceptual architecture for governed, bounded, evidence-driven agent execution.**

Author: Prashanth Nandavanam, CTO, ForwardMeasure, Inc. · 12 October 2026.
Scope: conceptual architecture, control, governance, convergence.

> In-repo transcription of the owner's design document for the Agent OS control-loop feature.
> This is the approved reference for how agents execute on the platform.

## Why running the business on agents is a different problem

Conversational interfaces (ChatGPT, Claude Code) are valuable for interactive work — exploratory
reasoning, code generation, ad-hoc inquiry. Running core business operations on autonomous agents is
different: these are regulated, revenue-bearing processes acting on the organisation's behalf with real
money and regulatory exposure. The question shifts from convenience to **accountability**. In AML, KYC,
loan underwriting and insurance claims, the unit of work is a **case, not a conversation**, and a case
carries obligations a chat session cannot meet:

1. **Durability across time.** A case may stay open days-to-months waiting on counterparties, documents,
   third-party data or scheduled reviews. A chat session lasts only as long as its connection; its state
   lives in that connection, not in a durable record. Persistence must be designed in.
2. **Structured state and deterministic guardrails.** Whether a party matches a sanctions list is never
   settled by sampling a probability distribution. Every step follows codified rules, data is validated
   deterministically, and every state transition is explicit, permitted by a defined state model, and
   reproducible on review.
3. **Human judgement as a first-class participant.** KYC depends on asynchronous review, escalation and
   segregation of duties (analyst dispositions, compliance officer independently approves). This means
   encoding the organisation's structure of authority: durable work queues, role-based permissions,
   deadlines, notification. Prompting a model for an opinion does not meet the requirement.
4. **Immutable auditability and provenance.** A chat transcript mixes instructions, speculation, tool
   output and decisions in one stream without recording which statements carried authority. An audit
   trail captures each decision as a distinct record: what was decided, by whom, under which policy
   version, on what evidence. Regulators expect an unbroken chain: who/what initiated a check, which
   policy version governed it, what evidence was considered, on what grounds an exception was granted.

Mission-critical deployments also need, among other things: coordinated multi-agent execution (A2A);
purpose-built agent-to-user interfaces (A2UI) for review and approval; integrated payment flows; and
budgets enforced at once across compute, external API consumption, and money. Tool invocation, model
reasoning, LLM calls and external integrations all run **inside a governed envelope** — the model does
not decide for itself when and how they happen.

## From conversation to control: the Kubernetes reconciliation model

The chat window is one entry point among several. What actually runs the process is a **persistent
control mechanism** that holds the case's intent across every reasoning call, external action, delay,
failure and human decision, and continually compares what the agent is meant to accomplish with what has
actually happened.

A Kubernetes controller holds a declared desired state, observes the actual state, and takes bounded
action to close the gap — and critically **never assumes an earlier action worked**; it observes again,
because the environment may have changed, an operation may have failed, or another actor may have
intervened. Rather than following a fixed script, it asks one question each pass: *given the intent and
what is now true, what action, if any, is justified?* That is exactly the discipline an agent needs in an
uncertain, changing, regulated environment.

Applied to agents, the model splits into three responsibilities:

- **An outer control loop.** Holds the case's intent and constraints, observes evidence and outcomes,
  assesses the remaining gap. Decides whether work continues, waits for a human, escalates, or has
  reached a legitimate end. **Policy, authority and budget are enforced here, outside the model.**
- **Bounded reasoning capabilities.** One or more models, each with a defined mandate, interpret
  ambiguous evidence and propose next actions. The control loop selects which capability to invoke, only
  where judgement adds value. No model can grant itself authority, override policy, spend beyond its
  allocation, or declare the work complete. Disagreement between models is evidence for the control loop
  to resolve, not a decision.
- **An inner deterministic execution loop.** A parameterised OpenWorkflow subworkflow with fixed steps,
  defined branching and bounded retry. It carries out one authorised action under an explicit contract
  (inputs, preconditions, budget, verification), confirms the real-world result, and returns a verified,
  typed outcome — which becomes new evidence for the next reconciliation cycle.

**The design rule: models propose, the control loop decides, the deterministic loop executes.** Both
loops are expressed in OpenWorkflow but are different kinds of workflow: the **inner loop is a fixed
procedure**; the **outer loop is a state-driven controller** whose path on each pass is chosen by
reconciliation against current state.

### Responsibility boundaries

| Element | Primary responsibility | Must NOT own |
|---|---|---|
| Outer control loop | Maintain intent & state; govern progress; allocate authority & resources; decide continue/terminate | Treat an unverified model proposal as an executable instruction |
| Bounded reasoning | Within its mandate: interpret evidence, identify open questions, propose next actions | Grant itself authority, override policy, overspend, declare completion, treat another model's agreement as approval |
| Inner deterministic loop | Validate & execute one authorised action; enforce operational controls; verify; record outcome | Reinterpret the overall goal or decide whether the agent has converged |

## Why agents need a control loop

A reasoning call is **episodic** — it produces an interpretation, plan or response for a given context;
it does not, by itself, guarantee intent remains stable, that the latest external state was observed,
that a previous action succeeded, or that further action is justified. Agent work also extends beyond
inference: waiting for events, requesting approval, retrying recoverable failures, coordinating
concurrent work, respecting deadlines, and stopping when evidence gain no longer justifies cost or risk.
These are **control** responsibilities needing persistent state and deterministic decisions around the
model.

**Failure modes without persistent control:** goal drift; unsafe/unauthorised action (a plausible
suggestion reaching a tool with no independent permission/policy/approval); runaway cost; oscillation
(repeating actions because nothing recognises unchanged state); premature completion (fluency mistaken
for a satisfactory outcome); stale/conflicting state; brittle recovery (blind replay, duplicate action);
weak accountability. A control loop converts these into explicit state and decisions — it does not make
probabilistic reasoning deterministic; it **contains** that reasoning in a lifecycle whose permissions,
resources, transitions and termination can be inspected and enforced.

## Kubernetes → agent control mapping

| Kubernetes | Agent control interpretation |
|---|---|
| Spec / desired state | Intent, scope, constraints, success criteria, acceptable terminal outcomes |
| Status / observed state | Evidence, action outcomes, external facts, human input, uncertainty, blockers |
| Conditions | Concurrent, evidence-backed assertions: readiness, policy, approval, budget, progress, convergence |
| Controller | The outer control loop that owns reconciliation and lifecycle decisions |
| Reconciliation | Assess the material gap between intent and current state, then a justified control decision |
| Managed action | A bounded intervention issued as an action contract, executed by the inner loop |
| Admission/policy controls | Guardrails, policy decisions, authority checks, approvals applied before action |
| Resource limits | Task budgets & per-action allocations: cost, time, tokens, calls, retries, compute, risk |
| Requeue | Continue after new evidence, a timer, an event, an approval, or a recoverable outcome |
| Terminal condition | Complete, unable to complete, suspend, escalate, cancel, or fault — with an explicit reason |

## Multiple reasoning capabilities

Reasoning is a **role**, not a single model. Each capability has a mandate: the questions it may
interpret, the context/tools it may see, the output schema it must return, the model class that serves
it. Benefits: mandate-specific models (entity-match adjudication, adverse-media assessment, document
interpretation, SAR narrative drafting); budget-aware selection (route routine interpretation to a
cheaper model, reserve a capable model for hard judgements); independent review (one proposes, another
critiques); model independence (intent/evidence/history persist in the control loop's state, not a
context window, so models can be upgraded/replaced per mandate without disrupting in-flight tasks).
**Adding models adds perspectives, not authority** — no model approves its own or another's proposal;
disagreement is recorded as evidence and resolved deterministically or escalated to an accountable
human. A model critic resembles segregation of duties but does not satisfy it where regulation requires
a named human approver.

## Outer control loop — the reconciliation cycle

The outer loop persists for the task's lifetime; each pass uses the latest state, not a replayed plan:

1. Load current intent, policy context, authority, control state, remaining budget.
2. Observe external state; collect outcomes from completed actions, events, human decisions.
3. Assess the material gap between intent and evidence-backed state.
4. Determine whether a terminal condition is already satisfied (no further action if so).
5. When judgement is required, select the reasoning capability whose mandate fits and invoke a bounded
   reasoning episode to interpret uncertainty and propose options.
6. Govern the preferred option through guardrails, policy, authority, approval, risk and budget checks.
7. Issue **one** bounded action contract to the deterministic loop; wait for a typed outcome.
8. Verify the outcome; update state and conditions; then continue, converge, suspend, or escalate.

Reasoning is omitted when the next transition is mechanically clear (wait for an approval, reject an
expired request, stop on a hard budget) — it is used where interpretation adds value, not as a mandatory
wrapper around every transition.

**Why control sits outside the model:** a prompt can inform reasoning but is not an enforceable authority
boundary. The capability that proposes an action must not be the sole authority that approves its own
exception, enlarges its own budget, or declares its evidence sufficient. The outer loop treats model
output as an **untrusted proposal** until it passes deterministic checks, and records the policy/budget
context used, so review can distinguish what the model suggested from what the system authorised. The
same rule applies between models.

## Inner deterministic execution loop

**Deterministic** means the *control decisions around* the action are explicit and repeatable — given the
same contract, policy decision and observed execution state, the loop applies the same validation,
timeout, retry, idempotency, compensation and result-classification rules. It does not mean external
systems always return the same result. This keeps environmental mutation outside the free-form reasoning
episode: the model may *suggest* sending a message or changing a record; the inner loop receives only an
authorised, typed, budgeted action and executes it under declared operational semantics.

### The action contract (boundary object between outer and inner loops)

| Element | Purpose |
|---|---|
| Action identity | Names the instance; supplies an idempotency/correlation identity |
| Purpose & expected state change | Why the action is still necessary; what observable change it intends |
| Typed inputs & scope | Constrains data, targets, tools, jurisdictions, operations |
| Preconditions | Facts/freshness/dependencies that must hold immediately before execution |
| Authority & approval | Principal, delegated permission, any human/service approval |
| Policy & guardrail context | Pins the decision, rule version, non-negotiable runtime constraints |
| Budget allocation | Limits for cost, time, model use, tool calls, retries, compute, risk |
| Verification requirements | Acceptable evidence, output schema, postconditions |
| Failure & compensation rules | Retryable vs terminal faults, safe rollback, indeterminate outcomes |

### Execution sequence

1. Validate the contract; reject malformed/incomplete/expired instructions.
2. Recheck preconditions, authority, policy decisions and required approvals against current state.
3. Reserve the approved budget; obtain only the credentials/resources this action needs.
4. Execute with explicit timeout, cancellation and idempotency behaviour.
5. Apply bounded retry/compensation only where the contract permits.
6. **Verify the external outcome** — do not infer success from a successful request.
7. Record evidence, side effects, actual resource consumption, applied policy context.
8. Return a typed outcome without deciding what the overall agent should do next.

Outcome classes: succeeded, no change required, rejected by policy, budget unavailable, awaiting
approval, failed with retry permitted, failed permanently, compensated, and **indeterminate** — the last
is essential when the system cannot establish whether an external side effect occurred; blind retry is
unsafe there.

## Governance: guardrails, policy, authority

Kept distinct so a broad policy statement never substitutes for a hard runtime boundary:
- **Guardrail** — what must never be violated (tool allowlists, data boundaries, output schemas, rate
  limits, timeouts, prohibited operations). Outer loop admits only conforming actions; inner loop
  enforces the operational boundary.
- **Policy** — is this action permissible in the present business/legal/risk context (jurisdiction,
  eligibility, data handling, retention, segregation of duties, risk thresholds). A versioned policy
  decision is obtained before action and rechecked if relevant context changes.
- **Authority** — who may request/approve/execute (delegated role, service identity, approval threshold,
  separation of proposer and approver). The outer loop establishes authority; the inner loop uses
  least-privilege credentials.

Controls apply at three points: **admission** (may it enter execution), **runtime enforcement** (stays in
scope while running), **postcondition** (is the result acceptable; does a side effect need remediation or
escalation). Every material decision records the proposed action, policy version, relevant facts,
decision, obligations and reason. An approval grants only the authority stated — it does not enlarge the
action beyond its guardrails or budget.

### Budget and resource management

Budget is part of intent and control state; it bounds how much autonomy the task may consume. Dimensions:
financial cost; model use (tokens, context size, model class, number of reasoning episodes); time
(deadline, duration, wait, per-action timeout); actions & retries; compute & throughput; data & access
scope; **risk budget** (permitted uncertainty, reversibility, potential impact); human attention. The
ledger follows **reserve → execute → settle**: the outer loop approves an allocation; the inner loop
reserves before execution, debits actual use, releases the unused amount — preventing concurrent actions
from each assuming the same remaining budget. Budget exhaustion is a **control condition**, not an
ordinary error: narrow the objective, choose a cheaper permitted method, request more budget, or
terminate as unable to complete — but **never silently weaken evidence standards** to fit the remaining
allowance.

### Human oversight

A normal control outcome: request approval before a high-impact action, ask for clarification on
ambiguous intent, escalate on contradictory evidence. A human decision enters observed state with scope,
authority, time and conditions. **Suspension** preserves the task while execution is intentionally paused;
**escalation** transfers a decision to an accountable authority. Either may lead to resumption, revised
intent, or a terminal outcome.

## State, progress, convergence

Reconciliation needs explicit representations, not a transcript. State categories: **intent**
(goal, scope, constraints, success criteria, authorised changes); **observed state** (evidence,
provenance, freshness, outcomes, external facts, human input, contradictions, uncertainty);
**conditions** (ready, evidence sufficient, policy satisfied, approval required, budget available,
progressing, blocked, converged — several true at once); **control state** (phase, gaps, selected action,
dependencies, approvals, deadlines, blockers, next review); **budget ledger**; **action history**
(proposals, admissions, contracts, attempts, side effects, verification, compensations, typed outcomes);
**policy context** (rules, versions, inputs, obligations, exceptions, approving authority).

**Progress** is a meaningful state change — reducing material uncertainty, satisfying a condition,
obtaining an approval, producing an intended side effect, or establishing a candidate approach cannot
work — not merely another reasoning call or tool invocation. Detect repeated action signatures and
equivalent plans; compare evidence gain across cycles; distinguish a transient fault from a flawed
strategy or impossible objective; apply bounded backoff/retry only when the contract classifies the fault
retryable; require a changed premise/method/scope before repeating an approach; escalate when
contradictions or side-effect ambiguity cannot be resolved within authorised limits. Recovery starts from
persisted state and **re-observes external systems** before resuming, compensating, or issuing a new
action — so a restart does not assume an interrupted activity did nothing.

**Convergence** must be an explicit, evidence-backed control decision, not the natural end of a sequence:
goal convergence (objective & success criteria satisfied by evidence and verified outcomes); governance
convergence (policy, approval, disclosure, accountability obligations satisfied with residual uncertainty
visible); operational convergence (no unresolved actions, ambiguous side effects, required compensations,
or stale observations). Terminal states are broader than success: completed, unable to complete,
escalated, suspended, cancelled, faulted — each with a reason, evidence, remaining uncertainty and any
required follow-up. **Completion is permitted only when all applicable convergence tests pass**; exhausted
budget, missing authority or irreducible uncertainty must not be presented as success.

## OpenWorkflow as the execution model

Kubernetes contributes reconciliation semantics; OpenWorkflow contributes a platform-neutral way to make
the controlled lifecycle explicit (observation, assessment, reasoning, governance, action, review,
waiting, termination as stages and decision boundaries). It supplies typed boundaries, controlled paths
(tasks/switches/loops/nested workflows), waiting & events, operational resilience (errors/retries/
timeouts/task states), interoperability (calls/events/functions to model, policy, tool, human services),
and audit visibility. It does **not** supply intelligence or governance — those stay with reasoning,
policy services and the control model; and a capability does not gain authority merely because the
workflow can invoke it.

**The two loops, in OpenWorkflow:**
- **Outer loop = a state-driven controller.** A recurring lifecycle whose graph permits reason, govern,
  act, wait, escalate, terminate; the path each pass is chosen by reconciliation against current state.
- **Inner loop = one fixed, parameterised subworkflow** whose input is the action contract; its structure
  is the same for every action, only the parameters change:

| Inner-loop element | OpenWorkflow construct | Behaviour |
|---|---|---|
| Fixed steps | `do` | The 8 steps run in order (validate, recheck, reserve, execute, retry/compensate, verify, record, return) |
| Defined branching | `switch` | Routes on the outcome class; every path ends in a typed result, never an open question |
| Bounded retry | `try`/`catch` + retry policy | Retryability & attempts come from the contract, not workflow defaults; an indeterminate outcome routes to verification or escalation, never retry |
| Waiting | `listen`/`wait` | Asynchronous results and approvals arriving while the action is in progress |
| Parameterisation | workflow input + `call` | The contract supplies identity, idempotency key, typed inputs, preconditions, policy-decision reference, budget, verification; the operation is a `call` whose target/args come from those parameters |

Determinism lives in the control semantics, not the external system; idempotency and compensation remain
action-specific and sit in the services the workflow calls, not the workflow definition. OpenWorkflow is
the explicit execution model within which reasoning, policy decisions, actions, events and human
judgement stay coherent and reviewable.

## Where agentic reconciliation differs from Kubernetes

It operates in an epistemic and social environment, not only against observable infrastructure, so the
analogy is extended where facts, goals and authority are less objective: intent may be incomplete,
ambiguous or revised by an authorised human; evidence may be partial, stale, contradictory, deceptive or
costly to obtain; several reasonable actions may exist with different information gain, cost and risk;
actions may affect people, legal rights, markets, communications or irreversible processes; convergence
may require an evidence-sufficiency judgement; authority may depend on role, purpose, jurisdiction,
customer mandate, approval and changing policy. Hence the model adds bounded reasoning, evidence
provenance, policy/authority decisions, risk budgets, human intervention and richer terminal outcomes —
preserving the discipline of reconciliation without pretending judgement reduces to an infrastructure
diff.

## Conceptual design principles

1. Represent intent separately from observed state; permit intent changes only through explicit authority.
2. Treat model output as a proposal until independent controls admit it.
3. Give each reasoning capability a defined mandate; treat agreement/disagreement as evidence, not approval.
4. Use a bounded action contract between mission-level control and environmental mutation.
5. Enforce guardrails, policy, authority and budget in code or trusted services outside the prompt.
6. Observe and verify action outcomes; never equate request acceptance with successful effect.
7. Record conditions concurrently so evidence, approval, budget and readiness stay distinguishable.
8. Measure progress as meaningful state change; detect repeated approaches with little evidence gain.
9. Design retries, idempotency, compensation and indeterminate outcomes before permitting consequential actions.
10. Treat human approval, clarification, suspension and escalation as first-class lifecycle paths.
11. Make convergence explicit, evidence-backed, and independent of the model's confidence or fluency.
12. Preserve the complete control record: intent, observations, proposals, decisions, policies, budgets, actions, reasons.
13. Implement the inner loop as a fixed, parameterised workflow driven by the action contract; the outer loop as a state-driven lifecycle.
14. Use OpenWorkflow to expose lifecycle and decision boundaries without confusing orchestration with intelligence.

**Conclusion.** The Kubernetes control loop is a sensible blueprint: it replaces a fixed plan with
persistent reconciliation between intent and current state. The outer loop supplies stable intent,
explicit authority, policy enforcement, budget control, progress assessment, recovery, human intervention
and termination; the inner loop executes one authorised action under a contract, verifies the outcome,
and returns evidence. OpenWorkflow makes both lifecycles explicit — a fixed, parameterised procedure for
the inner loop and a state-driven controller for the outer loop. Reasoning may be distributed across
several models, each with its own mandate, without any gaining authority. The pattern preserves
probabilistic reasoning inside an inspectable, governable control system.

**Sources:** Kubernetes controller docs; Kubernetes API conventions for spec/status/conditions; the Open
Workflow Specification DSL; the NIST AI Risk Management Framework.
