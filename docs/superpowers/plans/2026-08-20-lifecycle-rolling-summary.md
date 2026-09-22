# Lifecycle Rolling Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the existing nine-section rolling summary while giving every summary fact a deterministic lifecycle, structured incremental operations, and lifecycle-aware garbage collection.

**Architecture:** The nine Markdown sections remain the prompt-facing information taxonomy. A new `SummaryItem` model stores subject, lifecycle, revision, evidence references, importance, and compaction age inside each section. The LLM proposes structured operations, while Java applies updates, supersedes old facts, resolves tasks, expires disposable context, and renders a bounded nine-section snapshot. `SessionMemory` and `ExecutionArtifact` remain authoritative runtime state; the rolling summary stays a lossy context projection.

**Tech Stack:** Java 17, Jackson, JUnit 5, Maven

---

## Specification

- Preserve the nine existing section titles and their stable order.
- Treat lifecycle as item metadata, not as replacement sections.
- Support `ADD`, `UPDATE`, `RESOLVE`, `SUPERSEDE`, `EXPIRE`, and `DELETE` operations.
- A resolved item retains final outcome, changed resources, verification result, and evidence references; disposable process narration is removed.
- Superseded facts do not appear as active facts. Keep only bounded audit metadata until lifecycle GC removes it.
- Stable decisions are never deleted merely because they were compacted many times.
- Active and unresolved items remain until explicitly resolved, superseded, or expired.
- Compaction count is a drift/GC trigger, not a deletion rule by itself.
- Legacy nine-section Markdown remains readable and is normalized into structured items.
- Malformed operation output must fail safely without discarding the previous summary.

### Task 1: Structured summary domain model

**Files:**
- Create: `src/main/java/com/devcli/memory/SummaryItem.java`
- Create: `src/main/java/com/devcli/memory/SummaryOperation.java`
- Modify: `src/main/java/com/devcli/memory/RollingSummary.java`
- Test: `src/test/java/com/devcli/memory/RollingSummaryTest.java`

- [ ] Write failing tests proving that all nine sections remain, items round-trip with lifecycle metadata, and legacy Markdown is migrated.
- [ ] Run `mvn -q '-Dtest=RollingSummaryTest' -DskipTests=false test` and verify the new tests fail because lifecycle APIs do not exist.
- [ ] Add immutable lifecycle and operation models with validation and safe defaults.
- [ ] Refactor `RollingSummary` to store per-section items while preserving `get`, `set`, `parse`, and stable nine-section rendering compatibility.
- [ ] Render structured JSON lines under each Markdown section; superseded entries retain bounded audit metadata without presenting the old value as active.
- [ ] Re-run `RollingSummaryTest` and verify it passes.

### Task 2: Deterministic incremental reducer

**Files:**
- Create: `src/main/java/com/devcli/memory/SummaryLifecycleReducer.java`
- Test: `src/test/java/com/devcli/memory/SummaryLifecycleReducerTest.java`

- [ ] Write failing tests for add, update, resolve, supersede, expire, delete, stale revision rejection, and malformed-output fallback.
- [ ] Run the reducer test and verify failure because the reducer does not exist.
- [ ] Implement Jackson parsing for a strict `{operations:[...]}` response and apply operations by section plus subject.
- [ ] Preserve the previous snapshot when parsing fails or an operation is invalid; never replace it with an empty model response.
- [ ] Ensure `RESOLVE` removes active process state and stores only the supplied final outcome and evidence references.
- [ ] Re-run the reducer test and verify it passes.

### Task 3: Lifecycle-aware garbage collection

**Files:**
- Modify: `src/main/java/com/devcli/memory/SummaryGarbageCollector.java`
- Test: `src/test/java/com/devcli/memory/SummaryGarbageCollectorTest.java`

- [ ] Write failing tests showing stable decisions survive high compaction counts, unresolved items remain, expired items are removed, superseded audit expires, and resolved items retain final verification.
- [ ] Run the GC test and verify the lifecycle cases fail.
- [ ] Age items on each committed compaction and apply status/importance/last-update rules before character truncation.
- [ ] Use compaction count only to trigger stronger cleanup of resolved and superseded entries; never delete stable or unresolved entries only because of age.
- [ ] Preserve the existing user-message collapse and low-priority section budget behavior for legacy content.
- [ ] Re-run the GC test and verify it passes.

### Task 4: Compactor integration

**Files:**
- Modify: `src/main/java/com/devcli/memory/ConversationHistoryCompactor.java`
- Test: `src/test/java/com/devcli/memory/ConversationHistoryCompactorTest.java`
- Test: `src/test/java/com/devcli/memory/ConversationHistoryCompactorStabilityTest.java`
- Test: `src/test/java/com/devcli/memory/ConversationHistoryCompactorSixSectionTest.java`

- [ ] Write failing tests proving incremental LLM output is applied as operations, malformed operations preserve the previous summary, and the Nth compaction performs lifecycle GC instead of re-summarizing the old summary as raw history.
- [ ] Run only the three compactor test classes and verify the new assertions fail.
- [ ] Update the initial/reduce prompts to produce structured nine-section items and the incremental prompt to produce strict operations.
- [ ] Normalize initial legacy Markdown through `RollingSummary`, apply incremental operations through `SummaryLifecycleReducer`, and retain compatibility for overridden test summaries.
- [ ] Replace periodic full re-summary with periodic lifecycle GC; keep the old setter as a deprecated compatibility alias.
- [ ] Run semantic guard after lifecycle rendering and retain post-compact `SessionMemory` restoration.
- [ ] Re-run the three compactor test classes and verify they pass.

### Task 5: Documentation and bounded verification

**Files:**
- Modify: `AGENTS.md`
- Modify: `README.md`
- Modify: `docs/agents-reference.md`
- Modify: `TODO.md`

- [ ] Document that nine sections are taxonomy while lifecycle belongs to facts.
- [ ] Document structured operations, lifecycle GC, malformed-output fallback, and the authority boundary with `SessionMemory`.
- [ ] Run the targeted rolling-summary and compactor tests only; do not run the full suite.
- [ ] Run `git diff --check`, inspect `git diff`, and inspect `git status --short`.
- [ ] Record unverified areas and remaining risks in `TODO.md`.
