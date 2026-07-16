# LiveAgent 可复用架构提取

## 基线

- 上游仓库：`Stack-Cairn/LiveAgent`
- 本地参考目录：`live/`
- 提取日期：2026-07-15
- 提取提交：`8dc4b9d830af9d2a5549d7d10c267019d22ef90f`
- 使用方式：仅作为架构与交互参考，不把 Rust、Tauri、React 实现直接复制进 Java 核心

## 提取原则

1. 只补 DevCLI 当前缺少的能力，不重复建设已有状态源。
2. 所有新增副作用继续经过 ToolEffect、ToolAccessScope、HITL、Policy 和 AuditLog。
3. 现有 AgentExecutionEngine、ExecutionArtifact、WorkspaceExecutionSession 和 PatchSet 保持权威边界。
4. 优先提取协议、状态机和治理规则，不提取技术栈绑定代码。

## 值得吸收的能力

### 1. 长期记忆离线组织器

状态：已于 2026-07-15 完成第一阶段实现。

LiveAgent 将记忆维护拆成扫描、聚类、生成变更计划、风险门控和应用，并记录置信度、证据、审核状态、配额和执行报告。

DevCLI 可在现有长期记忆版本、过期、主题替换和冲突检测之上增加独立维护服务：

- 扫描当前有效记忆，按主题和类型形成有界簇。
- 只允许生成保留、合并、标记复核、删除候选和重写提示，不直接自由修改存储。
- 程序侧重新计算风险，不信任模型自报风险。
- 低风险操作允许按配置自动应用；跨主题、跨类型、低置信度、删除有效事实进入人工复核。
- 记录整理前后数量、压缩比例、拒绝原因和模型消耗。

第一阶段已实现有界扫描、JSON 数据载荷、KEEP/MERGE/REVIEW/REJECT 计划、一次协议修复、程序风险门控、低风险合并和运行报告。自动应用只接受同主题、同类型、全部未审核、覆盖完整且计划置信度不低于 0.9 的合并；已审核、跨主题、跨类型和删除倾向只在本次报告中标记为需要人工复核。当前不持久化复核队列；主题聚类、持久化运行历史和人工复核应用入口仍可后续增强。

### 2. 记忆证据与审核状态

状态：已于 2026-07-15 完成第一阶段实现。

LiveAgent 将来源引用、推理依据、置信度和审核状态作为结构化字段进入存储层，并由存储层统一降级不满足证据要求的置信度。

DevCLI 可增加以下最小字段：

- `confidence`：高、中、低。
- `sourceQuote`：用户原始陈述或可靠工具证据的短引用。
- `reasoning`：写入原因，不进入普通召回正文。
- `reviewState`：未审核、已审核、已拒绝。
- `conflictsWith`：与现有 supersede 链并存，用于保留争议信息。

结构化字段已经进入 MemoryEntry 与 Store 契约；SQLite 通过幂等补列兼容旧库。显式写入默认 REVIEWED，策略自动写入默认 UNREVIEWED，REJECTED 保留审计但不参与召回。旧 conflict metadata 暂时保留为兼容输出，结构化 conflictsWith 是新代码的权威来源。

### 3. 持久化压缩检查点

LiveAgent 使用不可变历史 Segment 和 Summary Checkpoint 保存压缩边界，后续请求只组合摘要与未覆盖尾部消息。压缩可以在发送前、工具执行后和上下文超限恢复时触发。

DevCLI 已有 ConversationHistoryCompactor、语义守卫、microcompact 和恢复上下文，因此只提取持久化边界。第一阶段已于 2026-07-15 完成：

- 仅用于 Runtime API 长线程，不改变默认 CLI 内存会话。
- RuntimeThreadStore 保存压缩消息窗口、覆盖事件范围、摘要和完整 `CompactBoundaryMetadata`，其中包含压缩前后 token、语义守卫结果、Skill、RAG epoch 与 MCP 快照。
- 默认在 32,000 token 阈值触发，允许通过环境变量或系统属性调整，最低 4,000。
- 恢复时使用最新有效检查点，并完整重放检查点之后的已完成 turn；没有检查点时恢复全部已完成 turn，不再固定依赖最近 20 轮。
- 检查点候选移除动态 system prompt、reasoning 和图片正文；持久化发生在 `turn.completed` 之后，写入失败不改变 turn 终态。
- 最新检查点损坏时回退到更早可解析版本，避免单条坏记录阻断 thread 恢复。
- 不新增独立文件账本；最近读写资源继续来自 WorkingMemory、ExecutionArtifact 和现有压缩恢复区。

### 4. 强类型运行事件协议

LiveAgent 让桌面端和 WebUI 消费同一套模型输出、thinking、工具调用、工具结果、状态和终态事件，Gateway 只转发协议，不执行业务工具。

