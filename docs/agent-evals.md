# 终端 Agent Evals

## 目标

该评测入口用于验证 DevCLI 终端 Agent 的真实任务能力、工具轨迹和工作区副作用。它不是模型聊天质量分数，也不替代 SWE-bench、AgentDojo 等公开集合的官方 evaluator。

## 真实链路

```text
tasks.jsonl
  -> AgentEvalDriver
  -> AgentSessionRuntime
  -> ToolRegistry.ISOLATED_PROJECT
  -> IsolatedWorkspace
  -> trace + workspace diff + deterministic checks
```

每个样本都创建新的隔离工作区。Agent 运行期间只能看到 `prompt` 和 fixture；`checks` 只在运行结束后由评测器读取。命令工具默认使用 Docker 沙箱，文件系统隔离由 `IsolatedWorkspace` 和配置的 workspace backend 提供。

## 工具调用校验闭环

Function Calling 的 JSON Schema 只校验参数结构，不能证明参数代表的业务对象真实存在，也不能证明当前状态允许执行。DevCLI 的实际顺序是：

```text
模型 tool call
  -> JSON 解析与 Schema 校验
  -> 能力范围与 Skill 权限
  -> HITL 人工审批（危险工具）
  -> 语义/业务校验
  -> 策略与审计
  -> Provider 执行时的最终状态校验
  -> 结构化 ToolOutput（status/error_code/retryable）
```

语义层会检查参数组合、项目路径和资源状态、范围边界、正则与 URL 等本地可判定条件。业务系统相关的规则（例如用户是否存在、订单状态是否允许变更）不能由客户端猜测；MCP 或业务扩展应通过 `ToolRegistry.registerSemanticValidator` 注册自定义规则，并继续保留服务端最终校验。失败不会执行工具，而是返回 `SEMANTIC_VALIDATION_FAILED` 或 `POLICY_DENIED`，让 Agent 根据结构化错误修正或停止。

## 样本格式

`Test/agent-evals/v1/tasks.jsonl` 每行一个 JSON 对象：

| 字段 | 说明 |
| --- | --- |
| `id` | 唯一样本标识 |
| `prompt` | Agent 可见任务，不包含 gold |
| `fixture` | 相对 tasks 文件的只读初始工作区 |
| `checks` | 仅运行结束后执行的确定性 grader |

支持的 checks：`file_exists`、`file_absent`、`file_contains`、`file_not_contains`、`no_changes`、`command`。`command` 通过生产 `ToolRegistry` 在 `ISOLATED_PROJECT` 范围执行，结构化读取退出码，不从文本猜测。评测结果应同时记录 Schema/语义拒绝、权限拒绝、Provider 执行失败和外部环境失败，不能把“格式正确”当作“任务完成”。

## 运行

先做离线契约检查：

```powershell
pwsh benchmarks/agent-evals/run.ps1 -DryRun
```

真实运行需要模型配置和 Docker：

```powershell
pwsh benchmarks/agent-evals/run.ps1 -M2 C:\Document\Maven\repository
```

输出目录包含 `manifest.json`、`summary.json`，以及每个样本的 `result.json`、`trace.jsonl`、`model-output.txt`。`manifest.json` 固定记录目标、测试类型、入口、Gold 可见范围、主指标、样本 hash、沙箱和工作区后端。

## 指标

- `task_success_rate = successful_scored_cases / scored_cases`
- `external_failure_rate = external_failure_count / raw_sample_count`
- `safe_tool_use`：是否出现评测白名单之外的工具调用
- `trace_present`：是否产生结构化 Agent trace
- 工具调用数、结果数、输入/输出/缓存 Token、估算成本和墙钟时间

外部失败（模型不可用、网络、Docker 或沙箱故障）单独统计，不进入核心任务成功率分母。样本只运行一轮，正式版本比较应冻结 tasks 文件 hash、模型、预算、DevCLI commit 和镜像 digest。

## 评测边界

当前入口是定向测试和小规模冒烟的工程适配器。它可以回答“Agent 是否完成这些任务，以及怎样完成”，不能仅凭少量样本宣称通用能力提升。公开 benchmark 仍须使用其固定数据版本和官方 evaluator。
