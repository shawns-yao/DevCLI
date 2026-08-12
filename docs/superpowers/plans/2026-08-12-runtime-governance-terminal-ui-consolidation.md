# DevCLI 运行治理与终端界面收敛实施计划

> **执行要求：** 当前只允许更新并冻结设计文档。阶段 5A 及之后的代码实现必须取得用户明确同意后才能开始；获得授权后仍需分阶段实施。全部功能完成前禁止启动项目或运行测试，最终统一验证。

**目标：** 删除重复能力，统一会话、执行、预算、安全、恢复和观测模型，并把终端界面重构为消费同一运行状态的 JLine 内联控制台。

**架构：** 保留 ReAct 与结构化执行两类执行语义，Plan 和 Team 作为结构化执行的两种审查策略；保留 Side-Git、PatchSet 和 Checkpoint 的不同恢复职责。Runtime SQLite 成为本地运行事实来源，Temporal 只作为未来分布式服务端运行时的替代适配器，不进入当前本地发行版。终端界面只保留 Inline 和 Plain 两种适配器，所有展示状态来自强类型 RunEvent 与统一 RunSnapshot。

**技术栈：** Java 17、JLine 4、SQLite、JGit、Docker、Jackson、JUnit 5、Maven。

---

## 一、设计结论

### 1.0 实施授权门禁

- 本文档中的未勾选事项均为候选设计，不代表已经实施。
- 用户明确回复同意实施前，不修改阶段 5A 及之后涉及的源代码、测试代码、配置或依赖。
- 授权按已冻结范围生效；实施中发现需要扩大范围时，先更新设计并再次取得同意。
- 文档更新、静态差异检查和 Git 文档提交不视为功能实现。
- 结构化执行统一入口已在本次流程澄清前完成并提交；后续阶段严格执行本门禁。

### 1.1 产品能力保留、合并与删除

| 现有能力 | 决策 | 最终职责 |
| --- | --- | --- |
| ReAct | 保留 | 默认轻量执行；允许直接处理低风险、本地交互任务 |
| Plan-and-Execute | 合并 | 进入 `StructuredExecution`，策略为 `PLAN_REVIEW` |
| Multi-Agent | 合并 | 进入 `StructuredExecution`，策略为 `TEAM_REVIEW`；保留 Worker、Pre-Review、Reviewer |
| `AgentExecutionEngine` | 保留并下沉 | ReAct 与结构化步骤共用单轮模型、工具、预算、重试和事件协议 |
| Runtime thread、branch、event、checkpoint | 保留并统一 | 作为 CLI、后台任务和 Runtime API 的本地运行事实来源 |
| CLI 进程内 conversation branch | 删除 | 由持久 Session Tree 取代，禁止维护第二套历史快照 |
| Session Tree | 新增交互，不新增存储 | 复用 Runtime branch、message parentId 和 checkpoint 数据 |
| Side-Git | 保留 | 恢复已经进入主工作区的整轮文件状态 |
| PatchSet、Patch Journal | 保留 | 隔离成果的冲突预检、原子应用和中断对账 |
| Agent Checkpoint | 保留但收窄 | 只保存结构化执行 DAG、角色、步骤和 Patch 提交恢复信息 |
| DurableTaskManager 独立任务表 | 合并 | 并入统一 RunStore；后台任务是 Run 的一种提交来源，不再是第二套运行状态机 |
| InlineRenderer | 保留并重构 | 唯一交互式终端界面 |
| PlainRenderer | 保留 | 无 ANSI、重定向、测试和自动化降级界面 |
| Lanterna TUI | 删除 | 不再维护第三套输入、会话、HITL 和状态实现 |
| Skill、Hook、MCP | 保留 | 分别承载模型工作流、确定性生命周期动作和外部工具协议 |
| 通用进程内第三方插件平台 | 不新增 | 防止绕过 ToolExecutionPipeline 和 JVM 权限边界 |
| Temporal | 当前不引入 | 未来分布式服务端模式下替换本地调度适配器，不与本地调度叠加 |

### 1.2 不可互相替代的状态

| 状态 | 唯一事实来源 | 说明 |
| --- | --- | --- |
| 对话和分支 | RunStore / Session Tree | Tree 只切换对话上下文 |
| 主工作区文件 | Side-Git | Tree 不能恢复文件 |
| 隔离任务修改 | PatchSet / Patch Journal | Side-Git 不承担批准前隔离 |
| 结构化执行进度 | ExecutionArtifact / AgentCheckpoint | 不与普通会话事件混存业务语义 |
| 当前会话证据 | WorkingMemory | 可淘汰，不承担持久恢复 |
| 跨会话稳定事实 | LongTermMemory | 不保存运行状态 |

### 1.3 目标结构

```text
User / Runtime API / Background submitter
                  |
             RunCoordinator
          /          |          \
      ReAct    StructuredExecution   SessionTree
                 |          |
            PLAN_REVIEW  TEAM_REVIEW
                  |
        AgentExecutionEngine
                  |
   BudgetGuard -> ToolExecutionPipeline -> RunEventSink
                      |
       Sandbox / PatchSet / Side-Git / Audit
                  |
                RunStore
                  |
       Inline UI / Plain UI / Runtime SSE
```

---

## 二、四条运行治理主线

## 2.1 成本控制：Token 与现金预算

### 当前问题

- `TokenBudget` 管理上下文窗口，`AgentBudget` 管理循环退出，两者名称相近但职责不同。
- ReAct 的硬 Token 预算默认是无限，仅保留 50 轮兜底；这不符合商业产品默认可控成本要求。
- 成本估算按 Provider 写死少量价格，缺少 model 版本、币种、价格生效时间和未知价格状态。
- Plan、Worker、Reviewer、压缩、重排和重试没有统一归入同一个任务预算账本。
- 状态栏展示的是使用量，不是“已用 / 上限 / 剩余 / 触发动作”。

### 设计

建立三个深模块：