DevCLI 已于 2026-07-15 完成第一阶段强类型事件出口：

- `AgentExecutionEngine` 将模型 reasoning/content 流、工具调用和工具结果转换为 `RunEvent`，ReAct、Plan task 与 SubAgent 共用该产生点。
- CLI Renderer 通过 StreamListener 适配器保持现有终端交互；Runtime API 通过事件 sink 持久化稳定 JSON，并输出 SSE。
- Runtime 生命周期和 checkpoint 事件也使用同一类型体系，不再在服务端手工拼接 JSON。
- 流式 content 已写入事件时不再重复追加最终输出；Provider 只流式输出 reasoning 时，无头运行器从最终 assistant history 恢复答案。
- 工具参数在协议中保持 JSON 对象；工具结果包含结构化状态、错误码、重试标记、耗时和图片数量，不包含图片正文。
- 终端文本不再作为远程客户端的协议输入；工具执行权仍留在 DevCLI 运行端，远程层只负责认证、队列、重放和取消。

### 5. 持久化子代理身份与恢复

LiveAgent 子代理具有稳定标识、私有上下文、运行记录、消息总线和 worktree 恢复能力。

DevCLI 已于 2026-07-16 完成稳定身份和恢复语义提取：

- 保留 Planner、Worker、Reviewer、DAG 与 `ExecutionArtifact`，没有改成自由聊天式编排。
- Multi-Agent checkpoint 协议版本 4 保存稳定身份、上下文 schema 版本、步骤到 Worker/Reviewer 的绑定、单调消息游标和有界最近摘要。
- resume 优先按 checkpoint 重建 Worker 拓扑；运行时 Worker 数量配置变化不会改写原步骤绑定，失败步骤重做仍使用原 Worker 身份。
- 恢复摘要通过 SubAgent system prompt 的恢复段注入；schema 不兼容时丢弃旧摘要，只保留任务终态和步骤分配。
- 每次 Worker 或 Reviewer 任务级结果推进游标并严格保存；相同步骤、相同摘要的重复边界不会重复推进。
- 恢复继续复用 checkpoint、WorkspaceExecutionSession、PatchSet 和资源租约，没有持久化完整私有对话对象图，也没有新增消息数据库或重复状态源。

### 6. Hook 生命周期

LiveAgent 提供 agent、turn、message 和 tool execution 四层生命周期 Hook。DevCLI 已于 2026-07-15 完成受控实现，但没有照搬任意 shell 或 HTTP Hook：

- `AgentExecutionEngine` 统一触发四层幂等生命周期，ReAct、Plan task 和 SubAgent 共用顺序与异常闭合语义。
- Hook 动作只能调用 ToolRegistry 已注册工具，继续经过 ToolEffect、能力范围、参数校验、HITL、策略和审计管线。
- READ_ONLY / LOCAL_CONTEXT 强制收窄到只读 scope；其他副作用必须显式允许、启用 HITL，并命中逐次审批策略，否则拒绝。
- 用户级与项目级配置按 id 合并，项目覆盖用户定义；支持运行上下文占位符和 64 条上限。
- `warn` Hook 失败只记录警告；`required` Hook 失败进入 Agent 统一失败出口，包括 agent_end 阶段。

## 明确不采用

- 不替换 AgentExecutionEngine。LiveAgent 的前端运行适配层职责过多，模型流、工具执行、搜索状态、恢复和界面事件耦合在同一大模块中。
- 不采用 `isError + 文本` 作为统一错误模型。DevCLI 继续使用 ToolStatus、ToolErrorCode 和 retryable。
- 不采用对 Bash、Agent 等工具名称做特殊并行分支。并行能力继续按统一 executeTools 和副作用范围控制。
- 不允许 Shell 工作目录逃逸项目边界。LiveAgent 的外部工作目录能力不符合 DevCLI 的 PathGuard 与隔离策略。
- 不使用普通 worktree 自动应用替换 PatchSet、冲突预检、写前日志、回滚和 Pre-Review。
- 不降低压缩校验标准。近期路径或命令存在性校验只能作为补充，不能替代必须、禁止、默认值、版本和配置赋值保护。
- 不引入 Tauri、Rust Gateway、React 桌面技术栈。
- 暂不引入 Cron 和即时通信渠道；这些属于产品扩展，不是当前核心可靠性目标。

## 实施顺序

1. P1：记忆证据、审核状态和离线组织器第一阶段已实现。
2. P1：Runtime API 持久化压缩检查点已实现。
3. P2：强类型运行事件协议及 Renderer 适配第一阶段已实现。
4. P2：受控 Hook 生命周期已实现。
5. P3：持久化子代理身份与跨进程恢复已实现。

每项需要独立设计、针对性测试和分功能提交，禁止一次性横跨 Memory、Runtime、Renderer 和 Multi-Agent。
