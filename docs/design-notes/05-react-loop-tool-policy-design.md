# ReAct Loop And Tool Policy Design

## Status

This is a future design document. It records issues exposed by interviews and does not change current runtime behavior.

## 暴露的问题

`ReAct（推理-行动）` 面试高频追问：

- LLM 输出 JSON 不规范怎么办？
- 工具参数校验失败后，模型一直重试怎么办？
- MCP server 一直返回参数错误怎么办？
- 编译错误几十条，怎么反馈给 Agent？
- Agent 陷入 `search_code -> read_file -> compile error -> search_code` 循环怎么办？
- 怎么判断修复是在收敛还是发散？

## 当前已有

- 工具参数走 JSON Schema 校验。
- 工具错误会回灌给模型修正。
- `ToolRegistry（工具注册表）` 是内置工具和 MCP 工具统一入口。
- 危险工具走 HITL / Policy / AuditLog。
- Java 项目有 Pre-Review compile gate。
- 部分预算和熔断策略已经存在。

## 不足

- 没有统一 `ToolUsePolicy（工具调用策略）`。
- 没有 per-Agent progress score。
- 没有系统化 error fingerprint。
- 重复低价值工具调用不可见。
- 编译错误剪枝还不够标准化。
- MCP 参数错误循环没有独立预算。

## 怎么修改

### 1. ToolUsePolicy

新增策略层记录：

```text
agent_id
task_id
tool_name
arguments_hash
target_file
query_hash
result_fingerprint
duration
token_cost
```

### 2. 低价值重复检测

触发条件：

- 同一 query 连续 search 3 次，无新增文件。
- 同一文件连续 read 3 次，无写入或新诊断。
- 同一命令连续失败，错误指纹不变。
- MCP 参数错误连续出现同类 schema failure。

处理：

- 要求 query rewrite。
- 强制读取当前文件。
- 总结 root cause。
- 交给 Planner re-plan。
- 触发 Reviewer/Orchestrator 介入。

### 3. ErrorFingerprint

编译错误结构化：

```text
file
line
symbol
error_code
message_class
root_cause_group
```

比较每轮错误：

- resolved count.
- new error count.
- unchanged count.
- severity.

### 4. Causal Pruning

对级联错误只保留：

- 第一批 root cause。
- 缺失符号。
- 方法签名不匹配。
- 代表性调用点。
- 与当前 patch 相关的错误。

### 5. Progress Score

每轮计算：

```text
progress = resolved_errors - new_errors + accepted_tests + meaningful_file_changes - repeated_tool_penalty
```

低于阈值则停止 Worker。

## 设计边界

- 不允许无限工具循环。
- 不把全部编译日志直接塞给模型。
- 不把工具错误全部交给 LLM 自行悟。
- 不让重试掩盖发散。

## 验收标准

- 工具调用有预算和重复检测。
- 编译错误能生成 error digest。
- 同类错误不减少时 Worker 会被阻断。
- MCP schema 失败有独立熔断。
- Orchestrator 能解释为什么停止 Worker。

## 文字解释

面试时可以这样讲：

> PaiCLI 的 ReAct 不是放任模型一直调工具，而是要加 `ToolUsePolicy（工具调用策略）`。我会记录每次工具调用的 query、参数 hash、结果指纹、耗时和 token 成本。如果同一个 Agent 连续检索同一 query、读取同一文件、或者编译错误指纹不变，说明没有信息增益，就不能继续原地循环。编译错误会先变成 `ErrorFingerprint（错误指纹）` 和 root-cause digest，只把根因和代表调用点反馈给 Agent。每轮用 progress score 判断收敛还是发散，发散时由 Orchestrator 停止 Worker 并触发 re-plan。

