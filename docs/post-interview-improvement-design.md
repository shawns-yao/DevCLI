# Post-Interview Improvement Design

## Status

This document records improvement work exposed by recent technical and scenario interviews.

It is a future design document. It does not change current runtime behavior.

Current PaiCLI behavior:

- `CodeRetriever（代码检索器）` supports hybrid code retrieval with keyword, semantic, and bounded graph expansion.
- `CodeIndex（代码索引）` builds chunks and JavaParser relations into a local SQLite-backed index.
- `ResourceLeaseManager（资源租约管理器）` blocks concurrent writes to the same file in parallel Agent steps.
- `Pre-Review Hook（审查前硬检查）` runs Java compile checks before Reviewer LLM review.
- `WorkingMemory（工作记忆）` is role-scoped for Planner, Worker, and Reviewer.
- `HITL（人类审批）`, `PathGuard（路径守卫）`, `CommandGuard（命令守卫）`, and `AuditLog（审计日志）` protect local tool execution.

Known gaps:

- No production `SymbolSolver（符号求解器）` integration with Maven classpath.
- No `ShadowIndex（影子索引）` or atomic index epoch swap in the runtime.
- No active `ContextInvalidation（上下文失效）` for running Workers.
- No per-Worker `Worktree（工作树）` or container sandbox isolation.
- No formal `RRF（倒数排名融合）` or labeled retrieval benchmark.
- No enterprise-scale ingestion pipeline for high-frequency Git push events.
- No durable multi-node Agent recovery model.

Related detailed designs:

- `docs/runtime-resource-lease-design.md`
- `docs/context-invalidation-shadow-index-design.md`

## Improvement Goals

- Make the interview story match real implementation boundaries.
- Convert repeated interview weak points into buildable engineering phases.
- Keep local CLI behavior simple while defining production-grade upgrade paths.
- Avoid claiming roadmap items as shipped behavior.
- Provide measurable acceptance criteria for each future phase.

## Gap 1: RAG Ranking Is Rule-Based, Not Formally Evaluated

### Problem

Current retrieval is practical but not rigorously evaluated.

The system combines:

- `Keyword Search（关键词检索）`
- `Semantic Search（语义检索）`
- `Graph Expansion（图扩展）`

Today the fusion is mostly heuristic. That is acceptable for a local CLI MVP, but weak for production claims such as `recall@5（前 5 召回率）`.

### Design

Introduce `RetrievalFusion（检索融合）` as an explicit component.

Recommended ranking flow:

```text
query
-> keyword candidates
-> semantic candidates
-> graph-expanded candidates
-> normalize per-channel ranks
-> RRF fusion
-> symbol-aware boost
-> context budget trimming
```

Use `RRF（倒数排名融合）` as the base because it does not require keyword, vector, and graph scores to share the same numeric scale.

Then apply deterministic boosts:

- exact file path match.
- exact class or method name match.
- error stack file and line match.
- test file and production file pair match.
- `implements（接口实现）` / `extends（继承关系）` relation match.

### Benchmark

Create a small but honest `CodeRagBenchmark（代码 RAG 基准集）`.

Data sources:

- `KnownFixCase（已知修复案例）`: historical diffs with expected relevant files.
- `CompileErrorCase（编译错误案例）`: missing symbol, changed method signature, wrong import, and type mismatch.
- `SyntheticSymbolQuery（合成符号查询）`: generated from JavaParser relations, such as "find all implementations".

Metrics:

- `Recall@k（前 k 召回率）`
- `MRR（平均倒数排名）`
- `Precision@k（前 k 精度）`
- `ContextPrecision（上下文精度）`
- compile success after Agent edit.
- average repair iterations.
- token cost.

Acceptance criteria:

- benchmark command exists.
- benchmark data is versioned and reproducible.
- retrieval report separates keyword, semantic, graph, and fused scores.
- no production claim uses unqualified recall numbers without dataset name.

## Gap 2: JavaParser Symbol Graph Is Lightweight

### Problem

Current JavaParser extraction is useful but shallow for type resolution.

For example:

```text
userService.save(userDto)
```

