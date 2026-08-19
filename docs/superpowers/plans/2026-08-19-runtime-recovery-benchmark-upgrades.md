# Runtime Recovery Benchmark Upgrades Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use test-driven development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade Runtime event delivery to a resumable live SSE stream, attach typed recovery evidence to RunStore, and make checkout collaboration evaluation produce fair automatically retried paired runs.

**Architecture:** Preserve `runtime_events` as the durable event source and add race-free wait/notify streaming above it. Extend RunStore with metadata-only recovery evidence references instead of copying checkpoint, patch, or Side-Git contents into SQLite. Treat single-Agent and Plan evaluation as one attempt unit so infrastructure failure retries the complete pair and every attempt remains auditable.

**Tech Stack:** Java 17, JDK HttpServer/HttpClient, SQLite JDBC, JUnit 5, Maven

---

## Task 1: Fair paired benchmark retry

**Files:**
- Create: `src/test/java/com/devcli/benchmark/PairedBenchmarkRunner.java`
- Create: `src/test/java/com/devcli/benchmark/PairedBenchmarkRunnerTest.java`
- Modify: `src/test/java/com/devcli/benchmark/CheckoutCollaborationBenchmarkIT.java`

- [x] Write failing deterministic tests proving that an incomplete side retries the entire pair, stops at the first complete pair, preserves every attempt, and fails after the configured bound.
- [x] Run `mvn -q test -Dtest=PairedBenchmarkRunnerTest -DskipTests=false` and confirm RED for missing behavior.
- [x] Implement a package-private runner with an injected attempt function; default maximum attempts is 2 and explicit values are bounded to `[1, 5]`.
- [x] Update the checkout benchmark to create a fresh directory for every paired attempt and write all attempt metadata into one report. A result is valid only when both LLM runs complete in the same attempt.
- [x] Do not retry only one side, discard failed attempts, select by hidden-check score, or call a real model from deterministic tests.
- [x] Run `PairedBenchmarkRunnerTest` and `CheckoutCollaborationBenchmarkIT#contractTemplateShouldCompile` until GREEN.

## Task 2: Resumable live SSE stream

**Files:**
- Modify: `src/main/java/com/devcli/runtime/api/RuntimeThreadStore.java`
- Modify: `src/main/java/com/devcli/runtime/api/RuntimeApiServer.java`
- Modify: `src/test/java/com/devcli/runtime/api/RuntimeThreadStoreTest.java`
- Modify: `src/test/java/com/devcli/runtime/api/RuntimeApiServerTest.java`

- [x] Write failing tests proving replay-after-cursor, `Last-Event-ID` resume, delivery of an event appended after connection, heartbeat while idle, and prompt disconnect/server-shutdown cleanup.
- [x] Run the two Runtime tests and confirm RED for the live-stream behavior.
- [x] Add a race-free bounded event wait API: query after the cursor before waiting, signal after the SQLite commit, tolerate spurious wakeups, and return promptly on interruption or close.
- [x] Change the events endpoint to chunked `text/event-stream`: send replay first, flush each event, then tail new events; emit SSE comments as heartbeats without advancing the event cursor.
- [x] Resolve the initial cursor as the greater of a valid `after` query and valid `Last-Event-ID`; malformed values fall back safely.
- [x] Bound each database fetch and rely on blocking socket writes for backpressure. Client disconnect and server close must not retain subscriptions, threads, or per-client queues.
- [x] Keep the API bound to loopback and preserve existing authorization and event visibility rules.
- [x] Run `RuntimeThreadStoreTest,RuntimeApiServerTest` until GREEN.

## Task 3: RunStore recovery evidence references

**Files:**
- Modify: `src/main/java/com/devcli/runtime/store/RunStore.java`
- Modify: `src/main/java/com/devcli/runtime/store/SqliteRunStore.java`
- Modify: `src/main/java/com/devcli/runtime/RunCoordinator.java`
- Modify: `src/main/java/com/devcli/runtime/RunContext.java`
- Modify: `src/main/java/com/devcli/runtime/CancellationContext.java`
- Modify: `src/main/java/com/devcli/cli/Main.java`
- Modify: `src/main/java/com/devcli/session/SessionTreeService.java`
- Modify: `src/main/java/com/devcli/agent/AgentCheckpoint.java`
- Modify: `src/main/java/com/devcli/agent/WorkspaceCommitCoordinator.java`
- Modify: `src/main/java/com/devcli/snapshot/SnapshotService.java`
- Modify or create focused tests under `src/test/java/com/devcli/runtime/`, `agent/`, `snapshot/`, and `session/`.

- [x] Write failing store contract tests for idempotent upsert, state transition, ordered listing, and restart persistence of typed `CHECKPOINT`, `PATCH_JOURNAL`, and `SIDE_GIT` references.
- [x] Write failing integration tests proving one stable Run id flows from CLI execution into session persistence and recovery evidence.
- [x] Run focused tests and confirm RED for missing persistence and propagation.
- [x] Add `RecoveryEvidenceRef` with run/thread/branch identity, kind, stable logical key, normalized reference, SHA-256 when available, state, timestamps, and monotonic version. Store metadata only; never copy artifact contents or secrets into SQLite.
- [x] Add a dedicated SQLite table and migration-safe indexes. Upsert the same `(run_id, kind, logical_key)` idempotently and enforce documented state transitions.
- [x] Make the execution Run id available before work starts and reuse it when `SessionTreeService` records the turn; do not create a second unrelated turn id after execution.
- [x] Register checkpoint save/delete, patch prepare/terminal/rollback, and Side-Git pre/post snapshot references through an injected run-scoped evidence sink. Missing RunStore integration must degrade to `NO_OP` without changing local recovery behavior.
- [x] Recovery evidence persistence failure must surface as a Run event or warning and must not claim the underlying artifact was persisted when it was not.
- [x] Run the focused RunStore, session, checkpoint, workspace, snapshot, and CLI tests until GREEN.

## Task 4: Documentation and final verification

**Files:**
- Modify: `docs/phase-20-runtime-api.md`
- Modify: `docs/benchmark-evaluation.md`
- Modify: `docs/agents-reference.md`
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `TODO.md`

- [x] Document live SSE resume/heartbeat semantics, recovery reference boundaries, and fair whole-pair benchmark retries.
- [x] Run only the focused regression suite for the three upgrades; do not run the full test suite per user instruction.
- [x] Run `git diff --check`, inspect the complete diff and confirm the worktree contains no unrelated changes.
- [x] Do not commit, merge, or push without a new explicit user request.
