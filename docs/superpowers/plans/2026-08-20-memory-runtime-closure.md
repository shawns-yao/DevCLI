# Memory Runtime Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the runtime gaps in the two-layer memory architecture without restoring the deprecated four-layer model.

**Architecture:** Keep `SessionMemory` as the current-task projection and `LongTermMemory` as the cross-session fact store. Add deterministic task boundaries, sequence-aware shared events, globally budgeted role views, cross-turn file-reference obligations, equivalent-fact deduplication, typed observation invalidation, rule migration/management, and a structured sensitive-confirmation continuation.

**Tech Stack:** Java 17, JUnit 5, Jackson, Maven

---

### Task 1: Session memory runtime contract

**Files:**
- Modify: `src/main/java/com/devcli/memory/SessionMemory.java`
- Modify: `src/main/java/com/devcli/memory/MemoryManager.java`
- Modify: `src/main/java/com/devcli/agent/Agent.java`
- Modify: `src/main/java/com/devcli/agent/AgentOrchestrator.java`
- Test: `src/test/java/com/devcli/memory/SessionMemoryArchitectureTest.java`
- Test: `src/test/java/com/devcli/memory/MemoryManagerTest.java`

- [x] Add explicit `beginTask/endTask` lifecycle and task identity.
- [x] Preserve `agentId/stepId/sequence` in normalized evidence.
- [x] Reject stale plan and step events by sequence and plan version.
- [x] Render immutable state first, then evidence by importance under one hard Token budget.
- [x] Compact critical and failure collections instead of allowing unbounded growth.
- [x] Run targeted SessionMemory and MemoryManager tests.

### Task 2: Cross-turn attachment evidence

**Files:**
- Modify: `src/main/java/com/devcli/agent/ContextReferenceGuard.java`
- Modify: `src/main/java/com/devcli/agent/AgentExecutionEngine.java`
- Test: `src/test/java/com/devcli/agent/AgentExecutionEngineTest.java`

- [x] Maintain a bounded reference registry across turns in one execution history.
- [x] Resolve content-oriented follow-ups against earlier file references.
- [x] Count wrong-path or missing reads toward a bounded terminal failure.
- [x] Preserve metadata-only requests without forced reads.
- [x] Run targeted execution-engine tests.

### Task 3: Long-term memory conflict semantics

**Files:**
- Modify: `src/main/java/com/devcli/memory/LongTermMemory.java`
- Modify: `src/main/java/com/devcli/memory/MemoryObservationConflictDetector.java`
- Modify: `src/main/java/com/devcli/memory/MemoryManager.java`
- Test: `src/test/java/com/devcli/memory/LongTermMemorySupersedeTest.java`
- Test: `src/test/java/com/devcli/memory/MemoryManagerTest.java`

- [x] Deduplicate equivalent same-subject facts before supersede.
- [x] Keep non-equivalent revisions and audit links unchanged.
- [x] Accept typed current-state observations before legacy Maven/Gradle inference.
- [x] Run targeted long-term memory tests.

### Task 4: Rule and sensitive-confirmation operations

**Files:**
- Modify: `src/main/java/com/devcli/memory/RuleContext.java`
- Modify: `src/main/java/com/devcli/cli/CliCommandParser.java`
- Modify: `src/main/java/com/devcli/cli/Main.java`
- Modify: `src/main/java/com/devcli/tool/ToolRegistry.java`
- Modify: `src/main/java/com/devcli/tool/provider/MemoryToolProvider.java`
- Test: `src/test/java/com/devcli/memory/RuleContextArchitectureTest.java`
- Test: `src/test/java/com/devcli/cli/CliCommandParserTest.java`

- [x] Import legacy pinned facts into a one-time migration report without silently treating them as rules.
- [x] Add `/rule list` and `/rule remove <id>`.
- [x] Return structured sensitive-confirmation data from `save_memory` and expose a deterministic continuation path.
- [x] Run targeted CLI, rule, and memory-tool tests.

### Task 5: Documentation and verification

**Files:**
- Modify: `AGENTS.md`
- Modify: `README.md`
- Modify: `docs/agents-reference.md`

- [x] Update only behavior that is actually implemented.
- [x] Run `git diff --check` and targeted Maven tests.
- [x] Inspect `git diff` and `git status --short` for unrelated changes.