AST can identify `userService`, `save`, and `userDto`, but not always the resolved type behind `userService`.

Current approach is best-effort:

- imports.
- package names.
- class and method declarations.
- local variable and field type hints.
- fallback to method name when receiver type is unknown.

This is not equivalent to full `Symbol Resolution（符号消解）`.

### Design

Add a tiered resolver:

```text
Tier 0: text + AST extraction
Tier 1: source-only symbol table
Tier 2: Maven classpath symbol solver
Tier 3: LSP verified definitions and references
```

`JavaParser（Java 语法树解析器）` remains the low-cost baseline indexer.

`LSP（语言服务器协议）` becomes a high-confidence verifier, not a full replacement.

Priority when views disagree:

```text
current file content
> Maven/javac compile result
> LSP diagnostic / definition
> JavaParser symbol graph
> vector semantic result
```

### Maven Classpath Handling

Introduce `ClasspathEpoch（类路径版本）`.

When `pom.xml` or `build.gradle` changes:

- mark classpath stale.
- rebuild external symbol cache.
- re-resolve affected Java files.
- lower confidence of old symbol edges until rebuild finishes.

Acceptance criteria:

- index entries carry `symbol_confidence（符号置信度）`.
- classpath changes invalidate affected symbol edges.
- unresolved calls are explicit, not silently treated as resolved.
- compile diagnostics remain the final correctness gate.

## Gap 3: Context Invalidation Is Not Runtime-Enforced

### Problem

The hardest Multi-Agent failure is stale context, not syntax error.

Example:

```text
Worker A changes Service method signature
Worker B still holds old signature in prompt memory
Worker B writes Controller code against stale API
```

The current system relies on compile/review to catch many cases, but it does not actively notify running Workers that their prompt context is stale.

### Design

Implement the detailed design from `docs/context-invalidation-shadow-index-design.md`.

Minimum runtime pieces:

- `WorkerContextManifest（Worker 上下文清单）`
- `ContextDependency（上下文依赖）`
- `SymbolDiff（符号差异）`
- `ContextInvalidationEvent（上下文失效事件）`
- `StaleWriteBarrier（过期写入屏障）`

Flow:

```text
Worker writes Java file
-> parse before/after symbols
-> emit SymbolDiff
-> map affected Workers and tasks
-> mark Workers stale
-> block stale Worker writes
-> force context refresh or re-plan
```

Acceptance criteria:

- Worker prompt dependencies are recorded.
- public API changes produce invalidation events.
- stale Workers cannot write until refreshed.
- refresh prompt includes negative facts, such as "old signature is invalid".

## Gap 4: Index Updates Need Shadow Index

### Problem

Current index rebuild behavior is local and coarse. It does not guarantee that every Worker reads a complete, fresh, consistent index snapshot after concurrent writes.

### Design

Use `ShadowIndex（影子索引）` with `IndexEpoch（索引版本）`.

Flow:

```text
ActiveIndex(epoch=N)
Worker writes file
-> DirtyFileEvent
-> ShadowIndex(epoch=N+1) rebuilds changed chunks and relations
-> validate chunks, relations, and embeddings
-> AtomicIndexSwap
-> ActiveIndex(epoch=N+1)
```

During rebuild:

- reads stay on the old active index.
- dirty files are marked stale.
- symbol queries against dirty files trigger direct `read_file（读文件）`.
- graph expansion avoids stale edges or lowers their score.

Acceptance criteria:

- every retrieval result includes `index_epoch`.
- dirty files are visible in search output metadata.
- no query reads half-built index rows.
- failed rebuild keeps old active index available.

## Gap 5: File Lease Exists, Worktree Isolation Does Not

### Problem

Current file-level lease prevents simultaneous writes to the same physical file. It does not isolate each Worker from partial changes already written by another Worker.

This is enough for local protection, but not enough for production multi-Agent execution.

### Design

Keep `ResourceLeaseManager（资源租约管理器）` as the first write barrier.

Add future `WorkerWorkspace（Worker 工作区）` isolation:

```text
turn starts
-> create baseline snapshot
-> create isolated worktree per Worker or parallel wave
-> Worker writes only inside its worktree
-> Worker exports PatchSet
-> Orchestrator validates patch
-> deterministic merge
-> compile/test/review
-> apply to shared workspace
```

Merge strategy:

- use JGit three-way merge for text-safe patches.
- reject semantic conflicts instead of letting LLM auto-merge silently.
- run compile/test after every accepted merge.
- re-plan when interface and caller changes conflict.

Acceptance criteria:

- parallel Workers do not write directly to shared workspace.
- every Worker output is a `PatchSet（补丁集）`.
- merge conflict becomes structured `RESOURCE_CONFLICT（资源冲突）`.
- shared workspace changes only after validation.

## Gap 6: Execution Sandbox Is Local Policy, Not Strong Isolation

### Problem

Current local CLI protection is policy-layer protection.

It is not a strong sandbox against malicious commands, prompt injection, or untrusted repositories.

### Design

Production platform should separate:

- `ControlPlane（控制面）`: task creation, auth, policy, state.
- `ExecutionPlane（执行面）`: sandboxed code execution.

Sandbox tiers:

```text
Tier 0: local process with PathGuard and CommandGuard
Tier 1: Docker container with CPU/memory/process limits
Tier 2: gVisor sandbox for stronger kernel isolation
Tier 3: Firecracker microVM for untrusted code or high-risk tenants
```

Network policy:

- default deny.
- allow Maven/Gradle dependency proxy only.
- block private network scanning.
- record all outbound attempts.

File sync:

- do not bind-mount host workspace as writable.
- copy snapshot into sandbox.
- export patch and artifacts.
- validate paths before applying patch.

Acceptance criteria:

- shell execution has a command profile.
- network access is explicit and auditable.
- sandbox output is patch-based.
- production mode never gives model raw host shell access.

## Gap 7: Reviewer Needs Independent Retrieval Profile

### Problem

Reviewer can become a rubber stamp if it only checks syntax or repeats Worker explanation.

Compiler catches syntax and type errors. Reviewer must catch:

- requirement mismatch.
- hidden side effects.
- incomplete integration.
- missing tests.
- wrong behavior with compiling code.

### Design

Reviewer context must be different from Worker context.

Worker retrieval profile:

- implementation files.
- direct dependencies.
- target APIs.
- local compile errors.

Reviewer retrieval profile:

- acceptance criteria.
- tests.
- public API callers.
- config and mapper files.
- related bugfix history.
- business docs or README behavior.
- Worker patch summary.

Reviewer prompt must require:

- `functional_correctness（功能正确性）`
- `integration_completeness（集成完整性）`
- `code_quality（代码质量）`
- `criteria_results（验收结果）`
- `risk_items（风险项）`

Acceptance criteria:

- Reviewer receives patch and evidence, not full Worker chat.
- Reviewer must cite evidence for rejection.
- critical/high criteria failure blocks approval.
- Reviewer retrieval profile is separate from Worker retrieval profile.

## Gap 8: ReAct Loop Control Needs Formal Policy

### Problem

Agents can waste turns in low-value loops:

```text
search_code
-> read_file
-> compile error
-> same search_code
-> same compile error
```

### Design

Add `ToolUsePolicy（工具调用策略）`.

Track per Agent:

- repeated same query.
- repeated same file reads.
- error fingerprint changes.
- write attempts.
- compile/test delta.
- token and time budget.

Intervention rules:

- repeated retrieval with no new evidence triggers query rewrite.
- repeated compile failure with same fingerprint triggers root-cause summary.
- no progress after N iterations escalates to Planner or Reviewer.
- unsafe command proposal triggers HITL or policy rejection.

Acceptance criteria:

- ReAct loop has a progress score.
- repeated low-value tool calls are visible.
- failure feedback is structured and pruned.
- Orchestrator can stop a diverging Worker.

## Gap 9: Enterprise Code RAG Needs Distributed Ingestion

### Problem

Local SQLite is suitable for CLI usage. It is not suitable for:

- 10,000+ developers.
- millions of files.
- high-frequency Git push events.
- multi-tenant permissions.
- central Code RAG service.

