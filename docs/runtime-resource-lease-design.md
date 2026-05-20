# Runtime Resource Lease Design

## Goal

`ResourceConflictDetector` handles planner-time conflicts by splitting executable tasks into conflict-free waves. That is not enough when a Worker discovers extra files at execution time, for example changing `UserDTO.java` while the original task only mentioned `User.java`.

This design adds a runtime write barrier at `ToolRegistry.write_file` so parallel Agent steps cannot physically write the same file at the same time.

## Non-Goals

- Do not implement per-Worker git worktrees in this phase.
- Do not perform automatic LLM merge for conflicting candidate versions.
- Do not change user `.git`.
- Do not roll back accepted step output during re-plan.

## Design

The runtime owns a `ResourceLeaseManager`.

Each executable step runs tools inside a resource lease context:

```text
step starts
-> ToolRegistry.runWithResourceLease(step_id, action)
-> write_file resolves PathGuard-safe absolute path
-> ResourceLeaseManager.acquireWrite(step_id, path)
-> write allowed only if no other running step owns the same path
-> step finishes
-> releaseResourceLeases(step_id)
```

If another running step already owns the file, `write_file` returns a policy rejection:

```text
资源写入冲突: <path> 已由步骤 [step_a] 持有，当前步骤 [step_b] 不能并发写入
```

The rejection is intentionally fail-fast. It preserves the already-written file content instead of letting the later Worker overwrite it. The existing Worker retry, Reviewer, Pre-Review Hook, and re-plan paths then decide how to continue.

## Why Dynamic Lock First

Post-facto merge is unsafe as the primary mechanism. Java edits can affect constructors, DTO fields, XML mapper nodes, imports, serialization annotations, and method overloads. A text merge can compile but still be semantically wrong.

The first production-grade guardrail is therefore:

```text
prevent concurrent writes first
merge only as an explicit future recovery path
```

## Current Scope

Implemented now:

- `write_file` write lease check.
- Same step may rewrite the same file.
- Different concurrent steps cannot write the same file.
- `/plan` task execution binds `task_id` as the lease owner.
- `/team` Worker step execution binds `step_id` as the lease owner.
- Leases are released when the step finishes.

Still future work:

- `edit_file` patch tool, if introduced, must use the same barrier.
- Runtime lock upgrade should be reported as structured `RESOURCE_CONFLICT`.
- Planner should output `read_set` / `write_set`.
- Worker writes should eventually produce `PatchSet` before applying to shared workspace.
- JGit deterministic three-way merge should be used only for isolated PatchSets, followed by compile/test/Reviewer validation.

## Future Worktree Isolation

Per-Worker worktree isolation is not implemented in the current phase. The current runtime still writes accepted tool output into the shared project workspace, protected by file-level write leases.

The next isolation phase should move Worker execution to independent workspaces:

```text
turn starts
-> create accepted baseline from side-git / current workspace
-> create one isolated worktree per Worker step or per parallel wave
-> Worker writes only inside its own worktree
-> collect step PatchSet from baseline..worker-result
-> Reviewer validates PatchSet with compile/test evidence
-> Orchestrator applies accepted PatchSet to shared workspace
-> conflicting PatchSets go through deterministic JGit merge first
-> unresolved conflicts become explicit repair tasks, not silent LLM merges
```

Required components:

- `WorkerWorkspaceManager`: creates and deletes isolated Worker worktrees under a controlled temp root.
- `PatchSet`: records changed files, base blob ids, after blob ids, and textual diff for each step.
- `PatchSetApplier`: applies accepted patches to the shared workspace with base-version checks.
- `MergeCoordinator`: uses JGit three-way merge for PatchSets that touch the same files.
- `ConflictTask`: turns unresolved merge conflicts into an explicit DAG task with base / ours / theirs context.

Hard rules for this phase:

- Do not modify the user project `.git`.
- Do not let Worker worktrees share writable source files.
- Do not apply a PatchSet if its base file version no longer matches the accepted baseline.
- Do not let LLM output directly overwrite a merge conflict result; it must produce a patch that passes compile/test/Reviewer checks.
- Keep file leases as a guardrail for the shared workspace even after worktree isolation is introduced.

