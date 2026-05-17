# Phase 23: P1 Agent Hardening Plan

> Status: implemented
>
> Goal: close the most important engineering gaps exposed by interview review without
> overstating current behavior. This phase focuses on reliability, observability, and
> safer dynamic tool execution.

## Background

PaiCLI already has the core Agent loop, Plan-and-Execute, Multi-Agent orchestration,
RAG, MCP integration, HITL approval, AuditLog, Side-Git snapshots, token budgeting,
and JavaParser post-edit diagnostics.

The current boundary is clear:

- MCP tool arguments are mainly validated by the remote MCP server.
- ReAct loop protection catches repeated identical tool calls, but not repeated
  semantically equivalent errors with slightly different arguments.
- Parallel task execution is dependency-aware, but not resource-aware at file level.
- Logs and AuditLog exist, but there is no unified run trace that links planner,
  worker, reviewer, LLM calls, tool calls, token usage, and failures.

This phase turns those boundaries into explicit engineering work.

## Scope

### 1. MCP Schema Validator

Add a lightweight local validator before invoking MCP tools.

Purpose:

- Catch missing required arguments before calling a remote server.
- Catch obvious JSON type mismatches.
- Return a model-readable error so the LLM can correct the next tool_call.
- Avoid trying to infer business semantics locally.

Expected behavior:

```text
LLM tool_call
  -> find registered MCP inputSchema
  -> validate required fields
  -> validate basic JSON types
  -> if invalid: return validation error as tool result
  -> if valid: call MCP server tools/call
```

Supported checks:

- `required`
- primitive `type`: `string`, `number`, `integer`, `boolean`, `array`, `object`
- nested object properties where schema is simple enough
- tolerant fallback when schema is missing or unsupported

Non-goals:

- Do not auto-fill missing business parameters.
- Do not implement full JSON Schema draft compliance.
- Do not reject a call only because the schema contains unsupported advanced
  constructs. Prefer best-effort validation.

Implemented files:

- `src/main/java/com/paicli/mcp/protocol/McpSchemaValidator.java`
- `src/main/java/com/paicli/tool/ToolRegistry.java`
- `src/test/java/com/paicli/mcp/protocol/McpSchemaValidatorTest.java`
- `src/test/java/com/paicli/mcp/McpToolRegistrationTest.java`

Acceptance criteria:

- Missing required field returns a clear local validation error.
- Basic type mismatch returns a clear local validation error.
- Valid arguments still call the MCP invoker.
- Missing or empty schema does not block invocation.
- Existing MCP schema sanitizer behavior remains unchanged.

### 2. Tool Error Circuit Breaker

Extend the current loop protection from identical tool_call detection to repeated
tool error detection.

Purpose:

- Stop costly correction loops where the LLM keeps calling the same tool after
  receiving equivalent failures.
- Produce a concise failure summary instead of burning more tokens.

Current behavior:

- `AgentBudget` detects stagnation when the last N tool call signatures are exactly
  identical.
- Hard max iterations and optional token budget remain as global fallbacks.

New behavior:

```text
tool result indicates error
  -> normalize error category
  -> record tool_name + category
  -> if same category repeats N times for same tool: circuit break
```

Candidate error categories:

- missing required argument
- type mismatch
- permission denied
- not found
- timeout
- policy denied
- MCP server error
- unknown tool error

Default policy:

- Break after 3 repeated failures for the same tool and same category.
- Keep existing hard max iteration and token budget rules.
- Return a user-visible explanation that includes the tool name and repeated error
  category.

Likely files:

- `src/main/java/com/paicli/agent/AgentBudget.java`
- `src/main/java/com/paicli/agent/ToolErrorClassifier.java`
- `src/main/java/com/paicli/agent/Agent.java`
- `src/main/java/com/paicli/agent/SubAgent.java`
- `src/main/java/com/paicli/agent/PlanExecuteAgent.java`
- `src/test/java/com/paicli/agent/AgentBudgetTest.java`
- `src/test/java/com/paicli/agent/AgentBudgetTest.java`

Acceptance criteria:

- Three repeated missing-argument errors for the same MCP tool stop the loop.
- Three different error categories do not trigger this specific breaker.
- Existing identical tool_call stagnation detection still works.
- The breaker output is clear enough for the user to understand what failed.

### 3. Resource-Aware Parallel Scheduler

Add resource conflict awareness to Plan-and-Execute and Multi-Agent batches.

Purpose:

- Preserve safe parallelism for read-only tasks.
- Avoid obvious read/write and write/write conflicts on the same file.
- Treat global commands as exclusive operations.

Current behavior:

- DAG dependencies decide whether tasks are executable.
- Executable tasks in the same batch can run in parallel.
- There is no file-level read/write lock.

New behavior:

```text
executable DAG nodes
  -> infer resource access from task metadata and planned tool calls where possible
  -> split into conflict-free batches
  -> run conflict-free tasks in parallel
  -> run conflicting write/global tasks serially
```

