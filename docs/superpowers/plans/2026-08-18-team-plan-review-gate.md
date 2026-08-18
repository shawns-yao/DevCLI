# Team Plan Review Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use test-driven development and execute this plan task-by-task. Do not commit or push unless the user explicitly requests it.

**Goal:** Add an independent Reviewer gate that semantically reviews a Team DAG and its acceptance criteria before checkpoint creation or Worker execution.

**Architecture:** Keep deterministic graph and acceptance preflight as the first gate. Run the existing Reviewer identity in an isolated, no-tool plan-review mode with a dedicated prompt and strict JSON protocol. Feed semantic rejection back to Planner through the existing bounded repair loop; fail closed on Reviewer failure. Re-review pending checkpoint plans during resume because older checkpoints do not contain a plan-review attestation.

**Tech Stack:** Java 17, Jackson, JUnit 5, Maven

---

## Task 1: Plan review protocol

**Files:**
- Create: `src/main/java/com/devcli/agent/TeamPlanReviewProtocol.java`
- Create: `src/test/java/com/devcli/agent/TeamPlanReviewProtocolTest.java`
- Create: `src/main/resources/prompts/modes/team-plan-reviewer.md`
- Modify: `src/main/java/com/devcli/prompt/PromptMode.java`

- [x] Write failing tests for malformed JSON, missing requirement coverage, missing criterion review, ambiguous or unverifiable criteria, reported issues, and a valid approval.
- [x] Run the protocol tests and confirm RED.
- [x] Implement strict parsing and bounded feedback formatting.
- [x] Add a dedicated plan-review prompt that forbids tools and requires requirement-to-step-to-criterion traceability.
- [x] Run the protocol tests until green.

## Task 2: Isolated no-tool Reviewer execution

**Files:**
- Modify: `src/main/java/com/devcli/agent/SubAgent.java`
- Test: `src/test/java/com/devcli/agent/SubAgentTest.java`

- [x] Write a failing test proving plan review uses the dedicated system prompt and exposes no tool definitions.
- [x] Add an isolated plan-review execution entry that does not reuse execution-review conversation history.
- [x] Keep normal Worker and execution Reviewer behavior unchanged.
- [x] Run the SubAgent tests until green.

## Task 3: Pre-execution gate and repair loop

**Files:**
- Modify: `src/main/java/com/devcli/agent/AgentOrchestrator.java`
- Modify: `src/main/java/com/devcli/cli/Main.java`
- Test: `src/test/java/com/devcli/agent/AgentOrchestratorTest.java`
- Test: `src/test/java/com/devcli/cli/MainTeamPlanReviewTest.java`

- [x] Write a failing integration test proving a semantic rejection returns to Planner before checkpoint creation and Worker execution.
- [x] Write a failing test proving malformed or failed plan review cannot silently pass.
- [x] Write a failing resume test proving pending checkpoints are reviewed before execution.
- [x] Add plan review to the bounded plan generation loop after deterministic validation.
- [x] Surface the approved Reviewer summary in the user-facing plan confirmation.
- [x] Re-review pending checkpoint plans and require a new task when rejected.
- [x] Run Orchestrator and CLI tests until green.

## Task 4: Documentation and verification

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `TODO.md`
- Modify: `docs/agents-reference.md`
- Modify: `docs/interview-agent-architecture-review.md`
- Modify: `docs/interview-agent-current-vs-production-qa.md`

- [x] Correct the previous claim that CLI confirmation already constituted Reviewer plan review.
- [x] Document the deterministic preflight, semantic Reviewer gate, human decision, and execution Reviewer as separate stages.
- [x] Run focused protocol, SubAgent, Orchestrator, CLI, and checkpoint tests.
- [x] Run Maven test compilation.
- [x] Run `git diff --check` and inspect all scoped changes.