## Future GraphPatch Protocol

GraphPatch is not implemented in the current phase. Today a runtime conflict is a policy denial and the existing retry / Reviewer / re-plan flow decides what happens next.

The production-grade DAG mutation path should be explicit and structured:

```text
Worker detects new dependency or blocked write
-> emits ChangeRequest
-> Orchestrator pauses affected wave
-> Planner proposes GraphPatch
-> GraphPatchValidator checks ids, dependencies, cycles, and completed-task impact
-> Orchestrator commits a new plan_version
-> affected pending tasks are rescheduled
-> stale completed tasks are invalidated only when impact analysis proves they depend on changed symbols or files
```

`GraphPatch` should support only bounded operations:

- `add_node`: add a new task with type, description, acceptance criteria, and declared read/write sets.
- `add_edge`: add a dependency between existing or newly added nodes.
- `invalidate_node`: mark a completed node stale because its accepted assumptions changed.
- `block_node`: pause a pending/running node until another node completes.
- `split_node`: replace a coarse node with smaller ordered nodes when runtime evidence shows the task was under-specified.

Validation rules:

- Reject patches that introduce cycles.
- Reject patches that delete completed evidence.
- Reject patches that mutate task status without an explicit reason.
- Require every invalidation to cite a changed file, changed symbol, failed check, or runtime lease conflict.
- Preserve `plan_version`; applying a patch must be compare-and-swap guarded so a patch based on an old plan cannot overwrite a newer one.

GraphPatch is the bridge between runtime evidence and DAG scheduling. LLM text can propose the patch, but the Orchestrator owns validation and commit.

## Future JavaParser Scheduling Awareness

JavaParser is currently used for Java syntax diagnostics, Java method/class chunking, and RAG code relation extraction. It is not yet a scheduling input.

The production-grade design should promote JavaParser from RAG support to a scheduling-awareness component:

```text
accepted baseline
-> SymbolIndex built from JavaParser AST plus JavaSymbolSolver when classpath is available
-> Worker writes Java file
-> incremental parse changed file
-> SymbolDiff compares before/after declarations
-> ImpactAnalyzer walks reverse call / implements / extends edges
-> affected files and DAG nodes are reported to Orchestrator
-> Orchestrator emits ChangeRequest or applies GraphPatch
```

Required components:

- `SymbolIndex`: stores classes, methods, fields, signatures, file paths, ranges, and relation edges.
- `SymbolDiff`: detects added/removed/changed method signatures, return types, field types, class inheritance, and interface implementations.
- `ImpactAnalyzer`: maps changed symbols to reverse callers, implementations, tests, and pending/completed DAG nodes.
- `StructuralChangeEvent`: structured event emitted after a write, for example `METHOD_SIGNATURE_CHANGED`.
- `SchedulingImpactReport`: summarizes affected files, affected tasks, suggested invalidations, and suggested dependency edges.

Example:

```text
changed symbol:
  UserService.login(String, String) -> UserService.login(LoginRequest)

reverse callers:
  UserController.login
  AuthCommand.execute
  LoginFlowTest.shouldLogin

scheduling impact:
  block task_4 if running
  add edge task_4 depends_on task_2
  invalidate task_5 if it completed against the old signature
```

Hard rules:

- JavaParser AST evidence can trigger impact analysis, but compile/test results remain the final correctness gate.
- When JavaSymbolSolver cannot resolve a call precisely, degrade to conservative file-level impact instead of pretending precision.
- Do not let symbol impact directly mutate the DAG; it must go through `ChangeRequest` and `GraphPatchValidator`.
- Reviewer must receive the `SchedulingImpactReport` when reviewing a task that changed public symbols.

## Failure Semantics

A runtime lease conflict is treated as a policy denial, not as a silent merge. This keeps the workspace deterministic:

- No last-writer-wins overwrite.
- No hidden candidate version loss.
- No LLM-only merge without compiler/test evidence.

## Verification

Targeted tests:

```bash
mvn test -Dtest=ToolRegistryTest -DskipTests=false
```

Regression tests:

```bash
mvn test -Dtest=ResourceConflictDetectorTest,ToolRegistryTest -DskipTests=false
mvn test -Pquick
```
