# Agent OS: why it exists and what to do with it (handover, 2026-10-12)

**For:** a fresh Claude or Codex session that will analyse this repository and report back to the
user. The user started `forwardmeasure-agent-os` some time ago and no longer remembers why. They
want to understand what it was for, what it does today, how it fits the rest of the platform, and
what should happen to it.

**This assignment is read-only analysis.** Its output is a written report to the user, nothing else.

## 1. Rules for this session

- **No changes:**
  - Don't edit, create, delete or move files in any repository; the report is the only output.
  - Don't run Maven, npm builds, Docker, kubectl or Helm.
  - Don't commit, stash or reset in git.
  - Reading files, `git log`, `git show`, `grep` and `find` are fine.
- **Off limits:** never search, read or mention `/home/pn/Documents/code/forwardmeasure/data-fabric`.
- **Code shows behaviour, not intent.** Label anything learned only from code as "current
  behaviour, intent unconfirmed". Only treat something as intent when a document or commit message
  says so.
- **Separate three kinds of statement:**
  - what you verified, citing file and line, or commit and date;
  - what you infer;
  - what only the user can answer.
- **Don't overclaim.** If something looks like a stub, say so and show the evidence: empty modules,
  TODOs, interfaces with no implementation, no tests, not built into an image, not deployed.
- **Write for a person:** plain sentences, no run IDs or private shorthand. When you ask the user
  something, make the question concrete and give examples of possible answers.

## 2. Background: the platform

All repositories live under `/home/pn/Documents/code/forwardmeasure/`. Verify anything you rely on.

- **Shared foundation:** every repository inherits from `forwardmeasure-platform`. It pins all
  versions, builds the whole stack through `reactor.xml` (run by `build.sh`), and holds the shared
  deployment config in `deploy/helmfile`: tenants, Keycloak, images.
- **Three frameworks, always:** every service is built three ways (Quarkus, Spring, Micronaut),
  from a framework-neutral core plus thin "framework bindings". This is a firm rule.
