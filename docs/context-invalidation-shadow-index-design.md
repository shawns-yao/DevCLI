# Context Invalidation and Shadow Index Design

## Status

This document describes future production-grade design. It is not implemented in the current runtime.

Current DevCLI behavior:

- `WorkingMemory` stores task state, volatile facts, and recent tool evidence for the current session.
- `ConversationHistoryCompactor` controls the real LLM message window.
- `CodeIndex` builds RAG index data from code chunks and JavaParser code relations.
- `CodeRetriever` reads the active index for semantic, keyword, and graph-expanded retrieval.
- Multi-Agent Workers can run in parallel, and `ResourceLeaseManager` blocks concurrent writes to the same file.

Missing today:

- No active `ContextInvalidation（上下文失效）` event is sent to running Workers when a symbol changes.
- No `ShadowIndex（影子索引）` exists for incremental index rebuild and atomic swap.
- No `SymbolVersion（符号版本）` is attached to Worker prompt context.
- No stale-context write barrier rejects a Worker that writes code based on an old symbol view.

## Problem

In Multi-Agent execution, the hardest failure is not syntax error. It is stale context.

Example:

```text
Worker A changes OrderService.createOrder(CreateOrderRequest)
-> Worker B still has old prompt context: OrderService.createOrder(User, OrderDTO)
-> Worker B writes syntactically valid Controller code against the old signature
-> compile may fail, or worse, compile passes through an overload but behavior is wrong
```

The system needs two production-grade capabilities:

- `ContextInvalidation（上下文失效）`: when code structure changes, affected Agent memory and prompt context must be marked stale before more writes happen.
- `ShadowIndex（影子索引）`: RAG indexing must update incrementally without letting Workers retrieve a half-built or stale mixed index.

## Goals

- Detect symbol-level changes after Worker writes.
- Map changed symbols to affected files, DAG nodes, Workers, and memory entries.
- Notify or stop running Workers whose context depends on changed symbols.
- Prevent stale Workers from writing files without refreshing context.
- Keep RAG retrieval versioned and atomic.
- Control token pressure by promoting only high-risk changes into prompt context.

## Non-Goals

- Do not trust LLM self-report as the source of truth for symbol changes.
- Do not rebuild the entire vector index synchronously after every file write.
- Do not inject full change history into every Worker prompt.
- Do not mutate DAG state directly from JavaParser output; structural changes still go through `ChangeRequest（变更请求）` and `GraphPatch（图补丁）`.
- Do not replace compile/test/Reviewer gates with AST analysis.

## Core Concepts

- `ContextEpoch（上下文版本）`: monotonically increasing version of the context snapshot visible to Agents.
- `SymbolVersion（符号版本）`: version attached to a class, method, field, constructor, or interface declaration.
- `IndexEpoch（索引版本）`: version of the active RAG index.
- `ContextDependency（上下文依赖）`: symbols, files, and chunks that were injected into a Worker prompt or retrieved by tools.
- `ContextInvalidationEvent（上下文失效事件）`: structured event proving that a context dependency changed.
- `StaleWorker（过期 Worker）`: a running Worker whose prompt or retrieved evidence depends on invalidated symbols.
- `StaleWriteBarrier（过期写入屏障）`: tool-layer guard that blocks writes from a stale Worker until it refreshes context.
- `ActiveIndex（活跃索引）`: current index used by `CodeRetriever`.
- `ShadowIndex（影子索引）`: isolated next index built from dirty files and changed relations.
- `AtomicIndexSwap（原子索引切换）`: compare-and-swap promotion of `ShadowIndex` to `ActiveIndex`.

## High-Level Flow

```text
Worker writes file
-> ResourceLeaseManager checks file lease
-> JavaParser parses changed file
-> SymbolDiff compares old/new declarations
-> ImpactAnalyzer finds affected symbols, files, tasks, Workers, and RAG chunks
-> ContextInvalidationEvent is appended
-> running Workers with matching ContextDependency become stale
-> StaleWriteBarrier blocks further writes from stale Workers
-> dirty files enter ShadowIndex rebuild queue
-> ShadowIndex builds new chunks, relations, and embeddings
-> AtomicIndexSwap promotes complete IndexEpoch
-> refreshed Workers retrieve from the new IndexEpoch
```

## Context Invalidation Design

### Dependency Capture

Every Worker should carry a `WorkerContextManifest（Worker 上下文清单）`.

The manifest records:

- `task_id`: DAG task or Multi-Agent step id.
- `plan_version`: plan version used when the Worker started.
- `context_epoch`: context version injected into its prompt.
- `index_epoch`: RAG index version used by retrieval.
- `prompt_symbols`: symbols explicitly injected into the prompt.
- `retrieved_chunks`: RAG chunks returned by `search_code`.
- `read_files`: files read through tools.
- `write_files`: files written through tools.
- `assumptions`: public signatures or config keys the Worker depended on.

