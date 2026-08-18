# Verifiable Acceptance Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use test-driven development and execute this plan task-by-task. Do not create commits or push unless the user explicitly requests it.

**Goal:** Make every Team acceptance criterion declare an executable verification path, reject ambiguous criteria before execution, and expose human-verification obligations for explicit approval.

**Architecture:** Extend the Team Planner contract with a typed verification method and verifier, validate criteria before DAG execution, and add a Team plan-review boundary before checkpoint creation. Keep automated Reviewer enforcement deterministic while carrying human-review obligations separately so the model cannot silently mark them as tool-verified.

**Tech Stack:** Java 17, Jackson, JUnit 5, Maven

---

## Task 1: Acceptance criterion contract and preflight validation

**Files:**
- Create: `src/main/java/com/devcli/agent/AcceptanceCriterion.java`
- Create: `src/main/java/com/devcli/agent/AcceptanceCriteriaPreflight.java`
- Create: `src/test/java/com/devcli/agent/AcceptanceCriteriaPreflightTest.java`

- [x] Write failing tests for missing verification method, missing verifier, missing expected evidence, unknown tool, duplicate id, and a valid human criterion.
- [x] Run the targeted test and confirm failures are caused by missing production types.
- [x] Implement the minimal typed criterion and deterministic preflight report.
- [x] Run the targeted test until green.

## Task 2: Planner protocol and bounded repair

**Files:**
- Modify: `src/main/resources/prompts/modes/team-planner.md`
- Modify: `src/main/java/com/devcli/agent/AgentOrchestrator.java`
- Modify: `src/main/java/com/devcli/agent/TeamPlannerProtocol.java`
- Modify: `src/test/java/com/devcli/agent/AgentOrchestratorTest.java`

- [x] Write failing tests showing criteria without verification metadata trigger Planner repair and remain blocked after repair exhaustion.
- [x] Run the targeted tests and confirm the old parser accepts the invalid criteria.
- [x] Parse `verification_method`, `verifier`, and `test_signal`; validate criteria together with the graph before returning a plan.
- [x] Include the concrete preflight failure in the bounded repair prompt.
- [x] Run the targeted tests until green.

## Task 3: Team execution-before-review boundary

**Files:**
- Modify: `src/main/java/com/devcli/agent/AgentOrchestrator.java`
- Modify: `src/main/java/com/devcli/cli/Main.java`
- Modify: `src/main/java/com/devcli/cli/PlanReviewInputParser.java`
- Modify: `src/test/java/com/devcli/agent/AgentOrchestratorTest.java`
- Modify: `src/test/java/com/devcli/cli/PlanReviewInputParserTest.java`

- [x] Write failing tests for execute, supplement/replan, and cancel decisions before checkpoint creation.
- [x] Add a Team plan-review handler with a noninteractive fail-closed default for unresolved human criteria.
- [x] Reuse the existing CLI plan-review interaction so users can execute, supplement, or cancel after seeing human-verification obligations.
- [x] Confirm execution never starts when criteria remain ambiguous or the user cancels.

## Task 4: Reviewer and checkpoint propagation

**Files:**
- Modify: `src/main/java/com/devcli/agent/TeamReviewerProtocol.java`
- Modify: `src/main/java/com/devcli/agent/AgentCheckpoint.java`
- Modify: `src/main/resources/prompts/modes/team-reviewer.md`
- Modify: `src/test/java/com/devcli/agent/TeamReviewerProtocolTest.java`
- Modify: `src/test/java/com/devcli/agent/AgentCheckpointTest.java`

- [x] Write failing compatibility and protocol tests for verification metadata persistence and Reviewer coverage.
- [x] Persist verification metadata in checkpoint protocol while keeping old checkpoint fields readable.
- [x] Require automated evidence for tool criteria and preserve human criteria as explicit delivery obligations.
- [x] Reject Reviewer output that mislabels a human criterion as tool-verified.

## Task 5: Documentation and focused verification

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `docs/agents-reference.md`
- Create: `docs/interview-agent-architecture-review.md`
- Modify: `TODO.md`

- [x] Run focused Planner, Orchestrator, Reviewer, checkpoint, and CLI parser tests.
- [x] Run Maven test compilation after focused tests pass.
- [x] Document the implemented chain, interview questions, design tradeoffs, and remaining limitations from actual code behavior.
- [x] Update `TODO.md` only after implementation status is known.
- [x] Check `git diff --check`, inspect scoped diffs, and record unverified areas without touching unrelated user changes.
