# DevCLI

DevCLI 是面向代码仓库的 Java Agent CLI。它将大语言模型、工具、代码检索、任务记忆、隔离工作区、人工审批和运行恢复组织成一个本地开发运行时。

## 总体架构

```text
CLI / Runtime API / Headless
            │
            ▼
       RunCoordinator
            │
            ├── Agent                     默认 ReAct 主 Agent
            │    └── DelegationSession    按需委派的独立子 Agent
            │
            └── AgentOrchestrator         显式 /plan 编排
                 ├── PlanCoordinator
                 ├── StepExecutionCoordinator
                 ├── ReviewCoordinator
                 └── CheckpointCoordinator

共享运行时
├── AgentExecutionEngine      Agent / Turn / Tool 统一控制流
├── ToolRegistry              内置工具、MCP 工具和执行管线
├── PromptAssembler           分层提示词和上下文装配
├── MemoryManager             会话、任务和长期记忆协调
├── CodeRetriever             代码 RAG 检索
├── WorkspaceExecutionSession 隔离工作区和 PatchSet
├── McpServerManager          MCP 连接与动态工具生命周期
├── SnapshotService           Side-Git 快照与回滚
├── RunStore / TraceRecorder  运行事件与追踪
└── Renderer                  inline / plain 终端输出
```

## 请求与执行模式

### 默认 ReAct

主 Agent 负责理解需求、选择工具、推进循环和最终验收。任务需要调查、拆解、独立实现或独立复核时，主 Agent 通过 `delegate_task` 按需调用子 Agent。

默认路径不强制生成 DAG。简单任务保持单 Agent 的低延迟；复杂任务通过委派增加隔离和独立视角。

### 显式 `/plan`

`/plan` 是面向长流程的重型编排模式，适用于并行 DAG、检查点恢复、强验收和审计场景：

```text
Planner
  → ExecutionGraph / AcceptanceCriteria
  → Worker 隔离执行
  → PreReviewVerifier 确定性检查
  → Reviewer 语义验收
  → PatchSet 版本校验与归并
  → Final integration
```

普通交互任务不自动切换到 `/plan`。

## 多 Agent 边界

```text
主 Agent：理解需求、分配工作、整合结果、最终验收
explorer：只读调查和证据收集
planner：只读拆解和验收设计
worker：隔离工作区中的代码修改
reviewer：只读、独立、基于证据的复核
```

子 Agent 只继承冻结的系统规则、角色提示词、显式任务和必要背景，不复制父会话、兄弟消息或长期记忆。每个子任务拥有独立的上下文历史、压缩器、Skill 副本、工具范围、资源租约、取消令牌和 `RunContext`。

委派报告由程序保存并通过 `report_id` / `upstream_report_id` 原文传递，避免模型二次转述造成证据损失。Worker 报告包含修改资源、前后哈希、工具证据和副作用失败状态。

## 工具执行管线

所有工具经过统一管线：

```text
取消检查
→ 工具存在性
→ 能力范围
→ Skill 权限
→ JSON Schema 参数校验
→ HITL 审批
→ 审计记录
→ 策略判定
→ 结果尺寸治理
```

工具副作用分为：

```text
READ_ONLY → LOCAL_CONTEXT → PROJECT_MUTATION → HOST_PROCESS → EXTERNAL_MUTATION
```

并行工具最多 4 路且保留调用顺序。参数错误、策略拒绝、命令非零退出、超时和取消都以结构化状态回传模型。

工具调用参数生成稳定语义指纹，用于重复动作检测、停滞提醒和硬熔断。只读结果允许会话级短期缓存；副作用操作或项目切换会清理缓存。

## Prompt 与上下文

Prompt 按稳定性分层：

```text
base
→ personality
→ mode
→ approval
→ project_context
→ skills
→ context_management
→ handoff
```

稳定层前置、易变层后置，以提高模型前缀缓存命中。模型上下文由 `ContextProfile` 和 `TokenBudget` 共同治理，不使用固定字符数代替 Token 预算。

上下文压缩只负责当前运行窗口：超大消息先做 microcompact，历史摘要按预算维护，文件引用、工具证据、失败尝试和下一步动作以结构化恢复段保留。压缩不会改变任务状态、长期记忆或工作区状态。

## Skill 系统

Skill 是可路由、可验证、可维护的知识单元，不是默认注入的长手册：

```text
Skill 索引
  → 任务相关选择
  → load_skill 分页加载
  → reference 按需读取
  → allowedTools 限制工具
  → 记录实际激活
```