### Design

Use event-driven ingestion:

```text
Git webhook
-> Kafka/Pulsar topic
-> diff parser
-> dirty symbol detector
-> embedding cache lookup
-> vector index write
-> graph index write
-> index catalog update
```

Hot file control:

- debounce repeated pushes.
- coalesce events by repo, branch, and path.
- prioritize main branch and active Agent tasks.
- rate-limit tenants with token bucket.
- degrade to keyword + symbol retrieval when embeddings lag.

Distributed query:

```text
query
-> vector topN with metadata filter
-> batch graph expansion for seed ids
-> server-side relation filtering and depth limit
-> fusion/rerank
-> context budget trim
```

Avoid N+1:

- batch graph expansion.
- precomputed adjacency for hot nodes.
- materialized subgraphs for core APIs.
- metadata filtering pushed into vector store.

Acceptance criteria:

- ingestion lag is measurable.
- embedding cache hit rate is reported.
- tenant isolation is enforced in every query.
- graph expansion is batch-based, not per-edge remote calls.

## Gap 10: Durable Agent Runtime Is Needed For Service Mode

### Problem

Local CLI can keep Agent state in memory during a turn. Service mode cannot.

Workers may crash, be preempted, or restart while an Agent task is halfway done.

### Design

FastAPI or any HTTP layer should be only `ControlPlane（控制面）`.

Long-running Agent execution should run through:

- queue worker.
- Kubernetes Job.
- durable task manager.
- event log.
- checkpoint store.

Persist:

- `task_id`
- `plan_version`
- `dag_state`
- `current_step`
- `tool_calls`
- `memory_summary`
- `patch_refs`
- `artifact_refs`
- `retry_count`
- `workspace_snapshot_id`

Recovery:

```text
worker dies
-> lease timeout
-> task becomes retryable
-> new worker loads latest checkpoint
-> rebuild sandbox/worktree
-> replay confirmed patches
-> continue from next pending step
```

Acceptance criteria:

- no Agent progress exists only in process memory.
- write operations are idempotent by patch id or lease id.
- crashed task resumes from last confirmed checkpoint.
- duplicate tool result application is prevented.

## Proposed Phases

### Phase A: Honest Retrieval Evaluation

- Add benchmark dataset format.
- Add retrieval fusion report.
- Implement RRF-based fusion behind a flag.
- Document recall metrics with dataset names.

### Phase B: Symbol And Index Consistency

- Add symbol confidence.
- Add classpath epoch.
- Add dirty file metadata in retrieval.
- Implement shadow index MVP.

### Phase C: Multi-Agent Consistency

- Add Worker context manifest.
- Add context invalidation events.
- Add stale write barrier.
- Add structured resource conflict handling.

### Phase D: Reviewer And Loop Control

- Add reviewer retrieval profile.
- Add tool-use policy.
- Add error fingerprint progress tracking.
- Add diverging Worker escalation.

### Phase E: Isolation And Service Mode

- Add Worker worktree isolation.
- Add patch-based merge.
- Add sandbox command profile.
- Add durable task checkpoints.

### Phase F: Enterprise RAG Service

- Add event-driven ingestion design implementation.
- Add vector/graph batch query API.
- Add tenant permission filtering.
- Add ingestion and query observability.

## Non-Goals

- Do not replace compile/test gates with RAG confidence.
- Do not claim full Java type resolution without classpath-backed verification.
- Do not use LLM auto-merge as the primary conflict strategy.
- Do not send full graph or full chat history to every Agent.
- Do not use long context as a substitute for permission, freshness, or dependency tracking.
- Do not make the local CLI depend on enterprise-only infrastructure.

## Interview Answer Guidelines

When discussing these areas:

- Say "current implementation" only for shipped behavior.
- Say "production-grade design" for future architecture.
- Use clear terms such as `ShadowIndex（影子索引）` and `ContextInvalidation（上下文失效）`.
- Admit that JavaParser extraction is lightweight unless symbol solver is added.
- Treat compile/test as the final correctness source.
- Treat RAG as retrieval evidence, not truth.
- Separate local CLI design from service platform design.