Resource key examples:

- `read:src/main/java/com/foo/UserService.java`
- `write:src/main/java/com/foo/UserService.java`
- `global:execute_command:mvn-test`
- `global:execute_command:mvn-package`

Conflict rules:

- read + read on same file: parallel
- read + write on same file: serial
- write + write on same file: serial
- `execute_command` that may compile/test/build: exclusive by default
- unknown write target: conservative serial execution

Implementation notes:

- Start with conservative static inference from task descriptions and tool args.
- Do not require perfect file set prediction from the Planner.
- Prefer lowering parallelism over corrupting workspace state.

Implemented files:

- `src/main/java/com/paicli/plan/ResourceConflictDetector.java`
- `src/main/java/com/paicli/agent/PlanExecuteAgent.java`
- `src/main/java/com/paicli/agent/AgentOrchestrator.java`
- `src/test/java/com/paicli/plan/ResourceConflictDetectorTest.java`
- `src/test/java/com/paicli/agent/AgentOrchestratorTest.java`

Acceptance criteria:

- Independent read-only tasks still run in parallel.
- Two write tasks targeting the same file run serially.
- A write task and a read task for the same file run serially.
- Build/test commands run exclusively.
- Existing DAG dependency behavior remains unchanged.

### 4. TraceRecorder

Introduce a lightweight structured trace recorder for Agent runs.

Purpose:

- Make failed runs diagnosable without scraping mixed terminal output.
- Link planner decisions, DAG nodes, LLM calls, tool calls, reviewer decisions,
  token usage, latency, and errors under one `run_id`.
- Keep trace data local and avoid logging full secrets or large binary payloads.

Trace model:

```text
run_id
  span_id
  parent_span_id
  event_type
  actor
  model
  prompt_hash
  input_summary
  output_summary
  tool_name
  arguments_preview
  result_preview
  input_tokens
  output_tokens
  cached_input_tokens
  elapsed_ms
  status
  error
  created_at
```

Storage:

- JSONL under `~/.paicli/traces/trace-YYYY-MM-DD.jsonl`
- Configurable with `PAICLI_TRACE_DIR` / `-Dpaicli.trace.dir`
- Default enabled for metadata and summaries, not full prompts.

Privacy and size rules:

- Reuse AuditLog-style secret sanitization.
- Store prompt hash, not full prompt body, by default.
- Truncate previews.
- Do not store base64 image payloads.

Implemented files:

- `src/main/java/com/paicli/trace/TraceRecorder.java`
- `src/main/java/com/paicli/trace/TraceContext.java`
- `src/main/java/com/paicli/agent/Agent.java`
- `src/main/java/com/paicli/agent/PlanExecuteAgent.java`
- `src/main/java/com/paicli/agent/AgentOrchestrator.java`
- `src/test/java/com/paicli/trace/TraceRecorderTest.java`

Acceptance criteria:

- ReAct iterations are traceable through `run.start`, `llm.response`, and `tool.result`.
- Plan task iterations record `task.start`, `llm.response`, and `tool.result`.
- Multi-Agent records `run.start` and resource-aware `batch.wave` scheduling events.
- Tool results include latency, timeout status, and sanitized result preview.
- Trace write failures do not break the main Agent flow.

## Verification Plan

Targeted tests:

```bash
mvn -DskipTests=false -Dtest=McpSchemaSanitizerTest,McpSchemaValidatorTest,McpToolRegistrationTest test
mvn -DskipTests=false -Dtest=AgentBudgetTest test
mvn -DskipTests=false -Dtest=ResourceConflictDetectorTest,ExecutionPlanTest,AgentOrchestratorTest test
mvn -DskipTests=false -Dtest=TraceRecorderTest,AuditLogTest test
```

Regression tests:

```bash
mvn test -Pquick
```

Manual checks:

```text
1. Start PaiCLI with MCP enabled.
2. Trigger a MCP tool with a missing required argument and verify local validation.
3. Trigger repeated MCP failures and verify the circuit breaker stops the loop.
4. Run a Plan task with two read-only subtasks and confirm they remain parallel.
5. Run a Plan task with two same-file write subtasks and confirm they serialize.
6. Inspect ~/.paicli/traces/ and verify trace JSONL contains sanitized metadata.
```

## Resume Wording After This Phase

After implementation, these claims become safer:

- MCP tools are dynamically registered from server schemas, with local basic
  schema validation and model-readable correction feedback.
- Agent loops include repeated-error circuit breaking in addition to hard iteration
  and token budget limits.
- Plan-and-Execute and Multi-Agent parallel execution are constrained by DAG
  dependencies and resource conflict detection.
- Agent runs can be replayed through local structured traces covering key LLM
  responses, tool results, scheduling waves, latency, token usage, and failures.

Avoid claiming:

- full JSON Schema compliance
- automatic business-parameter completion
- distributed transaction semantics
- perfect semantic conflict detection