1. `ContextWindowBudget`
   - 只回答一次请求能否进入模型上下文。
   - 输入为 system prompt、工具定义、消息、图片估算和输出预留。
   - 超限动作只允许压缩、裁剪或拒绝，不与现金成本混合。

2. `RunBudget`
   - 约束一次 Run 的累计输入、输出、缓存 Token、LLM 调用次数、工具调用次数、墙钟时间和估算金额。
   - 重试、Planner、Worker、Reviewer、摘要模型调用全部记入同一个 `run_id`。
   - 支持 `WARN`、`SOFT_STOP`、`HARD_STOP`；达到硬限制后禁止再发起 LLM 调用，只允许生成程序化终止摘要。

3. `PricingCatalog`
   - 键为 provider、model、effectiveAt。
   - 分别记录 uncached input、cached input、output 价格和币种。
   - 未知模型显示 `cost=unknown`，禁止套用其他 Provider 的默认价格伪装精确成本。

### 默认预算策略

| 模式 | Token 上限策略 | LLM 调用上限 | 预算分配 |
| --- | --- | --- | --- |
| ReAct | 模型窗口外另设有限 Run 预算 | 有限 | 全部归主 Agent |
| Plan review | 总预算按 Planner、tasks、integration 预留分配 | 有限 | 未使用额度可以归还公共池 |
| Team review | 总预算按 Planner、Workers、Reviewer、修复预留分配 | 有限 | Worker 并行共享原子预算账本 |
| Background | 必须显式选择预算档位 | 有限 | 禁止默认无限 |

预算档位只提供 `economy`、`balanced`、`thorough` 三种产品语义；底层仍允许配置精确限制。模式负责执行策略，预算档位负责资源上限，两者不得互相推导。

### 事件与持久化

新增强类型事件：

- `budget.configured`
- `budget.usage.updated`
- `budget.threshold.reached`
- `budget.exhausted`
- `llm.request.completed`

RunStore 保存预算配置、累计用量和最终退出原因；恢复后不得重置已经消耗的预算。

### 验收标准

- 任意一次 LLM 调用都能归属到唯一 run、phase、agent 和 attempt。
- 重试不会绕过 Token、调用次数或金额上限。
- Team 并行 Worker 不会因竞态共同超出总预算。
- 未知模型不展示虚假金额。
- UI 同时展示上下文占用和 Run 预算占用，两者名称明确区分。

## 2.2 安全性：默认受限的副作用执行

### 当前问题

- Plan/Team 的隔离命令已强制进入 Docker，但 ReAct 仍可在 `FULL` scope 下直接运行宿主机命令。
- 项目级 MCP、Hook 和未来扩展在加载前缺少统一 Project Trust 决策。
- HITL 是用户授权，不是沙箱；用户批准不能绕过策略拒绝。
- Docker 沙箱已有禁网、只读根文件系统和资源限制，但安全状态没有成为统一 Run 状态。

### 设计

建立 `ExecutionSecurityPolicy`，统一决定：

- `READ_ONLY_HOST`：读取项目文件和本地上下文。
- `PROJECT_PATCH`：只能在隔离工作区修改项目，结果必须导出 PatchSet。
- `SANDBOX_COMMAND`：命令必须进入 Docker，默认禁网。
- `EXTERNAL_MUTATION`：必须通过专门工具策略和逐次 HITL；不能借 Shell 实现。
- `DENIED`：无论 HITL 是否开启都禁止。

默认行为：

- ReAct 的读取继续在宿主机执行。
- ReAct 的 `write_file` 改为工作区事务，任务结束时展示 Patch 摘要并应用。
- ReAct 的 `execute_command` 默认进入 Docker；只有显式可信本地档位才能使用受限 Host profile。
- Plan/Team 继续使用隔离工作区和 Docker，不改变其安全底线。

增加 Project Trust：

- 未信任项目只加载 AGENTS/CLAUDE 上下文，不启动项目级 MCP，不执行项目 Hook，不安装项目扩展。
- 交互模式首次询问；非交互模式默认拒绝项目可执行资源。
- 信任只说明可以加载项目资源，不代表工具获得更高权限。

统一命令画像：

- `MAVEN_COMPILE`
- `MAVEN_TEST`
- `READ_ONLY_SHELL`
- `PROJECT_BUILD`
- `CUSTOM_SANDBOX`

每个画像声明 executable、参数规则、超时、CPU、内存、PID、网络、环境变量和输出上限。

### 验收标准

- 所有模式下的项目写入都有 PatchSet 或 Side-Git 恢复证据。
- 所有隔离命令保持禁网、只读根文件系统、能力清空和资源限制。
- 项目级 MCP 在未信任项目中不会启动进程。
- HITL 不能批准 `DENIED` 操作。
- UI 明确展示 `host-readonly`、`sandboxed`、`external-approved`，禁止只显示模糊的 HITL on/off。

## 2.3 可靠性：统一持久运行、恢复和重试

### 当前问题

- RuntimeThreadStore、DurableTaskManager 和 AgentCheckpoint 分别维护运行状态，存在多个事实来源。
- 后台任务重启时把所有 `RUNNING` 简单放回 `ENQUEUED`，缺少 attempt、lease、幂等键和恢复原因。
- LLM、网络、Worker 修复和步骤 redo 都有重试，但缺少统一 attempt 事件及预算归集。
- Checkpoint、Patch Journal 和 Side-Git职责正确，但需要统一 run/turn/step 关联。

### 设计

建立 `RunStore` 接口和 SQLite 适配器：

```text
Run
- run_id / thread_id / branch_id / parent_run_id
- source: interactive | runtime_api | background
- execution_policy: react | plan_review | team_review
- status / version / lease_owner / lease_expires_at
- budget_config / budget_usage
- security_profile
- created / started / updated / finished
```

子记录：

- `RunAttempt`
- `RunEvent`
- `RunCheckpointRef`
- `RunArtifactRef`
- `RunQueueMessage`

合并规则：

