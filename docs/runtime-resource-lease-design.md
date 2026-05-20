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
