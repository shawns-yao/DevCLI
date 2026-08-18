# Scoped Acceptance Evidence and Escalation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use test-driven development and execute this plan task-by-task. Do not commit or push unless the user explicitly requests it.

**Goal:** Bind acceptance criteria to DAG nodes, prove automated acceptance with actual Reviewer tool calls, and produce an explicit human-escalation result after bounded retries are exhausted.

**Architecture:** Add normalized `applies_to` targets to each criterion and filter criteria by the current step, while the final integration step rechecks the full set. Extend Reviewer protocol evaluation with observed tool names so declared TOOL verifiers cannot be satisfied by model-authored text alone. Preserve the bounded retry model and make its terminal state explicit instead of silently presenting a generic failure summary.

**Tech Stack:** Java 17, Jackson, JUnit 5, Maven

---

## Task 1: Criterion-to-DAG traceability

**Files:**
- Modify: `src/main/java/com/devcli/agent/AcceptanceCriterion.java`
- Modify: `src/main/java/com/devcli/agent/AcceptanceCriteriaPreflight.java`
- Modify: `src/main/java/com/devcli/agent/AgentOrchestrator.java`
- Modify: `src/main/java/com/devcli/agent/AgentCheckpoint.java`
- Modify: `src/main/resources/prompts/modes/team-planner.md`
- Test: `src/test/java/com/devcli/agent/AcceptanceCriteriaPreflightTest.java`
- Test: `src/test/java/com/devcli/agent/AgentOrchestratorTest.java`
- Test: `src/test/java/com/devcli/agent/AgentCheckpointTest.java`

- [x] Write failing tests for missing targets, unknown targets, normalized Planner IDs, step-local injection, and final integration full coverage.
- [x] Run each targeted test and confirm the current global-criteria behavior causes the failure.
- [x] Add `applies_to`, normalize original Planner IDs after step renumbering, and validate all targets before execution.
- [x] Filter Worker and Reviewer criteria by step; make final integration consume all criteria.
- [x] Persist targets in checkpoint protocol version 7 and migrate older criteria to final integration scope.
- [x] Run targeted tests until green.

## Task 2: Reviewer evidence binding

**Files:**
- Modify: `src/main/java/com/devcli/agent/TeamReviewerProtocol.java`
- Modify: `src/main/java/com/devcli/agent/AgentOrchestrator.java`
- Modify: `src/main/resources/prompts/modes/team-reviewer.md`
- Test: `src/test/java/com/devcli/agent/TeamReviewerProtocolTest.java`
- Test: `src/test/java/com/devcli/agent/AgentOrchestratorTest.java`

- [x] Write failing tests proving fabricated TOOL evidence is rejected when the declared verifier was not called.
- [x] Pass the current Review attempt's successful tool names into protocol evaluation.
- [x] Require every relevant TOOL criterion's declared verifier to appear in observed evidence; count an actually executed Pre-Review command as `execute_command`.
- [x] Keep HUMAN criteria pending and outside automatic pass claims.
- [x] Run Reviewer and Orchestrator tests until green.

## Task 3: Exhaustion escalation

**Files:**
- Modify: `src/main/java/com/devcli/agent/AgentOrchestrator.java`
- Test: `src/test/java/com/devcli/agent/AgentOrchestratorTest.java`

- [x] Write a failing test for the terminal output after Reviewer retries and in-place redo are exhausted.
- [x] Add a structured escalation section containing failed step, attempts, last reason, checkpoint ID, and allowed next actions.
- [x] Keep the checkpoint for resume or post-mortem and avoid automatic whole-graph replanning.
- [x] Run targeted tests until green.

## Task 4: Documentation and verification

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `TODO.md`
- Modify: `docs/agents-reference.md`
- Modify: `docs/interview-agent-architecture-review.md`

- [x] Run focused acceptance, Reviewer, checkpoint, CLI, and Orchestrator tests.
- [x] Run Maven test compilation.
- [x] Update architecture and interview documentation from actual behavior.
- [x] Run `git diff --check`, inspect scoped status, and record unverified areas.