- DurableTaskManager 不再拥有独立任务状态机，只负责后台提交与本地 Worker 轮询。
- Runtime API 和 CLI 都通过 RunCoordinator 创建 Run。
- AgentCheckpoint 继续以专用文件保存复杂 DAG 和 Patch Journal，但 RunStore 保存引用、版本和校验摘要。
- Side-Git 保存 workspace snapshot id，并与 run_id、turn_id 关联。

统一重试分类：

| 类型 | 是否自动重试 | 规则 |
| --- | --- | --- |
| LLM 429、过载、超时、网络、5xx | 是 | 指数退避、有界、计入预算 |
| 参数错误、认证、内容过滤 | 否 | 直接失败或等待用户修正 |
| 工具只读瞬时失败 | 条件允许 | 工具必须声明幂等 |
| 项目写入和外部副作用 | 默认否 | 只有幂等键和对账协议存在时才允许 |
| Reviewer 拒绝 | 不是基础设施重试 | 作为受限修复 attempt |
| 进程崩溃 | 恢复 | 先对账 Patch Journal，再恢复下一个安全点 |

### Temporal 演进决策

当前版本不添加 Temporal 依赖。未来满足以下条件时可以使用 Temporal：

- 服务端部署成为正式产品形态。
- Worker 跨进程或跨机器运行。
- 存在数小时或数天的审批、定时器、回调。
- 需要水平扩容、故障转移、集中运维和任务队列。

Temporal 必须作为 `WorkflowRuntime` 的第二个适配器，并替换本地调度职责：

```text
WorkflowRuntime
|- LocalWorkflowRuntime -> RunStore + local workers
`- TemporalWorkflowRuntime -> Temporal workflows + activities
```

禁止采用 `Temporal -> DurableTaskManager -> AgentCheckpoint` 的叠加结构。Temporal Workflow 只保存确定性控制状态和 Artifact 引用；提示词、代码、密钥、大工具输出和 Patch 内容不得直接写入 Temporal history。Temporal Activity 仍必须调用 BudgetGuard、ToolExecutionPipeline、Docker Sandbox、PatchSet 和 AuditLog。

### 验收标准

- 任意状态只有一个权威写入方。
- 进程崩溃后能够区分“未执行”“已执行未记账”“已提交”“提交一半”。
- 恢复不会重置预算，也不会重复应用 PatchSet。
- 所有 retry 都有 attempt 序号、原因、退避时间和最终结论。
- 本地发行版不依赖外部基础设施。

## 2.4 可观测性：统一事件、指标、追踪和审计

### 当前问题

- RunEvent、TraceRecorder、AuditLog、RAG audit 和各模块 metrics 分散。
- Trace 与 Audit 都是 JSONL，但缺少统一 run、turn、step、agent、attempt 关联字段。
- 当前 RunEvent 缺少预算、重试、沙箱、步骤和恢复事件。
- 终端状态栏只能显示部分运行状态，用户无法快速判断成本、安全和恢复情况。

### 设计

保留四类数据，不强行混成一个表：

1. `RunEvent`：产品状态和 UI 事实，可持久化、可重放。
2. `TraceSpan`：性能与调用链，允许采样和按期限清理。
3. `MetricPoint`：计数器、直方图和水位。
4. `AuditRecord`：安全事实，追加写、脱敏、独立保留策略。

所有记录共享：

- run_id
- thread_id
- turn_id
- branch_id
- step_id
- agent_id
- attempt
- timestamp

新增事件：

- `run.state.changed`
- `step.state.changed`
- `llm.request.started/completed/failed`
- `retry.scheduled/exhausted`
- `budget.*`
- `sandbox.started/completed/failed`
- `checkpoint.started/completed/failed`
- `recovery.reconciled`
- `snapshot.created/restored`

核心指标：

- Token：input、output、cached、context utilization、budget utilization。
- 成本：estimated cost、unknown pricing count、cost per successful run。
- 安全：host command count、sandbox command count、policy denial、HITL denial、external mutation。
- 可靠性：retry count、checkpoint age、recovery count、rollback count、queue wait、run duration。
- 工具：成功率、P50/P95 耗时、最大结果、截断次数。
- 多 Agent：Worker 并发、Reviewer 拒绝率、修复 attempt、最终通过率。

CLI 提供统一入口：

- `/status`：当前 Run 的成本、安全、可靠性和依赖健康摘要。
- `/inspect`：当前 Run 的步骤、attempt、工具和恢复详情。
- `/logs`：按 run_id 查看 trace；不与 MCP logs 混用。
- `/audit`：只查看安全审计。

Runtime API 继续使用 RunEvent JSON 投影；未来可增加 OpenTelemetry adapter，但不得让业务层直接依赖特定监控厂商。

### 验收标准

- 从任一失败 Run 能定位模型请求、工具、沙箱、重试、步骤和恢复链路。
- CLI 与 Runtime API 对同一个 Run 展示一致状态。
- Trace 关闭或写入失败不会改变业务终态。
- Audit 写入失败产生高优先级健康事件，不能伪装为审计成功。
- 所有持久记录执行统一敏感信息脱敏。

---

## 三、终端界面重构

## 3.1 设计方向

采用“工程控制台”风格：克制、高密度、稳定，不使用全屏窗口，不添加装饰性小字，不使用 Emoji 作为图标。颜色只表达语义：中性、运行、成功、警告、失败。

只保留四个视觉区域：

```text
stable transcript
  user / assistant / tool summary / diff / review

live activity
  current phase / current step / active worker / retry countdown

input line
  > message, /command, @path

bottom dock
  mode  model  budget  sandbox  persistence  trace  cwd