This turns memory from untracked text into versioned evidence.

### Symbol Change Detection

After a write to Java source:

```text
before SymbolIndex + after JavaParser AST
-> SymbolDiff
-> StructuralChangeEvent
```

`SymbolDiff（符号差异）` should detect:

- added, removed, or renamed class.
- method signature change.
- constructor signature change.
- field type or visibility change.
- interface method change.
- inheritance or implementation change.
- annotation change for serialization, routing, persistence, or dependency injection.

When JavaSymbolSolver is available, impact analysis uses resolved symbols. When resolution fails, the system degrades to conservative file-level impact.

### Invalidation Rules

An invalidation event must cite evidence:

```text
event_id: inv_42
context_epoch: 18 -> 19
reason: METHOD_SIGNATURE_CHANGED
changed_symbol: OrderService.createOrder(CreateOrderRequest)
previous_symbol: OrderService.createOrder(User, OrderDTO)
changed_file: src/main/java/.../OrderService.java
affected_workers: [task_4]
affected_tasks: [task_4, task_7]
affected_chunks: [chunk_103, chunk_188]
```

The Orchestrator handles affected Workers by state:

- `pending`: update dependency edges or prompt seed before starting.
- `running`: mark `STALE_CONTEXT（上下文过期）`, interrupt if the task owns risky write leases, otherwise require refresh before next write.
- `completed`: mark as `STALE_COMPLETED（已完成但过期）` only when its accepted output depended on the changed symbol.
- `reviewing`: force Reviewer to receive the invalidation event and re-check affected acceptance criteria.

### Stale Write Barrier

`StaleWriteBarrier（过期写入屏障）` belongs in the tool execution path, near `ResourceLeaseManager`.

Before `write_file`:

```text
current worker context_epoch must be >= latest invalidation epoch for its dependencies
current worker index_epoch must be valid for retrieved chunks it is relying on
otherwise reject write with STALE_CONTEXT
```

This avoids the dangerous sequence:

```text
Worker sees invalidation too late
-> still writes old-signature code
-> later merge or compile has to discover the damage
```

The rejection should not be silent. It becomes an explicit repair path:

```text
STALE_CONTEXT
-> refresh affected symbols and files
-> update Worker prompt suffix
-> retry current step or emit ChangeRequest
```

### Notification Model

Running Workers should not rely on passive memory polling.

The Orchestrator should expose an `InvalidationBus（失效事件总线）`:

- append-only event log for deterministic replay.
- per-Worker subscription by task id and dependency keys.
- bounded queue so one noisy file does not flood every Worker.
- idempotent delivery using `event_id`.

Workers do not directly mutate shared state after receiving an event. They transition through controlled states:

```text
RUNNING -> STALE_CONTEXT -> REFRESHING_CONTEXT -> RUNNING
RUNNING -> STALE_CONTEXT -> BLOCKED_BY_GRAPH_PATCH
RUNNING -> STALE_CONTEXT -> FAILED_RETRYABLE
```

## Shadow Index Design

### Why Shadow Index

RAG index freshness has two conflicting requirements:

- Workers need the newest code structure after writes.
- Workers must not retrieve from a half-updated index.

`ShadowIndex（影子索引）` solves this by rebuilding changes outside the active read path.

### Index States

```text
ActiveIndex(epoch=N)
ShadowIndex(epoch=N+1, status=BUILDING)
ShadowIndex(epoch=N+1, status=VALIDATED)
ActiveIndex(epoch=N+1) after AtomicIndexSwap
```

`CodeRetriever` always reads one complete `ActiveIndex（活跃索引）`.

### Dirty File Queue

Every accepted write produces a `DirtyFileEvent（脏文件事件）`:

- path.
- content hash before/after.
- writer task id.
- changed symbols.
- affected relation keys.
- priority.

Priority is higher when:

- public method signature changed.
- interface or superclass changed.
- route/controller/mapper/config file changed.
- compile failed after write.
- pending Worker depends on the changed file.

### Incremental Rebuild

`ShadowIndexBuilder（影子索引构建器）` should:

- re-parse dirty Java files with JavaParser.
- rebuild chunks only for dirty files.
- delete stale chunks for changed or removed files.
- recompute relations touching changed symbols.
- reuse embeddings for unchanged chunks by content hash.
- enqueue embedding jobs for changed chunks.
- build a complete candidate `IndexEpoch`.

The rebuild is asynchronous but bounded. A Worker affected by a high-risk symbol change should block on the relevant index shard, not wait for a full repository rebuild.

### Atomic Swap

Promotion requires:

- all dirty files for the target epoch processed.
- changed chunks embedded or explicitly marked keyword-only fallback.
- relation graph validated.
- `base_epoch` still equals current `ActiveIndex` epoch.

If another swap already advanced the index, the ShadowIndex is rebased:

```text
ShadowIndex(base=N, target=N+1)
but ActiveIndex is now N+1
-> rebuild only remaining dirty delta
-> promote as N+2
```

### Versioned Retrieval

`CodeRetriever` should include version metadata in every result:

```text
chunk_id
file_path
symbol_key
content_hash
index_epoch
symbol_version
```

The Worker records those values in `WorkerContextManifest（Worker 上下文清单）`.

Before using a retrieved chunk as evidence, the runtime can verify:

```text
chunk.index_epoch == ActiveIndex.epoch
or chunk.symbol_version is still current
```

If not current, retrieval is repeated against the latest `ActiveIndex`.

## Memory Layering Under Token Pressure

The system should not push every invalidation into every prompt.

Use layered memory:

- `StickyMemory（强约束记忆）`: user/project rules that must always apply.
- `TaskStateMemory（任务状态记忆）`: current DAG status, task ownership, leases, and plan version.
- `InvalidationMemory（失效记忆）`: recent high-risk invalidation events relevant to the current Worker.
- `RagEvidenceMemory（RAG 证据记忆）`: retrieved chunks with `index_epoch` and `symbol_version`.
- `WorkingMemory（工作记忆）`: recent tool evidence and volatile facts.
- `LongTermMemory（长期记忆）`: stable cross-session facts only.

Promotion policy:

- public API or method signature change: force into `InvalidationMemory`.
- file-only implementation detail change: inject only when the Worker touched or retrieved that file.
- compile/test failure tied to changed symbol: force into Reviewer and affected Workers.
- repeated invalidation on same symbol: summarize into one latest event plus count.
- obsolete invalidation after successful refresh and validation: retain in event log, remove from prompt.

This keeps token use proportional to risk, not proportional to edit count.

## Interaction With GraphPatch

`ContextInvalidationEvent（上下文失效事件）` does not directly rewrite the DAG.

It can produce `ChangeRequest（变更请求）`:

```text
symbol OrderService.createOrder changed
task_4 depends on old signature
task_4 is running
suggest:
  block task_4
  add edge task_4 depends_on task_2
  invalidate task_5 if completed against old signature
```

Planner can then propose `GraphPatch（图补丁）`, and `GraphPatchValidator（图补丁校验器）` checks cycles, stale plan versions, completed-task evidence, and affected acceptance criteria.

## Failure Semantics

- If invalidation detection fails, compile/test/Reviewer remains the backstop.
- If ShadowIndex build fails, keep `ActiveIndex` unchanged and mark retrieval as degraded.
- If embeddings fail, keep keyword and structure retrieval available for changed files.
- If a Worker ignores stale context, `StaleWriteBarrier` rejects writes.
- If impact analysis is uncertain, prefer conservative file-level invalidation over false precision.
- If invalidation floods the system, coalesce by symbol and epoch.

## Implementation Phases

### Phase 1: Metadata and Barriers

- Add `ContextEpoch（上下文版本）` and `IndexEpoch（索引版本）`.
- Attach retrieval metadata to `search_code` results.
- Track `WorkerContextManifest（Worker 上下文清单）`.
- Add `StaleWriteBarrier（过期写入屏障）` before writes.

### Phase 2: JavaParser Symbol Diff

- Build `SymbolIndex（符号索引）`.
- Compare before/after AST after writes.
- Emit `StructuralChangeEvent（结构变化事件）`.
- Map symbol changes to affected files and DAG nodes.

### Phase 3: Invalidation Bus

- Add append-only `InvalidationBus（失效事件总线）`.
- Notify running Workers.
- Add refresh/retry state transitions.
- Feed high-risk invalidations into Reviewer.

### Phase 4: Shadow Index

- Add `DirtyFileQueue（脏文件队列）`.
- Build `ShadowIndex（影子索引）` incrementally.
- Promote via `AtomicIndexSwap（原子索引切换）`.
- Make `CodeRetriever` version-aware.

### Phase 5: Worktree and PatchSet Integration

- Use per-Worker worktrees when available.
- Attach context and index versions to `PatchSet（补丁集）`.
- Reject PatchSet application if base file versions or symbol versions are stale.

## Verification Strategy

Targeted tests:

- Worker using stale retrieved chunk cannot call `write_file`.
- signature change invalidates dependent running task.
- completed task becomes stale only when its manifest depended on changed symbol.
- ShadowIndex build failure does not corrupt ActiveIndex.
- AtomicIndexSwap rejects promotion from stale base epoch.
- retrieval result carries index and symbol versions.
- high-risk invalidation enters Reviewer prompt context.

Regression tests:

```bash
mvn test -Dtest=AgentOrchestratorTest,ToolRegistryTest -DskipTests=false
mvn test -Dtest=CodeIndexTest,CodeRetrieverTest,CodeAnalyzerTest -DskipTests=false
mvn test -Pquick
```