- **The commercial brand is Kriyagentic,** "ForwardMeasure's agentic evidence-intelligence
  platform, built on forwardmeasure-openworkflow and forwardmeasure-entity-intelligence".
  - **Markets:** anti-human-trafficking investigation (law-enforcement agencies lead), banks (KYC,
    AML, sanctions), wealth and asset managers, insurers, legal teams and journalism.
  - **Named agents:** the brand book (design system at https://claude.ai/artifact/HmmXyKjATFChiz8VYnpL5i)
    names working agents: Custodex, Indexical, Selfsame, Chronoscope, Nexograph, Emergraph,
    Populens, Exposcope, Veristate, Shockpath, Deconflux, Narratrace, Statebound and Safegress.
    Selfsame resolves entities; Safegress handles sensitivity and quarantine.
  - **A core rule:** "the model proposes; a person decides". Agent proposals never look like
    approved findings, and every approval names the approver and the policy version.

### The products

- **FOWF: `forwardmeasure-openworkflow`** (Apache-2.0). A workflow engine for the CNCF Serverless
  Workflow language.
  - **Execution:** durable execution on Kafka Streams (called OKS), plus a Pekko engine.
  - **Workflow definitions:** published as bundles and resolved by DID URL
    (`did:<method>:bundle:<domain>/<file>`).
  - **Human tasks:** a blotter, a workspace and leases.
  - **Workers:** correlated workers, including Kubernetes Jobs.
  - **OpenWorkflow Studio:** the web UI, with a canvas editor, executions and human tasks.
  - **Tenant administration:** Keycloak Organizations, a database per tenant, and capability packs.
- **FEI: `forwardmeasure-entity-intelligence`** (proprietary). Entity intelligence:
  - **Reference populations** such as WorldCheck, ingested and indexed in OpenSearch;
  - **a monitored population** of resolved entities;
  - **screening runs** and ad-hoc screening;
  - **dossiers, investigations and evidence**;
  - **FEI Studio**, its web UI.
  - Its workflows run on FOWF.
  - An **information-extraction agent** is planned, to work with FEI's investigation evidence.
    `forwardmeasure-platform/docs/information-extraction-agent-studio-handover-2026-10-12.md` says
    the agent's location is unknown, and that Agent OS is *not* established as its home.
- **FDS: `forwardmeasure-data-streaming`.** Data streaming and ingestion: Apache Camel routes, a
  Kafka Streams executor image, and work covering what Numaflow would otherwise provide. FEI's
  ingestion uses it. Kafka Streams code must not depend on Pekko.
- **FDE: `forwardmeasure-decision-engine`.** A decision engine with a gRPC lifecycle and Python gRPC
  client bindings. Deployments can switch it on or off (`FORWARDMEASURE_ENABLE_FDE`).
- **Shared libraries and apps:**
  - `forwardmeasure-authzen`: AuthZEN authorisation and Keycloak Organization claims;
  - `forwardmeasure-jpa`, `forwardmeasure-object-storage`, `forwardmeasure-testcontainers`,
    `forwardmeasure-nlp`, `forwardmeasure-entity-matching`, `forwardmeasure-database-migrations`;
  - `forwardmeasure-platform-operations`: the Platform Dashboard;
  - `forwardmeasure-presentation-system`: the shared UI design system used by OpenWorkflow Studio,
    FEI Studio and the Platform Dashboard.
- **Useful context:** in `forwardmeasure-platform/docs/`, read `platform-architecture-explained.md`,
  `forwardmeasure-engineering-manifesto.md`, `production-ui-delivery-architecture-audit.md` and
  `tenant-branding-design-2026-10-12.md`, plus each repository's own README and docs.

## 3. What is already known about Agent OS

- **Modules,** from the root `pom.xml`:
  - domain and schema: `agent-os-domain`, `agent-os-schema-validation`;
  - API: `agent-os-api-specifications`, `agent-os-api-language-bindings`;
  - integration: `agent-os-openworkflow-client`, `agent-os-inbound-protocol-mapping`,
    `agent-os-actor-bindings`;
  - control: `agent-os-governance`, `agent-os-execution`, `agent-os-policy`,
    `agent-os-policy-opa`, `agent-os-policy-drools`;
  - data: `agent-os-database-migrations`, `agent-os-database-migration-service`;
  - packaging and UI: `agent-os-studio`, `agent-os-deployments`;
  - tests: `agent-os-architecture-tests`, `agent-os-conformance-tests`.
- **`agent-os-studio` is an empty skeleton.** It has only POMs: a "runtime-configuration resource
  for the dashboard SPA", plus Quarkus, Spring and Micronaut bindings. Git shows no source file was
  ever committed there. There's no web app, and no image or deployment for it was found.
- **Git history for `agent-os-studio`:** `8ecf7a6` "Initial commit", `e96885d` "Updated API
  handling, other changes to support new features and bug fixes", `0ed40e5` "Incorporated latest
  FOFW and FDS updates".
- **It is built:** `forwardmeasure-platform/reactor.xml` lists the repository, so `build.sh` builds
  it.

## 4. What to investigate

1. **Stated intent:** the README, any docs, commit messages and dates across the whole history
   (`git log --stat`), and comments in the POMs, the API specifications and the code.
2. **The API surface:** in `agent-os-api-specifications`, which resources and operations exist,
   and what they say about the product's purpose.
3. **Module by module:** how much is real, partial, a stub or empty. That covers the code, tests,
   database schema and migrations.
4. **Integration modules:** what "inbound protocol mapping" and "actor bindings" mean here. Are they
   agent protocols such as MCP or A2A, or something else? How does
   `agent-os-openworkflow-client` use FOWF?
5. **Governance, policy and execution:** what they decide and enforce, including the OPA and
   Drools policy modules.
6. **Deployment and use:** do images get built (check `agent-os-deployments` for container-image
   settings)? Is there Helm or helmfile config anywhere? Does any other repository call Agent OS
   or depend on it? Search all repositories except `data-fabric`.
7. **Platform rules:** whether anything conflicts with the platform's stated rules (see the
   architecture docs and the UI delivery audit's invariants).

## 5. The report to write

Write it for the user, in under 1,500 words, with these sections:

1. **What Agent OS appears to be for:** the most likely original purpose and the evidence.
   Separate stated intent from inference.
2. **What exists today:** module by module, implemented, partial, stub or empty, with evidence.
   Say whether it builds into images and whether anything deploys or calls it.
3. **How it fits the rest of the platform:**
   - Does it overlap with FOWF (orchestration, human tasks, tenancy)?
   - Is it the natural home for the named agents and the planned information-extraction agent?
   - How does it relate to FDS, FDE and AuthZEN?
   - Does anything conflict with platform rules?
4. **What an Agent OS Studio would probably show,** if the product continues. Derive this from the
   API and domain, and label it as inference.
5. **Options and a recommendation:** for example, make it the platform's agent runtime (and say
   what is missing), fold parts into FOWF or FEI, or retire it. Give the cost and risk of each in
   plain terms.
6. **Questions only the user can answer:** concrete, with example answers.

Present the report in the chat. Write it to a file only if the user asks.

## 6. Starting the session

Start the new chat with:

> Read /home/pn/Documents/code/forwardmeasure/forwardmeasure-agent-os/docs/agent-os-purpose-analysis-handover-2026-10-12.md and do the analysis it describes.