Skill 来源分为 builtin、user 和 project 三层。project Skill 默认不可信，必须显式信任；其正文带有不可信参考资料边界，不能覆盖系统规则、提升权限或改变执行结构。

推荐的 Skill 内容边界：

```text
SKILL.md       触发条件和导航
rules/         稳定约束
workflows/     可执行流程
references/    代码地图和详细资料
gotchas/       已验证的高成本陷阱
scripts/       确定性检查
```

索引展示、正文激活和 reference 激活分别统计，区分“被列出”和“真正改变了任务行为”。

## 记忆分层

```text
conversationHistory + RollingSummary  当前线程上下文
SessionMemory                         当前任务运行投影
LongTermMemory                        跨任务稳定事实
```

`SessionMemory` 保存本任务的待办、当前工作、下一步动作、工具证据和失败尝试，不跨进程持久化。长期记忆由独立 Curator 处理，经过脱敏、限长、证据检查和作用域隔离后进入晋升队列。

召回只记录观测，不因“被检索”自动续期。用户确认、同值重复显式保存或验证通过等强信号才会刷新新鲜度并延长有效期。长期记忆的来源以脱敏快照和 SHA-256 固化，不依赖原始会话仍然存在。

## 代码 RAG

```text
JavaParser / 文件分块
  → keyword + semantic + bounded graph
  → RRF 倒数排名融合
  → symbol-aware boost
  → CrossEncoder rerank
```

索引按文件 generation 和项目 epoch 管理并发。增量索引先写入影子表，通过 CAS 校验后原子提升；旧 epoch 结果不可见。检索结果标记 `CURRENT`、`STALE` 或 `DIRTY`，必要时回读实时文件校验。

`search_code` 负责语义、符号和关系检索；`grep_code` 是独立的精确定位工具，用于类名、方法名、配置键和固定文本。

## 隔离工作区与 PatchSet

副作用任务使用可替换工作区后端：Git worktree、文件系统写时复制或有界复制。敏感文件、符号链接、路径逃逸和工作区边界由统一策略控制。

```text
Worker 工作区
  → 文件内容与权限快照
  → beforeHash / afterHash
  → 资源租约与版本检查
  → 项目锁 + 跨进程文件锁
  → 原子应用 PatchSet
  → 失败回滚或 checkpoint
```

同一文件不允许多个运行中步骤并发写入。未通过版本检查、Reviewer、策略或验收的补丁不会写入主项目。

命令默认在禁网、只读根文件系统的 Docker 沙箱执行；`HOST_WARN` 是显式的受限主机检查模式，不作为自动降级路径。Maven 本地仓库路径只接受显式配置的绝对目录。

## Reviewer 与验收

确定性验证和模型评审分层：

- 编译、测试、哈希、权限和版本校验由工具负责；
- Reviewer 负责静态和语义核对；
- 验收标准声明验证方式、验证器和适用节点；
- `critical/high` 问题阻断，`normal` 问题进入 advisory；
- 缺少真实工具证据时不能伪装为通过。

默认 ReAct 委派只在高风险条件下触发独立 Reviewer，例如大范围修改、关键安全资源或副作用工具失败。Reviewer 可以配置独立模型；配置不可用时失败关闭。

## 运行时、恢复与观测

CLI、Runtime API、后台任务和无头执行都通过 `RunCoordinator` 创建独立 `RunContext`。同一 thread 的 turn 串行执行，不同 thread 可以并行。

执行内核输出强类型 `RunEvent`，覆盖模型调用、工具执行、结果配对、取消、预算退出、失败和完成。`RunStore` 保存运行生命周期，`TraceRecorder` 按 `runId` 写入结构化 trace。

Checkpoint 保存执行图、执行产物、验收元数据、PatchSet 写前日志、文件权限、步骤身份、失败摘要和重做额度，不保存完整的 SubAgent 对话对象图。恢复时重建原拓扑并重新验证，不直接信任旧工作区。

失败统一提供原因、分类、下一步动作，以及重试、人工接手、接受部分结果和回滚选项。

## 设计原则

1. 主 Agent 负责决策和最终验收，子 Agent 只承担边界清晰的子任务。
2. 默认路径轻量，复杂能力按需启用。
3. 模型负责理解和生成，程序负责权限、状态、证据和一致性。
4. 读取、修改、验证、归并和恢复拥有明确边界。
5. 被加载不等于已生效，模型声明成功不等于任务验收通过。