```

### 底部 dock

默认单行，按终端宽度逐级降级：

```text
REACT  gpt-5.6  ctx 41%  run 28%  ¥0.43/¥2.00  SANDBOX  SAVED  trace 8fd2  18s
```

- `ctx` 是本次请求上下文窗口占用。
- `run` 是整个任务预算占用。
- `SANDBOX/HOST-RO/EXTERNAL` 是实际执行安全域。
- `SAVED/PENDING/RECOVERING` 是持久化状态。
- `trace` 显示短 run id，不显示冗余说明文字。

窄终端依次隐藏 cwd、金额明细、trace、模型全名，但必须保留 mode、run budget、安全域和失败状态。

### Live activity

- ReAct：阶段、当前工具、重试倒计时。
- Plan：当前步骤、完成数、预算剩余。
- Team：稳定排序的 Worker 摘要；详细输出进入折叠块。
- 恢复：显示 checkpoint 对账、Patch Journal 处理和 Side-Git 状态。
- activity 只能重绘自己，不能覆盖 transcript。

### 工具块

默认只显示：工具名、关键参数、状态、耗时、安全域和结果大小。

```text
read_file  Config/App.java                 42 ms  host-ro
execute_command  mvn test-compile       8.2 s   sandbox
write_file  3 files / +84 -17            31 ms  patch pending
```

详细参数、输出和审计引用通过 Ctrl+O 展开。

### Session Tree

- `/session tree` 打开持久消息树 palette。
- 从任意历史节点创建新 branch。
- 切换 branch 只切换对话上下文。
- 如果该节点关联 workspace snapshot，界面单独提供“恢复文件”动作并要求确认；禁止暗中恢复。
- 删除旧 `/branch` 进程内语义；提供一个版本周期的命令别名，并明确迁移到 `/session branch`。

### 命令面收敛

顶层命令按资源归组：

| 新入口 | 吸收的旧入口 |
| --- | --- |
| `/run` | `/plan`、`/team` 的模式选择与恢复入口 |
| `/session` | `/branch`、`/clear`、会话树和会话历史 |
| `/workspace` | `/snapshot`、`/restore` |
| `/status` | `/context` 的运行摘要、`/policy` 的状态摘要 |
| `/inspect` | 当前 Run 的步骤、预算、重试、沙箱和 trace |
| `/settings` | `/config`、模型、预算档位、安全档位 |

`/model`、`/mcp`、`/skill`、`/memory`、`/browser`、`/index`、`/search`、`/audit` 保留，因为它们管理独立资源。旧命令先作为别名保留一个版本，帮助和补全只展示新入口。

## 3.2 UI 模块边界

将现有大而宽的 Renderer 接口拆为内部深模块：

- `RunProjection`：把 RunEvent 投影为不可变 `RunSnapshot`。
- `TranscriptView`：只追加稳定内容。
- `ActivityView`：只管理当前可重绘区域。
- `StatusDock`：只渲染 RunSnapshot 的摘要。
- `InteractionController`：统一 LineReader、HITL、Plan review、palette 和 Session Tree 输入所有权。
- `PlainEventRenderer`：把同一 RunEvent 降级为稳定文本。

Agent、Plan、Team 和工具不得直接调用 UI 方法或写 stdout；它们只产生 RunEvent。迁移完成后删除把 `PrintStream` 当业务事件通道的路径。

## 3.3 删除 Lanterna

删除范围包括全屏启动、窗口、Pane、独立会话快照、独立 HITL、Lanterna renderer 枚举与相关配置。Plain 继续作为无 ANSI 降级适配器。

---

## 四、分阶段实施计划

> 文件清单是实施边界。动手前必须重新用 CodeGraph 做影响分析；当前工作区已有未提交的 Multi-Agent 改动，实施时不得覆盖或混入这些改动。

### 阶段 0：冻结契约和建立迁移测试

**目标：** 在删除和合并前锁定现有外部行为。

**主要文件：**

- 修改：`src/test/java/com/devcli/runtime/api/RuntimeThreadStoreTest.java`
- 修改：`src/test/java/com/devcli/runtime/api/RuntimeApiServerTest.java`
- 修改：`src/test/java/com/devcli/cli/CliCommandParserTest.java`
- 修改：`src/test/java/com/devcli/cli/DevCliCompleterTest.java`
- 修改：`src/test/java/com/devcli/snapshot/SideGitManagerTest.java`
- 新增：`src/test/java/com/devcli/architecture/RuntimeOwnershipContractTest.java`

**步骤：**

- [ ] 固化 Run、Thread、Branch、Checkpoint、Patch Journal 和 Side-Git 的所有权矩阵。
- [ ] 为旧命令到新命令的映射建立兼容测试。
- [ ] 固化 Tree 切换不修改工作区、Workspace restore 不切换对话的测试。
- [ ] 完成该阶段代码后统一运行上述限定测试。

### 阶段 1：统一 Token 与成本治理

**目标：** 用一个 RunBudget 约束所有模型调用，并区分上下文窗口与任务预算。

**主要文件：**

- 重命名/重构：`src/main/java/com/devcli/memory/TokenBudget.java`
- 重构：`src/main/java/com/devcli/agent/AgentBudget.java`
- 修改：`src/main/java/com/devcli/agent/AgentExecutionEngine.java`
- 修改：`src/main/java/com/devcli/context/ContextProfile.java`
- 替换：`src/main/java/com/devcli/context/TokenUsageFormatter.java`
- 新增：`src/main/java/com/devcli/budget/RunBudget.java`
- 新增：`src/main/java/com/devcli/budget/RunBudgetPolicy.java`
- 新增：`src/main/java/com/devcli/budget/BudgetLedger.java`
- 新增：`src/main/java/com/devcli/budget/PricingCatalog.java`
- 修改：`src/main/java/com/devcli/runtime/event/RunEvent.java`
- 修改：`src/main/java/com/devcli/runtime/api/RunEventJsonCodec.java`
- 新增测试：`src/test/java/com/devcli/budget/`

**步骤：**

- [ ] 先写失败测试，证明重试和并行 Worker 会共享同一预算。
- [ ] 分离 ContextWindowBudget 与 RunBudget 命名和职责。
- [ ] 把 Planner、Worker、Reviewer、压缩和重试用量归入 BudgetLedger。
- [ ] 引入 PricingCatalog，删除 Provider 猜价回退。
- [ ] 持久化预算配置、使用量和退出原因。
- [ ] 完成阶段功能后统一运行 budget、agent、context、codec 限定测试。

### 阶段 2：统一 RunStore 与本地可靠运行

**目标：** 消除 RuntimeThreadStore、DurableTaskManager 和普通运行状态之间的事实来源重复。

**主要文件：**

- 新增：`src/main/java/com/devcli/runtime/store/RunStore.java`
- 新增：`src/main/java/com/devcli/runtime/store/SqliteRunStore.java`
- 新增：`src/main/java/com/devcli/runtime/RunCoordinator.java`
- 迁移：`src/main/java/com/devcli/runtime/api/RuntimeThreadStore.java`
- 收缩：`src/main/java/com/devcli/runtime/task/DurableTaskManager.java`
- 修改：`src/main/java/com/devcli/runtime/api/RuntimeSessionTurnRunner.java`
- 修改：`src/main/java/com/devcli/runtime/api/RuntimeApiServer.java`
- 修改：`src/main/java/com/devcli/agent/AgentCheckpoint.java`
- 修改：`src/main/java/com/devcli/snapshot/TurnSnapshot.java`
- 新增测试：`src/test/java/com/devcli/runtime/store/`

**步骤：**

- [x] 先建立 schema 迁移测试，保证旧 runtime.db 和 tasks.db 数据可以导入或只读迁移。
- [x] 引入 Run 与 Attempt，所有状态变更使用版本比较。
- [x] 将后台任务改为 Run 提交来源，删除独立终态写入。
- [x] Checkpoint 和 Side-Git 只向 RunStore 写引用与摘要。
- [x] 增加启动对账：lease、checkpoint、Patch Journal、Side-Git 引用。
- [x] 完成阶段功能后统一运行 runtime、task、checkpoint、snapshot 限定测试。

### 阶段 3：统一重试与恢复语义

**目标：** 基础设施重试、Reviewer 修复和崩溃恢复使用同一 Attempt 模型，但保持不同语义。

**主要文件：**

- 修改：`src/main/java/com/devcli/llm/LlmRetryExecutor.java`
- 新增：`src/main/java/com/devcli/runtime/RetryPolicy.java`
- 新增：`src/main/java/com/devcli/runtime/AttemptCoordinator.java`
- 修改：`src/main/java/com/devcli/agent/AgentOrchestrator.java`
- 修改：`src/main/java/com/devcli/agent/StepRedoTracker.java`
- 修改：`src/main/java/com/devcli/workspace/PatchSet.java`
- 修改：`src/main/java/com/devcli/agent/AgentCheckpoint.java`
- 修改：`src/main/java/com/devcli/runtime/event/RunEvent.java`

**步骤：**

- [x] 先覆盖 retryable/non-retryable、预算耗尽和副作用禁止重试测试。
- [x] 统一 attempt id、parent attempt、原因、范围、退避和最终状态，并写入 RunStore。
- [x] 将 Reviewer 修复标记为 correction attempt；step redo 单独计数，不伪装为网络重试。
- [x] 普通 Worker 只认领 enqueued；recovery_required 必须通过显式恢复入口，并在认领前提交 Patch Journal、checkpoint 和预算对账证明。
- [x] 完成阶段功能后统一运行 LLM retry、orchestrator、checkpoint、PatchSet、RunStore、DurableTaskManager 和事件编码限定测试。

阶段结果（2026-08-12）：checkpoint 协议升级为版本 5，版本 1 至 4 保持兼容；RunContext 注入事件、Attempt 与恢复引用窄出口，领域对象不再自行打开默认 runtime.db。全量测试按用户要求推迟到所有功能完成后统一执行。

### 阶段 4：统一安全策略和 Project Trust

**目标：** 所有执行模式共享同一安全决策，默认命令沙箱化。

**主要文件：**

- 新增：`src/main/java/com/devcli/security/ExecutionSecurityPolicy.java`
- 新增：`src/main/java/com/devcli/security/SecurityProfile.java`
- 新增：`src/main/java/com/devcli/security/ProjectTrustStore.java`
- 新增：`src/main/java/com/devcli/security/CommandProfile.java`
- 修改：`src/main/java/com/devcli/tool/ToolRegistry.java`
- 修改：`src/main/java/com/devcli/tool/ToolExecutionPipeline.java`
- 修改：`src/main/java/com/devcli/tool/command/DefaultCommandExecutionService.java`
- 修改：`src/main/java/com/devcli/mcp/McpServerManager.java`
- 修改：`src/main/java/com/devcli/hook/HookConfigLoader.java`
- 修改：`src/main/java/com/devcli/hitl/ApprovalPolicy.java`
- 新增测试：`src/test/java/com/devcli/security/`

**步骤：**

- [x] 覆盖未信任项目不启动项目 MCP/Hook/Skill 的测试契约。
- [x] 将 ToolEffect 映射收口到 ExecutionSecurityPolicy。
- [x] ReAct 命令默认路由 Docker，宿主机命令需要显式 profile。
- [x] CLI、Runtime API 和后台无头 ReAct 写入统一接入工作区事务与 PatchSet。
- [x] 把安全域和沙箱状态发为 RunEvent。
- [ ] 功能统一完成后统一运行 policy、HITL、command、MCP、workspace 限定测试。

阶段结果（2026-08-12）：Project Trust 控制项目级 MCP、Hook 与 Skill 的加载，非交互未知项目默认不信任；统一安全策略区分 host-readonly、project-patch、sandboxed、external-approved 与 denied，HITL 不能提升被拒权限。ReAct 的所有 `AgentSessionRuntime` 入口使用隔离工作区，成功后通过 PatchSet 原子提交，冲突时不污染主项目；命令默认使用禁网 Docker，只有 `TRUSTED_LOCAL` 安全档位与显式 `TRUSTED_HOST` 命令画像同时满足时才允许宿主机执行。安全事件通过 `security.decision` 与 `sandbox.execution` 进入运行事件流。按照用户要求，本阶段只完成实现、测试契约和编译检查，测试留到所有功能统一完成后执行。

### 阶段 5：合并 Plan 与 Team 的产品入口

**目标：** 保留两种审查强度，删除重复的顶层编排外壳和用户认知成本。

**主要文件：**

- 新增：`src/main/java/com/devcli/agent/StructuredExecution.java`
- 新增：`src/main/java/com/devcli/agent/ExecutionReviewPolicy.java`
- 迁移：`src/main/java/com/devcli/agent/PlanExecuteAgent.java`
- 迁移：`src/main/java/com/devcli/agent/AgentOrchestrator.java`
- 保留复用：`src/main/java/com/devcli/plan/ExecutionGraph.java`
- 保留复用：`src/main/java/com/devcli/plan/ExecutionArtifact.java`
- 修改：`src/main/java/com/devcli/cli/CliCommandParser.java`
- 修改：`src/main/java/com/devcli/cli/Main.java`

**步骤：**

- [x] 用契约测试锁定 plan review 与 team review 的差异。
- [x] 统一产品入口并复用共同执行内核、DAG、workspace、artifact 与 checkpoint 协议；两种差异状态机暂由内部策略适配器承载，避免布尔分支单体类。
- [x] PLAN_REVIEW 关闭独立 Reviewer；TEAM_REVIEW 启用 Worker/Pre-Review/Reviewer。
- [x] `/run --review=plan|team` 成为唯一结构化执行入口；旧 `/plan`、`/team` 已在阶段 9 删除。
- [ ] 完成阶段功能后统一运行 plan、team、graph、artifact、CLI 限定测试。

阶段结果（2026-08-12）：CLI 与旧 TUI 兼容入口都只选择 `ExecutionReviewPolicy` 并进入 `StructuredExecution`；`PlanExecuteAgent` 与 `AgentOrchestrator` 降为内部策略适配器。公共执行内核继续由 `AgentExecutionEngine`、`ExecutionGraph`、`ExecutionArtifact`、`WorkspaceExecutionSession`、`ToolRegistry` 和 `MemoryManager` 提供。按照用户要求，本阶段只完成测试契约和静态差异检查，测试留到全部功能统一完成后执行。

### 阶段 5A：循环收敛与证据门禁

**目标：** 防止重复观察造成无限循环，同时避免用全局最小轮数强迫普通问答调用无意义工具。

**设计边界：**

- 复用 `AgentBudget` 的硬轮数、相同工具参数指纹和重复错误熔断，不再新增第二套 MaxIterations。
- 新增有界 `ObservationFingerprint` 与 `LoopProgressGuard`；只保存归一化摘要或指纹，不复制完整工具结果。
- 连续观察高度相似时，第一次要求更换查询、工具或验证路径；策略切换后仍没有新证据才终止。
- `EvidenceRequirement` 按任务意图和结构化步骤类型启用：代码分析至少需要成功读取或检索证据，修改任务需要成功副作用和验证证据，稳定知识问答不强制工具。
- 不把隐藏思考原文再次注入 Prompt；工具调用仍与当前 assistant reasoning/content 保持同一消息协议，只校验声明目标与实际行动是否一致。

**步骤：**

- [ ] 为观察文本建立去时间戳、空白、路径噪声和截断标记的低成本归一化。
- [ ] 用有界 token 集合相似度或 SimHash 判断“没有新增信息”，避免调用 embedding 增加成本。
- [ ] 增加 `strategy_change_required`、`observation_stagnant` 和 `evidence_missing` 运行事件。
- [ ] 将证据门禁接入 ReAct、Plan task 和 Worker 的统一执行出口，替代各路径零散判断。
- [ ] 在基础工具策略 Prompt 中增加“观察等价时必须换方法”，但确定性 Guard 仍是唯一强制出口。
- [ ] 所有功能统一完成后运行 AgentBudget、AgentExecutionEngine、ReAct、Plan 和 Team 限定测试。

### 阶段 5B：统一工具契约与参数模型

**目标：** 把工具说明、参数、能力和执行约束收敛为一份不可矛盾的契约。

**目标结构：**

```text
ToolContract
├── identity: name / version / source
├── discovery: description / category / capabilities
├── inputSchema: JSON Schema
├── effect: ToolEffect
├── availability: credential references / provider state
└── executionPolicy: timeout / quota / concurrency / cost
```

**设计边界：**

- `ToolEffect` 与 `ExecutionSecurityPolicy` 继续作为危险性和沙箱决策的唯一来源，不新增 `dangerous`、`sandboxed` 布尔字段。
- `ToolOutput` 继续表达 status、errorCode、retryable、正文、图片、修改资源和强类型旁路载荷；耗时保留在 `ToolExecutionResult`，不复制到自由 metadata。
- 工具内部 LLM Token 必须进入共享 `RunBudget`；工具固定费用使用整数最小货币单位和明确币种，禁止使用 `double` 猜价。
- 参数支持 default、enum、minimum、maximum、pattern 和长度边界；未知字段继续拒绝。
- 只允许无损格式归一化，例如明确的数字字符串转整数；越界值不静默 clamp，副作用参数必须返回结构化校验失败。
- 参数 description 必须说明使用场景、格式、边界和示例；新增静态契约审计，不靠运行时猜测质量。

**步骤：**

- [ ] 新增不可变 `ToolContract`、`ToolIdentity`、`ToolCategory`、`ToolExecutionPolicy` 和 `ToolCost`。
- [ ] 扩展 `ToolParameter` 或迁移到统一 JSON Schema builder，保留旧构造器兼容窗口。
- [ ] 为认证要求保存凭据引用和可用状态，不读取或暴露密钥正文。
- [ ] 增加 `AUTH_REQUIRED`、`RATE_LIMITED`、`TOOL_UNAVAILABLE` 和 `COST_BUDGET_EXCEEDED` 等稳定错误码。
- [ ] 让内置工具、MCP 工具和 resource 虚拟工具通过同一契约投影给 LLM。
- [ ] 所有功能统一完成后运行 schema、ToolOutput、MCP validator 和工具 Provider 契约测试。

### 阶段 5C：工具注册、发现与按需暴露

**目标：** 降低工具选择噪声，防止重复注册和动态刷新破坏调用一致性。

**步骤：**

- [ ] `ToolRegistry` 保存 contract + executor，并建立 category、capability、source、version 和 schema fingerprint 索引。
- [ ] 同名注册默认拒绝；只有显式、同来源、版本可判定的替换才允许，MCP server 刷新继续原子替换自己的命名空间。
- [ ] 暴露顺序固定为：能力范围 → Skill 白名单 → Project Trust/凭据可用性 → 任务意图/步骤类型 → 剩余预算 → 相关性排序。
- [ ] 保留 `search_tools` 和 MCP 按需激活；3 至 5 个工具只作为简单任务经验值，不设固定上限，最终上限由模型能力与任务配置决定。
- [ ] 功能相近工具采用内置优先级和来源说明，不因类别相同直接删除有不同认证或语义的工具。
- [ ] 记录工具被展示或隐藏的稳定原因，供可观测投影使用。
- [ ] 所有功能统一完成后运行 ToolRegistry、tool search、动态 MCP 刷新和 Skill 权限测试。

### 阶段 5D：逐工具运行治理

**目标：** 单工具与并行工具共享一致的限流、超时、并发、成本和失败语义。

**步骤：**

- [ ] 所有工具调用统一经过逐工具 timeout；修复当前单工具调用只受底层实现约束、批次工具才有统一超时的问题。
- [ ] 使用 token bucket 或真实滚动窗口实现 run/agent/credential/provider 多级配额，不采用只记录上次调用时间的伪滑动窗口。
- [ ] 用公平 semaphore 限制逐工具和逐 provider 并发，限流结果携带 `retryAfter`。
- [ ] 外部工具执行前向 `RunBudget` 预留费用，完成后按实际值结算；未知价格在严格预算档位下不得伪装为零成本。
- [ ] 只有幂等、无副作用且结构化标记 retryable 的调用可自动重试，并继续占用 Attempt 与预算。
- [ ] CPU、内存等硬限制只对进程或容器工具声明和执行；JVM 进程内工具不虚假声称具有独立内存隔离。
- [ ] 超时返回后仍可能运行的非协作线程视为运行时缺陷，优先使用原生 HTTP/MCP/进程取消并在关闭时对账。
- [ ] 所有功能统一完成后运行 timeout、quota、concurrency、cost、retry 和 cancellation 测试。

### 阶段 6：持久 Session Tree 替换 CLI 进程内分支

**目标：** 统一 CLI 与 Runtime API 的消息树和分支模型。

**主要文件：**

- 删除：`src/main/java/com/devcli/cli/CliConversationBranchManager.java`
- 删除：`src/test/java/com/devcli/cli/CliConversationBranchManagerTest.java`
- 新增：`src/main/java/com/devcli/session/SessionTree.java`
- 新增：`src/main/java/com/devcli/session/SessionTreeService.java`
- 修改：`src/main/java/com/devcli/runtime/store/RunStore.java`
- 修改：`src/main/java/com/devcli/cli/Main.java`
- 修改：`src/main/java/com/devcli/cli/DevCliCompleter.java`

**步骤：**

- [x] 覆盖跨进程分支、消息节点 fork 可见性和工作区不变测试。
- [x] CLI 会话写入统一 Runtime SQLite；`RuntimeThreadStore` 作为会话门面，RunStore 继续只承载运行事实。
- [x] 实现 `/session tree|fork|use|status`，普通 turn 使用事件，只有新压缩边界写 checkpoint。
- [x] `/session` 完成持久迁移；旧 `/branch` 已在阶段 9 删除。
- [x] 完成阶段功能后统一运行 session、runtime branch、CLI 限定测试。

### 阶段 7：统一可观测模型

**目标：** RunEvent 成为 UI 和 Runtime 状态事实，Trace、Metric、Audit 保持专用存储。

**主要文件：**

- 扩展：`src/main/java/com/devcli/runtime/event/RunEvent.java`
- 新增：`src/main/java/com/devcli/observability/RunTelemetry.java`
- 新增：`src/main/java/com/devcli/observability/TraceSpan.java`
- 新增：`src/main/java/com/devcli/observability/MetricRecorder.java`
- 迁移：`src/main/java/com/devcli/trace/TraceRecorder.java`
- 迁移：`src/main/java/com/devcli/policy/AuditLog.java`
- 修改：`src/main/java/com/devcli/runtime/api/RunEventJsonCodec.java`
- 修改：`src/main/java/com/devcli/runtime/api/RuntimeApiServer.java`
- 新增测试：`src/test/java/com/devcli/observability/`

**步骤：**

- [x] 建立字段关联、脱敏和失败不影响业务终态测试。
- [x] 通过 RunEventEnvelope 为事件补齐 run/turn/step/agent/attempt/trace 上下文。
- [x] 将预算、沙箱、重试、恢复、checkpoint 与 Side-Git 引用接入统一投影。
- [x] 增加 Runtime `GET /v1/threads/{id}/snapshot` 查询模型；CLI 查询入口随终端 UI 阶段收敛。
- [x] 完成阶段功能后统一运行 event、trace、audit、runtime API 限定测试。

### 阶段 8：终端 UI 重构

**目标：** UI 只消费 RunSnapshot，统一 transcript、activity、input 和 dock。

**主要文件：**

- 新增：`src/main/java/com/devcli/render/state/RunProjection.java`
- 新增：`src/main/java/com/devcli/render/state/RunSnapshot.java`
- 新增：`src/main/java/com/devcli/render/inline/TranscriptView.java`
- 新增：`src/main/java/com/devcli/render/inline/ActivityView.java`
- 新增：`src/main/java/com/devcli/render/inline/StatusDock.java`
- 新增：`src/main/java/com/devcli/render/inline/InteractionController.java`
- 重构：`src/main/java/com/devcli/render/inline/InlineRenderer.java`
- 重构：`src/main/java/com/devcli/render/PlainRenderer.java`
- 收缩：`src/main/java/com/devcli/render/Renderer.java`
- 修改：`src/main/java/com/devcli/render/RendererFactory.java`
- 修改：`src/main/java/com/devcli/cli/Main.java`
- 修改：`src/main/java/com/devcli/cli/DevCliCompleter.java`

**步骤：**

- [x] RunEvent 到 RunSnapshot 的投影已由可观测性阶段覆盖。
- [x] StatusDock 按宽度实现字段优先级，新增状态文本不使用 Emoji。
- [x] 工具、retry、recovery、预算和安全状态经统一事件驱动；diff 正文仍保留稳定 transcript 专用渲染。
- [x] HITL、palette 与 LineReader 输入所有权接入 InteractionController；Session Tree 使用同一 palette 能力面。
- [x] Renderer 只消费 RunSnapshot；帮助和补全的到期别名在下一阶段删除。
- [x] 完成阶段功能后统一运行 renderer、status、completer、HITL 限定测试；PTY 手工验收仍需启动许可。

### 阶段 9：删除 Lanterna 与旧兼容层

**目标：** 删除第三套终端状态和过期命令实现。

**主要文件：**

- 删除：`src/main/java/com/devcli/tui/`
- 删除：`src/test/java/com/devcli/tui/`
- 修改：`src/main/java/com/devcli/render/RendererFactory.java`
- 修改：`src/main/java/com/devcli/cli/Main.java`
- 修改：`pom.xml`
- 更新：`README.md`
- 更新：`AGENTS.md`
- 更新：`docs/phase-22-jline-interaction-upgrade.md`

**步骤：**

- [x] 已确认 Lanterna 没有不可替代业务能力；必要能力由 Inline、Session Tree 和统一 HITL 覆盖。
- [x] 删除独立 ConversationSnapshot、HITL 和 Pane 状态。
- [x] 删除 Lanterna 依赖及 renderer 枚举。
- [x] 删除 `/plan`、`/team`、`/branch`、`/snapshot`、`/restore`；Side-Git 迁移为 `/workspace status|clean|restore`。
- [x] 所有功能完成后统一运行 quick、phase22、Runtime、Plan/Team、全量回归和打包构建。
- [ ] 获得用户许可后再启动真实终端进行手工验收。

### 阶段 10：未来 Temporal 适配器（条件触发，不属于当前版本）

**启动条件：** 服务端产品形态已经成立，并同时出现跨机器 Worker、长时间等待、故障转移或集中工作流运维需求。没有满足条件时，本阶段保持冻结。

**目标：** 用 Temporal 替换服务端调度实现，同时保持 Agent、预算、安全、Artifact 和观测协议不依赖 Temporal。

**预期文件：**

- 新增：`src/main/java/com/devcli/runtime/workflow/WorkflowRuntime.java`
- 新增：`src/main/java/com/devcli/runtime/workflow/LocalWorkflowRuntime.java`
- 新增：独立服务端模块中的 `TemporalWorkflowRuntime`
- 新增：独立服务端模块中的 Workflow / Activity 定义
- 保留：`src/main/java/com/devcli/runtime/store/RunStore.java`
- 保留：`src/main/java/com/devcli/budget/RunBudget.java`
- 保留：`src/main/java/com/devcli/security/ExecutionSecurityPolicy.java`
- 保留：`src/main/java/com/devcli/workspace/PatchSet.java`

**步骤：**

- [ ] 先证明 WorkflowRuntime 已存在两个真实适配器需求，禁止提前为假设扩展建立空接口。
- [ ] 定义 Workflow 确定性状态，只传递 run_id、step_id、attempt 和 Artifact 引用。
- [ ] 将 LLM、工具、沙箱和 Patch 操作放入 Activity，并保持幂等键与预算扣减原子性。
- [ ] 禁止在 Temporal history 中保存提示词、代码、密钥、大工具输出和 Patch 正文。
- [ ] 用 Temporal 替换服务端任务领取、定时器、重试和审批等待；删除服务端路径中的本地 Worker 调度，不允许双调度。
- [ ] 验证 Worker 崩溃、Activity 重放、版本升级、取消、预算耗尽和 Patch 幂等。

---

## 五、明确不做

- 当前版本不引入 Temporal Server、Temporal SDK 或 Temporal Cloud。
- 不用 Session Tree 自动恢复工作区。
- 不删除 Side-Git、PatchSet 或 Patch Journal。
- 不增加第四种 Agent 执行模式。
- 不增加无限制进程内第三方插件平台。
- 不新增 Web UI；本计划中的 UI 指终端 Inline UI。
- 不为了统一而把安全 Audit、性能 Trace、业务 RunEvent 混成一种自由文本日志。
- 不迁移 Java 技术栈。

## 六、最终验收矩阵

| 维度 | 必须证明 |
| --- | --- |
| 功能收敛 | 只有 ReAct 与 StructuredExecution 两类执行语义；CLI 不再有进程内分支 |
| 循环收敛 | 重复工具、重复错误和相似观察都能有界退出；证据型任务不能无证据完成 |
| 工具契约 | 元数据、Schema、能力和执行约束只有一个事实来源；安全字段不会互相矛盾 |
| 工具选择 | LLM 只看到当前能力和任务相关工具，未知工具仍可通过 search_tools 按需激活 |
| 工具治理 | 单个与并行调用都受逐工具超时、配额、并发和成本约束 |
| 成本 | 所有 LLM 调用和重试均计入 RunBudget；硬预算并发安全 |
| 安全 | 默认副作用隔离；未信任项目不启动可执行资源；策略拒绝不可由 HITL 绕过 |
| 可靠性 | RunStore 是本地运行事实来源；崩溃恢复不重复提交、不重置预算 |
| 可观测性 | 一个 run_id 能串联预算、模型、工具、沙箱、重试、checkpoint 和快照 |
| UI | Inline 与 Plain 消费同一 RunSnapshot；活动区不覆盖 transcript；窄终端可降级 |
| 恢复 | Tree 只恢复对话；Side-Git只恢复文件；Patch Journal 只对账提交 |
| 兼容 | 旧命令经过一个版本别名迁移；旧数据库有明确迁移路径 |
| 部署 | 本地 JAR 不依赖外部工作流服务；Temporal 仅保留未来适配器决策 |
