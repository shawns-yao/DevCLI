# State and Evidence Reliability Implementation Plan

> **For implementation:** REQUIRED SUB-SKILL: Use test-driven-development for every behavior change and verification-before-completion before claiming success.

**Goal:** Eliminate self-review, duplicate task state, volatile confirmation, weak-observation overwrite, unverified stale retrieval, incomplete checkpoint recovery, and silent rule/state conflicts.

**Architecture:** Keep `ExecutionArtifact`/`SessionMemory` as the task-state authority, attach typed strength to observations, persist user confirmation and bounded attempt evidence, validate stale retrieval against live files, and make reviewer/rule conflicts explicit. Existing public behavior remains the fallback unless an opt-in reviewer model is configured.

**Tech Stack:** Java 17, Maven, JUnit 5, Jackson, SQLite-backed project stores.

---

### Task 1: Observation strength and rule conflicts

**Files:**
- Modify: `src/main/java/com/devcli/memory/CurrentStateObservationSideChannel.java`
- Modify: `src/main/java/com/devcli/memory/MemoryObservationConflictDetector.java`
- Modify: `src/main/java/com/devcli/memory/MemoryManager.java`
- Create: `src/main/java/com/devcli/memory/RuleCurrentStateConflictDetector.java`
- Test: `src/test/java/com/devcli/memory/MemoryManagerTest.java`

1. Add failing tests proving LOW/MEDIUM observations do not supersede active memory, nested build files do not become project-wide facts, and rule/state conflicts emit a user-decision instruction.
2. Run `mvn test -Dtest=MemoryManagerTest -DskipTests=false` and confirm the new assertions fail for the missing behavior.
3. Add typed strength/scope propagation, conservative project-root inference, and rule conflict notices.
4. Re-run the targeted test until green.

### Task 2: Summary state has one authority

**Files:**
- Modify: `src/main/java/com/devcli/memory/RollingSummary.java`
- Modify: `src/main/java/com/devcli/memory/SummaryLifecycleReducer.java`
- Modify: `src/main/java/com/devcli/prompt/PromptAssembler.java`
- Test: `src/test/java/com/devcli/memory/RollingSummaryTest.java`
- Test: `src/test/java/com/devcli/memory/SummaryLifecycleReducerTest.java`

1. Add failing tests proving model-authored todo/current/next content is never rendered as task state.
2. Run the two targeted test classes and confirm RED.
3. Render compatibility headings with a fixed `SessionMemory` authority marker and reject lifecycle writes to projection-only sections.
4. Re-run targeted tests until green.

### Task 3: STALE retrieval validates live content

**Files:**
- Modify: `src/main/java/com/devcli/rag/VectorStore.java`
- Modify: `src/main/java/com/devcli/rag/CodeRetriever.java`
- Test: `src/test/java/com/devcli/rag/VectorStoreTest.java`
- Test: `src/test/java/com/devcli/rag/CodeRetrieverTest.java`

1. Extend the existing in-progress watcher tests with a failing STALE live-read case.
2. Run the two targeted RAG test classes and confirm the stale content failure.
3. Validate both DIRTY and STALE hits against the live file; retain a warning only when validation cannot complete.
4. Re-run targeted tests without reverting existing user changes.

### Task 4: Durable, idempotent confirmation tickets

**Files:**
- Create: `src/main/java/com/devcli/memory/MemoryConfirmationStore.java`
- Modify: `src/main/java/com/devcli/memory/MemoryManager.java`
- Test: `src/test/java/com/devcli/memory/MemoryManagerTest.java`

1. Add failing restart, expiry, and double-confirm tests using a temporary memory directory.
2. Confirm RED with the targeted memory test.
3. Persist sanitized pending tickets and terminal outcomes atomically, use a configurable longer TTL, and return the prior outcome on replay.
4. Re-run targeted tests until green.

### Task 5: AttemptDigest checkpoint recovery

**Files:**
- Modify: `src/main/java/com/devcli/memory/SessionMemory.java`
- Modify: `src/main/java/com/devcli/agent/AgentCheckpoint.java`
- Modify: `src/main/java/com/devcli/agent/AgentOrchestrator.java`
- Test: `src/test/java/com/devcli/agent/AgentCheckpointTest.java`
- Test: `src/test/java/com/devcli/agent/AgentOrchestratorTest.java`

1. Add failing round-trip and resume-context tests for bounded, step-scoped attempt digests.
2. Confirm RED with the two targeted agent tests.
3. Bump the checkpoint protocol, persist the bounded records, migrate older checkpoints to an empty list, and inject only relevant records on resume.
4. Re-run targeted tests until green.

### Task 6: Independent reviewer and counterexample protocol

**Files:**
- Modify: `src/main/java/com/devcli/agent/AgentOrchestrator.java`
- Modify: `src/main/java/com/devcli/agent/TeamPlanReviewProtocol.java`
- Modify: `src/main/java/com/devcli/cli/Main.java`
- Modify: `src/main/resources/prompts/modes/team-plan-reviewer.md`
- Test: `src/test/java/com/devcli/agent/TeamPlanReviewProtocolTest.java`
- Test: `src/test/java/com/devcli/agent/AgentOrchestratorTest.java`

1. Add failing tests for a distinct reviewer client and mandatory counterexamples for critical/high coverage.
2. Confirm RED with targeted protocol/orchestrator tests.
3. Add optional reviewer provider/model resolution with safe primary-client fallback and route all reviewer instances through it; enforce counterexample evidence in the review JSON.
4. Re-run targeted tests until green.

### Task 7: Integrated verification and documentation lifecycle

**Files:**
- Modify: `TODO.md` only after implementation is complete.

1. Run all modified test classes together.
2. Run `mvn test -Pquick` if targeted tests are green.
3. Run `mvn -q -DskipTests test-compile` and `git diff --check`.
4. Inspect `git diff` and `git status --short`, distinguish pre-existing RAG edits from this implementation, and record completed work in `TODO.md` without claiming unverified checks.
