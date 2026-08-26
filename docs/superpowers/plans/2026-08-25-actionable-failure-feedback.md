# Actionable Failure Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `test-driven-development` and execute each checkbox in order. This session executes inline because project policy does not authorize subagents.

**Goal:** Upgrade terminal failures from reason-only text to a shared contract containing reason, category, actionable suggestion, and four explicit next actions.

**Architecture:** Add one agent-domain `FailureFeedback` model as the classification and rendering source. User-facing Agent paths reuse it, while `AgentExecutionEngine` emits an additive structured Runtime event. Existing execution-state fields and checkpoint protocol remain unchanged.

**Tech Stack:** Java 17, JUnit 5, Jackson, Maven

---

### Task 1: Lock the feedback contract

**Files:**
- Create: `src/test/java/com/devcli/agent/FailureFeedbackTest.java`
- Modify: `src/test/java/com/devcli/runtime/api/RunEventJsonCodecTest.java`
- Modify: `src/test/java/com/devcli/agent/AgentExecutionEngineTest.java`

- [x] Add failing tests for all six requested categories.
- [x] Require reason, category, suggestion, and retry/manual/partial/rollback actions.
- [x] Require a structured `failure.guidance` Runtime event on budget exit.
- [x] Run the focused tests and confirm failure is caused by the missing contract.

### Task 2: Implement the shared model and event

**Files:**
- Create: `src/main/java/com/devcli/agent/FailureFeedback.java`
- Modify: `src/main/java/com/devcli/runtime/event/RunEvent.java`
- Modify: `src/main/java/com/devcli/runtime/api/RunEventJsonCodec.java`
- Modify: `src/main/java/com/devcli/agent/AgentExecutionEngine.java`

- [x] Implement deterministic category mapping and category-specific suggestions.
- [x] Keep four stable action types with truthful CLI instructions.
- [x] Emit the additive structured event without changing existing event fields.
- [x] Rerun focused tests.

### Task 3: Migrate user-facing exits

**Files:**
- Modify: `src/main/java/com/devcli/agent/Agent.java`
- Modify: `src/main/java/com/devcli/agent/SubAgent.java`
- Modify: `src/main/java/com/devcli/agent/PlanExecuteAgent.java`
- Modify: `src/main/java/com/devcli/agent/AgentOrchestrator.java`
- Modify: `src/test/java/com/devcli/agent/AgentOrchestratorTest.java`

- [x] Render shared feedback for budget and LLM failures.
- [x] Preserve partial Plan task output before the guidance block.
- [x] Include `/plan resume <checkpoint>` when a checkpoint exists.
- [x] Keep Reviewer retry, redo count, and last failure reason intact.

### Task 4: Documentation and delivery

**Files:**
- Modify: `AGENTS.md`
- Modify: `TODO.md`

- [x] Document the four-part failure contract and remaining heuristic-classification risk.
- [x] Run focused regression and `mvn -q -Pquick -DskipTests=false test`.
- [x] Run `git diff --check`, stale-reference searches, and final diff review.
- [x] Prepare a Chinese Conventional Commit; push status is recorded by Git delivery.
