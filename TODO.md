# TODO

## 2026-08-22 Plan 入口归一

- 状态：已完成
- 已实现：删除 `/team` 与 `/plan --team` 兼容解析；TUI `/plan` 与默认 CLI 统一进入 `AgentOrchestrator` 的 Planner/Worker/Reviewer 链路
- 影响范围：CLI 命令解析、TUI 编排入口、命令测试和公开说明
- 验证：命令解析、补全与主代码编译限定验证
- 未验证：未启动项目，未执行真实 TUI 和真实 LLM 编排

## 2026-08-22 多智能体版本化上下文与记忆协议

- 状态：已实现并通过协议限定验证
- 已实现：父 Registry 与隔离 fork 共享 `ContextVersionLedger`；Java 符号与普通 file 指纹统一进入写闸门；generation/mtime/size/dirty 缓存避免无变化文件重复解析
- 已实现：`write_file` 前置校验与 PatchSet 应用前提交校验共同覆盖直接写入和命令间接写入；`STALE_CONTEXT -> REFRESHING_CONTEXT -> RUNNING/FAILED_RETRYABLE` 使用强类型 `RunEvent`，刷新后可安全重写同一依赖文件
- 已实现：索引构建使用 dirty 标记、`base_epoch` CAS、事务内原子交换和 `CURRENT/STALE/DIRTY` 检索标记；DIRTY 结果回读实时文件
- 已实现：长期记忆按 `subject + predicate + scope` 稳定键管理修订；未确认候选隔离为非 ACTIVE，确认后原子 supersede；有效性、新鲜度、相关性和证据权重分层，移除全局 `0.5` 衰减下限
- 已实现：新增 `protocol-regression` Maven profile、确定性故障模拟器、仓库固定 JSON 基线和 `target/benchmark-reports/protocol-regression.json` 报告
- 影响范围：Agent 执行内核、ToolRegistry、Workspace/PatchSet、Code RAG/VectorStore、LongTermMemory/MemoryRetriever、Runtime 事件、Maven profile 和协议测试
- 验证：`mvn -q -Pprotocol-regression test`、主代码编译和跨模块限定回归通过；共享账本、PatchSet 门禁、刷新重写、索引 CAS、pending 确认、SQLite 重载和原子失败回滚均有确定性用例
- 全局回归：`mvn -q -Pquick test` 执行 1594 项，2 项既有失败、4 项跳过；失败为 `ConversationHistoryCompactorStabilityTest` 固定摘要峰值 19285 超限，以及 `TraceRecorderTest` 脱敏断言，均已单独复现且对应模块不在本次改动范围
- 未验证：真实 LLM、真实 Docker、跨 JVM 并发索引、常驻 WatchService dirty 监听和真实长期运行资源回收；未启动项目
- 剩余风险：大文件 `file#N` 分段证据暂不进入写闸门；外部进程绕过 PatchSet 提交流程的直接主目录写入不在隔离 Worker 协议内

## 2026-08-22 符号证据写入门禁与普通对话轮数

- 状态：已实现并通过限定验证
- 已实现：`search_code` 记录 Worker 的 Java 符号依赖和源内容指纹；其他 Worker 修改依赖符号后，写入前确定性拦截；重新 `read_file` 后清理该文件的旧观察并恢复写入；普通 ReAct 默认硬轮数从 50 提高到 100，保留系统属性覆盖
- 影响范围：`StaleWriteBarrier`、`ToolProvider`、`ToolRegistry`、`RagToolProvider`、`AgentBudget` 及相关测试
- 验证：符号门禁、写入集成、AgentBudget、资源租约、工具注册、工作区执行和 RAG Provider 限定测试通过；`git diff --check` 通过
- 未验证：未运行全量测试，未启动项目，未验证真实 LLM、真实索引重建和跨进程恢复
- 剩余风险：当前符号依赖仍是保守的直接证据校验，尚未接入 `CodeIndex` 的原子 epoch 交换和完整调用图收窄

## 2026-08-20 两层记忆运行闭环

- 状态：已实现并通过限定验证
- 设计文档：`docs/superpowers/plans/2026-08-20-memory-runtime-closure.md`
- 已实现：`SessionMemory` 增加任务生命周期、覆盖式工作状态、带来源和序列的分级证据、失败摘要、里程碑压缩与统一 Token 预算；Multi-Agent 共享同一投影并按角色渲染
- 已实现：大文件只注入元数据、摘要和引用时，内容型请求由程序强制 `read_file`；跨轮保留有界引用批次，元数据请求不强制回读，错误路径或连续失败后关闭推理链
- 已实现：长期记忆同主题等价事实去重，类型化当前状态证据可以立即使旧事实失效；敏感保存使用一次性确认编号并只缓存脱敏文本；规则与稳定事实分离，旧 pinned facts 只进入待分类报告
- 影响范围：SessionMemory、长期记忆冲突、文件引用回读、规则管理、敏感确认、Agent/Plan/Multi-Agent 运行装配、CLI 与相关文档
- 验证：SessionMemory、执行内核、长期记忆、规则、CLI、工具注册、Prompt 和编排角色视图相关限定测试通过；`git diff --check` 通过
- 未验证：未运行全量测试，未启动项目，未验证真实 LLM、真实终端交互和跨进程恢复
- 剩余风险：本地 Token 估算与 Provider tokenizer 仍可能存在偏差；类型化当前状态失效需要更多工具逐步接入 `CurrentStateObservationSideChannel`

## 2026-08-18 Team 可判定验收与执行前评审

- 状态：已实现并通过限定验证
- 设计文档：`docs/superpowers/plans/2026-08-18-verifiable-acceptance-gate.md`
- 增强设计：`docs/superpowers/plans/2026-08-18-scoped-acceptance-evidence-escalation.md`
- 面试文档：`docs/interview-agent-architecture-review.md`
- 生产化问答：`docs/interview-agent-current-vs-production-qa.md`
- 已实现：验收标准强制声明 `TOOL` 或 `HUMAN`、判定信号和验证器；执行前拒绝缺失字段、重复 ID、未知工具和具有项目写入副作用的验证器；无效计划进入有界 Planner 修复
- 已实现：每条标准通过 `applies_to` 绑定有效 DAG 节点或 `FINAL`；Planner 原始节点 ID 规范化时同步重写验收目标；普通 Worker/Reviewer 只接收节点局部标准，Final integration 重新检查全部标准
- 已实现：确定性预检后由独立、无工具上下文的计划 Reviewer 检查原始需求到节点和验收标准的映射；结构化拒绝进入 Planner 有界修复，评审协议错误失败关闭；机器通过后用户仍可执行、补充重规划或取消；未完成 checkpoint 恢复前重新评审
- 已实现：Reviewer 声称 TOOL 标准通过时，声明验证器必须出现在本轮真实成功工具调用中；Pre-Review 实际执行的命令计为 `execute_command` 证据，其他工具不能替代
- 已实现：Reviewer 重试和原位重做结束后，最终结果显式输出失败节点、两类额度、最后原因、checkpoint ID 和人工处理选项，不自动重写整张 DAG
- 已实现：checkpoint 协议升级到版本 7；旧协议缺失适用节点时迁移为 `FINAL`，未声明验证方式时迁移为人工验收；没有可执行验收标准的未完成 checkpoint 拒绝恢复
- 验证：计划评审协议、SubAgent、Orchestrator、CLI 和 checkpoint 专项回归 124 项通过；全量回归共执行 1592 项测试，0 项失败、10 项跳过，主代码与测试代码编译通过
- 未验证：真实 LLM、真实 Docker、真实终端交互和外部 MCP；未启动项目
- 剩余风险：复杂自动验证的工具参数仍由 Reviewer 根据上下文选择；产物 Reviewer 问题列表尚未强制携带文件、行号、期望值和实际值；Planner 与计划 Reviewer 仍可能共享同一语义偏差，关键任务需要外部需求清单、隐藏测试或人工确认兜底

## 2026-08-16 Team 在位重做恢复增强

- 状态：已实现并通过限定验证
- 已实现：checkpoint 协议升级到版本 5，持久化每个步骤已经消耗的在位重做次数；进程恢复后沿用原额度，不再重新获得一次重做机会
- 已实现：每次在位重做记录步骤 ID、重做次数、失败原因、已修改文件和记录时间；另行保存未完成重做标记，恢复时不会重复执行额度耗尽的失败步骤，也不会丢失中途崩溃的合法重做
- 影响范围：Team 步骤失败恢复、checkpoint 兼容与审计、在位重做测试
- 未实现：执行中动态增加或删除任务、失败后自动修改整个依赖图、外部支付或消息等副作用补偿、每次 Agent 与工具尝试的完整事件账本
- 验证：`StepRedoTrackerTest`、`AgentCheckpointTest` 与 `AgentOrchestratorTest` 共 95 项通过；主代码与测试代码编译通过
- 剩余风险：恢复会继续执行已经批准但尚未形成终态的重做步骤；不提供跨外部系统的幂等或补偿保证

## 2026-08-14 多用户与多租户会话隔离

- 状态：设计方案已完成，尚未实施
- 设计文档：`docs/multi-user-session-isolation-design.md`
- 当前结论：现有 Runtime 只实现单一可信本地用户下的 thread 级会话隔离；全局 API Key、无 tenant_id 的存储、共享项目路径、长期记忆和审计目录均不满足多租户安全要求
- 目标：保留默认 `local` 模式，新增失败关闭的 `server` 模式；统一引入 TenantContext、SessionKey 和 TenantResourceScope，并完成认证授权、数据范围、会话执行、工具资源、配额和强沙箱隔离
- Hook 决策：保留现有受控 Hook，不新增第二套框架；多用户实现需扩展租户上下文、配置来源、用户审批、租户预算和审计字段，禁止让 Hook 承担身份认证、资源授权、数据库过滤和 PatchSet 冲突校验
- 实施顺序：本地租户兼容 -> 认证与存储范围化 -> SessionCoordinator/TurnRuntime 拆分 -> 项目、记忆、RAG、Hook、MCP、浏览器和凭据隔离 -> Worker 沙箱、配额、公平调度和分布式租约 -> 安全验收
- 影响范围：runtime、agent、tool、workspace、memory、rag、hook、mcp、browser、policy、audit、配置、迁移、测试和部署
- 未实现：本条全部为待实施设计；当前 Runtime API 不得经反向代理直接作为多用户服务暴露
- 验证计划：跨租户 API 攻击矩阵、相同资源 ID 复合范围、会话串行与幂等、缓存和记忆泄漏、路径逃逸、Hook/MCP/Browser/凭据隔离、配额公平性、多节点 lease/fencing、崩溃恢复和旧数据迁移
- 剩余风险：组织内会话共享策略、管理员正文访问边界、租户 BYOK、PostgreSQL 部署和 Worker 容器平台仍需在实施前确定

## 2026-08-14 DeepSeek Harness 可靠性机制对照

- 状态：已实现并通过限定验证
- 已实现：重复提醒和工具超时配置对语义非法值直接拒绝；工具调用使用独立期限和调用级取消信号，命令、Web 与 MCP 传递取消并在执行停止后返回；MCP 发送标准取消通知
- 已实现：模型请求前写入 `model.context` 完整消息快照，`model.message` 保存用户、系统内部、插件、转向、跟进、助手和工具来源；Runtime 只从已完成 turn 恢复，新事件协议优先，旧 turn 输入输出与 checkpoint 保持兼容
- 已实现：新增版本化会话投影缓存，记录日志身份、事件游标、标题、状态、Token、费用及工具/Hook 审计；缓存损坏、版本或分支身份不匹配时从事件日志重建
- 已实现：工具契约携带普通、终端、差异和位置展示类型，工具调用与结果事件持久化展示元数据，Plain、Inline、Lanterna 和 Runtime API 使用同一结构化字段
- 已实现：Hook 调用与结果成对记录稳定 Hook id、调用 id、耗时、状态和决策；按 `BLOCK > WARN > CONTINUE` 合并，多 Hook 全部执行后再阻断；生命周期结束前等待后台 Hook，安全权限和 HITL 边界不变
- 已补强（2026-08-17）：执行内核按原始 `tool_call_id` 对工具结果去重、拒绝未知结果、补齐缺失结果并恢复原始顺序；并行危险工具的人工审批使用共享公平锁串行化；工具契约声明 `COOPERATIVE` / `INTERRUPT_ONLY` 取消能力；新增强类型执行状态事件，并区分重复提醒与硬熔断动作
- 影响范围：工具执行与 Provider、MCP JSON-RPC/transport、运行事件、Runtime 会话恢复和投影、终端渲染、Hook 生命周期及配置
- 验证：`mvn -q -DskipTests test-compile` 通过；工具取消、MCP、事件协议、Hook、渲染、压缩和 Web 相关 20 个限定测试类共 188 项通过
- 未验证：未启动项目，未运行全量测试；未执行依赖 SQLite 的 Runtime 会话存储与 API 测试
- 剩余风险：`INTERRUPT_ONLY` 第三方进程内工具仍可能忽略线程中断，系统会等待其实际结束，不能提供进程隔离级强制终止；模型上下文事件按现有安全约束不持久化图片正文，只保留文本和图片数量

## 2026-08-12 运行治理、能力收敛与终端界面重构

- 状态：分阶段实施中
- 设计文档：`docs/superpowers/plans/2026-08-12-runtime-governance-terminal-ui-consolidation.md`
- 目标：删除重复能力，合并 Plan/Team 的公共执行链，使用持久 Session Tree 取代 CLI 进程内分支，保留 Side-Git、PatchSet、Checkpoint 各自不可替代的恢复职责；终端只保留 Inline 与 Plain
- 已实现（2026-08-18）：公开入口收敛为 ReAct 与 `/plan`；`/plan` 固定进入 Planner/Worker/Reviewer、Pre-Review、checkpoint 和隔离提交链路，串行或并行由 DAG 与资源冲突决定。`/plan --team` 与 `/team` 曾短期保留解析兼容，已于 2026-08-22 删除；STANDARD profile 与 `PlanExecuteAgent` 仅保留内部兼容
- 已实现（2026-08-14，第二批）：抽取 `AgentRuntimeSupport` 与 `AgentStreamPresenter`，统一四条 Agent 路径的运行装配和流式状态机；Plan `Task` 与 Team `ExecutionStep` 实现公共只读 `ExecutionNode`；关键启动配置统一使用 `ConfigResolver` 并拒绝显式非法值；新增 `RunStore` / `SqliteRunStore` / `RunCoordinator`，后台任务和 Runtime API 共用 `runtime.db` 与同一 Run 状态；旧 `tasks.db` 只读导入；持久 `SessionTreeService` 替换 CLI 进程内分支；CLI JSONL 归档降级为可选诊断导出；生产入口不再进入 Lanterna，旧配置映射到 Inline
- 已验证（2026-08-14）：主代码与测试代码编译通过；配置、RunStore、旧库导入、后台任务、Runtime API、Session Tree、CLI、渲染器、执行图、ReAct/Plan/Team 公共内核等 21 个限定测试类共 329 项通过
- 成本控制：新增统一 RunBudget、预算档位、并行原子账本和 PricingCatalog；Planner、Worker、Reviewer、压缩与重试全部计入同一 run，未知模型不得展示猜测价格
- 安全性：新增 Project Trust 与统一 ExecutionSecurityPolicy；ReAct 命令默认沙箱执行，项目写入使用工作区事务与 PatchSet；HITL 不得绕过策略拒绝
- 可靠性（部分实现）：RuntimeThreadStore、DurableTaskManager、Runtime API turn 与 CLI turn 已统一写入 RunStore；后台崩溃残留保留 attempt 和恢复原因后回到队列。AgentCheckpoint、Patch Journal 和 Side-Git 引用尚未接入 RunStore
- 可观测性：RunEvent 作为 UI 和 Runtime 状态事实，Trace、Metric、Audit 保持专用存储；统一 run/turn/step/agent/attempt 关联，并增加预算、沙箱、重试、恢复和快照事件
- UI（部分实现）：新增持久 Session Tree；交互入口只创建 Inline 或 Plain，Lanterna/TUI 配置兼容映射到 Inline。完整 RunSnapshot 投影、四区状态重构和 `tui/` 源码物理删除尚未完成
- Temporal 决策：当前本地版不引入；未来服务端出现跨机器 Worker、长时间审批、定时器和故障转移需求时，可实现 `TemporalWorkflowRuntime` 替换本地调度，禁止与 DurableTaskManager 叠加；Temporal history 只保存控制状态和 Artifact 引用
- 影响范围：agent、runtime、budget、security、tool、workspace、snapshot、trace、render、tui、cli、配置、测试和文档
- 未实现：RunBudget、PricingCatalog、Project Trust、RunStore 的 checkpoint/Side-Git 引用与完整恢复对账、统一可观测上下文、RunSnapshot 终端投影；内部 STANDARD 兼容实现尚未物理删除
- 验证计划：各阶段完成后运行对应限定测试；本轮按用户要求不执行全量回归，真实终端启动仍需单独许可
- 剩余风险：旧 `tasks.db` 当前只在启动时导入，不反向同步；剩余传统配置解析器仍需按模块迁移；ReAct 写入事务化、RunStore 完整恢复对账和 `tui/` 物理删除仍需分阶段实施

## 2026-08-11 上下文压缩预算治理优化

- 状态：已实现并通过限定回归
- 已实现：原文尾部从固定轮次近似策略收紧为严格 token 预算；user 边界超预算时继续前移，单条大消息无法切分时保留头尾并落盘可恢复引用；SessionMemory 支持前缀预摘要增量复用；默认每 5 次成功压缩执行一次摘要重建；压缩阈值扣除当前工具定义和输出预留
- 验证：`ConversationHistoryCompactorTest`、`ContextProfileTest`、`TokenBudgetTest` 共 47 项通过；Maven 全量回归通过
- 未验证：优化后的 256k 真实模型评测连续两次在探活阶段收到空 assistant response，测试按协议跳过；旧 93.3% 只保留为 2026-08-10 基线，不能声称已被本次实现重新验证
- 影响范围：ConversationHistoryCompactor、ContextProfile、TokenBudget、ReAct、Plan、SubAgent 的请求前压缩阈值
- 剩余风险：Provider 原生 tokenizer 与本地估算可能存在偏差；周期性重建无法恢复既没有摘要也没有落盘引用的历史内容

## 2026-08-20 九段式生命周期滚动摘要

- 状态：已实现并通过限定回归
- 已实现：保留九段摘要分类；新增主题、生命周期、重要性、版本、压缩次数、覆盖关系和证据引用；增量模型只输出受限变更操作，程序负责校验、覆盖、完成迁移和删除；格式损坏时保留上一版摘要；周期性全量重压缩改为生命周期 GC，稳定决策和未解决事项不按次数删除
- 验证：`RollingSummaryTest`、`SummaryLifecycleReducerTest`、`SummaryGarbageCollectorTest`、`CompactionSemanticGuardTest`、`ConversationHistoryCompactorTest` 定向测试通过
- 未验证：未运行全量测试，未启动项目，未执行真实模型长会话评测
- 影响范围：上下文压缩摘要模型、增量更新协议、周期治理、摘要文档
- 剩余风险：模型提出的主题键质量会影响同主题合并；结构化摘要受保护事实过多时允许暂时超过字符上限并告警

## 2026-08-09 四项简历实验重测

- 状态：四项均已进入真实模型执行；并发、压缩、长期记忆已形成有效报告，Saga 多智能体形成有效单侧结果，但同轮单 Agent 连续因模型链路未完整结束而无法形成有效配对
- 多智能体协作：`gpt-5.6-terra` 最新运行中，多智能体完整结束并通过 30/30；单 Agent 产物通过 28/30，但 LLM 链路未完整结束，因此该轮不能计算有效模式差值，历史 27/30 对 30/30 不再代表本轮结果
- 上下文压缩：使用 `gpt-5.6-terra` 和 256k 上下文窗口执行；每轮先用单条低于 microcompact 阈值的确定性对话消息把历史重新增长到 80% 阈值，再由正式 `Agent.run` 通过 `Agent.maybeCompactHistory` 自动压缩，连续完成 5 轮；30 条固定事实保留 28 条，自动问答保真率 93.3%；当前仍是自动 QA + 预声明关键词判定，未完成人工复核
- 长期记忆：对抗型评测执行 120 次写入决策、50 个跨会话检索场景、10 个过期场景和 25 轮噪声；写入准确率 100%、Recall@5 82.0%、上下文注入命中率 62.0%、召回到注入传递率 75.6%、过期过滤率 100%；同主题更新仅 1/10 召回成功，高相似干扰 10/10 召回但 0/10 通过最终注入验收
- Agent 并发与状态一致性：5 类任务 × 3 个纠偏时间点 × 3 次重复，共完成 45 个真实 Runtime API 案例；旧 turn 在新 turn 启动后的残留事件为 0，42/45 完整观察到纠偏标记，3 个案例未观察到新响应标记
- 影响范围：真实 LLM 评测入口、OpenAI-compatible 空工具调用响应兼容、上下文压缩和长期记忆评测报告
- 下一步：修复同主题更新召回和高相似记忆注入；补充压缩人工复核；Saga 需要取得单 Agent 与多智能体均完整结束的同轮配对结果

## 2026-08-09 CodeSearchNet RAG 评测重构

- 状态：评测代码与执行脚本已实现，等待用户在 VSCode 终端执行
- 来源：原 50 条样本将查询文档同时写入索引源码，候选池较小且纯语义基线 Recall@5 已为 100%，不能作为简历中的检索能力结论
- 影响范围：CodeSearchNet 数据适配器、RAG 集成评测、评测文档和临时 Node.js 执行入口
- 已实现：索引源码不再包含查询文档；缺少自然语言文档的样本不进入查询集；代码内容去重；默认构造 1,000 个候选和 200 条查询；使用固定随机种子并按仓库轮转抽取查询；报告记录候选规模、随机种子、样本 ID、仓库和泄漏保护状态
- 执行方式：在 VSCode 终端运行 `node Temp/run-rag-benchmark.mjs`，脚本按固定种子从 CodeSearchNet Java test split 分层下载样本，再调用限定 Maven 集成评测
- 未实现：尚未生成新 Recall、MRR 和 nDCG 结果；新结果出来前不得继续在简历中使用旧 Recall@5=100%
- 风险：远程 embedding、重排服务和网络状态会影响复跑；需要保留原始报告和失败查询，不能只记录聚合分数

## 2026-08-07 Agent 会话运行时与双通道输入

- 状态：已实现（真实终端交互仍待现场验证）
- 来源：参考 pi 的 Agent session facade 与 Steering / Follow-up 消息队列，将活动输入从 CLI 局部协调器下沉到 Agent 执行层
- 影响范围：AgentExecutionEngine、AgentTurnInbox、AgentSessionRuntime、CLI、无头 Runtime API、RunEvent 和会话测试
- 已实现：Agent 执行层支持 Steering / Follow-up 双通道注入；CLI 活动输入复用 Agent 收件箱；无头执行复用统一 AgentSessionRuntime；运行事件支持 queue.updated；收件箱容量、优先级、批量消费和事件编码已有限定测试；Runtime checkpoint 已保存压缩 metadata，并增加稳定消息 id、parentId、role 和 index 的消息树快照，旧 SQLite 数据库启动时自动补列
- 已修复：AgentSessionRuntime 的异步执行会复用调用方已有的 RunContext，并在工作线程结束后恢复原上下文；无外层上下文时才创建并关闭临时上下文
- 未实现：Runtime API 尚未提供 SSE 长连接推送；Extension Contract 尚未负责外部配置加载和扩展执行权限统一
- 验证建议：运行收件箱与 RunEvent 编码限定测试；已通过 `mvn -DskipTests package` 和 plain renderer 的 `/help`、`/exit` 启动烟测；交互式方向键、底部 dock、HITL 按键仍需在真实终端现场验证
- 风险：跨进程恢复不保存尚未交付的队列输入；Runtime API 和无头路径已使用会话运行时，CLI 已通过同步会话入口复用同一 RunContext

## 2026-08-07 Runtime API 显式会话队列

- 状态：已实现
- 来源：Runtime API 之前每个 turn 通过无头执行入口重新构造 Agent，无法从 API 控制正在运行的会话队列
- 影响范围：Runtime API 路由、RuntimeSessionTurnRunner、AgentSessionRuntime、RunEvent、Runtime API 测试和启动器
- 已实现：Runtime API 通过持久 `RuntimeSessionTurnRunner` 为每个 thread 复用 `AgentSessionRuntime`；新增 `POST /v1/threads/{id}/steer` 与 `POST /v1/threads/{id}/follow-up`；两个入口复用 AgentTurnInbox，返回队列水位并写入 `queue.updated` 事件；旧 TurnRunner lambda 保持兼容，不支持队列时返回 501
- 未实现：Runtime API 仍未提供 SSE 长连接推送；真实模型交互仍需现场验证
- 验证建议：运行 RuntimeApiServerTest；已覆盖 steer、follow-up、queue clear 和 cancel；再使用真实 API Key 启动 `serve --http` 验证 Steering 在工具批次后注入、Follow-up 在自然结束前注入
- 风险：队列快照现在按 thread/branch 持久化，进程退出瞬间正在交付的消息仍以 turn 终态对账；大规模队列仍需分页和上限治理

## 2026-08-07 RunEvent 会话状态与自定义消息

- 状态：已实现
- 来源：已有运行事件覆盖模型增量、工具和 turn 终态，但没有统一表达会话生命周期和扩展事件的协议
- 影响范围：RunEvent、Runtime JSON 编码、RuntimeSessionTurnRunner、事件测试和架构文档
- 已实现：新增 `session.state` 和 `message.custom` 强类型事件；Runtime 会话在 turn 开始/结束时发布 running/idle 状态；自定义消息支持稳定类型、正文和字符串属性，并统一经过 JSON codec
- 未实现：CLI Renderer 尚未对所有自定义消息提供专用视觉渲染；事件 schema 仍为版本 1，尚未提供远端能力协商
- 验证建议：运行 RunEventJsonCodecTest、RuntimeApiServerTest；真实 Runtime API 需要验证 SSE 中的状态事件顺序
- 风险：第三方扩展提交过大的自定义属性仍需经过结果尺寸治理，当前 codec 只负责结构化编码

## 2026-08-07 内部 Extension Contract

- 状态：部分实现
- 来源：Skill、Hook、MCP server 和 CLI command 分别维护名称、来源、启用状态和能力信息，发现与替换语义不一致
- 影响范围：新增 extension 契约与注册表、Skill/Hook/MCP/CLI 适配器、后续补全与状态展示
- 已实现：新增 `ExtensionContract` 和 `ExtensionRegistry`；统一四类扩展的 kind、稳定 id、name、version、source、enabled、capabilities、metadata；注册表提供去重注册、显式替换、按 kind/启用状态列举；现有 Skill、Hook、MCP server 和 command 均有适配器；Main 启动时把命令、Skill 和 MCP server 注册到统一目录，CLI 补全优先从该目录读取 Skill/MCP 发现信息，并保留旧 supplier 回退
- 未实现：MCP 配置文件被外部直接修改后的自动 reload 尚未实现；MCP enable/disable/restart 已通过观察者同步目录；扩展执行权限仍由原有 Skill allowedTools、Hook Policy、MCP trust policy 和命令解析链路分别治理
- 验证建议：运行 ExtensionRegistryTest、DevCliCompleterTest；`/skill reload` 会同步 Skill 和 Hook 目录，后续接入调用方时继续保持旧执行管线不变
- 风险：注册表目前是进程内目录，不负责加载、执行或持久化；过早把执行权限塞入通用契约会削弱现有安全边界

## 2026-08-07 Runtime 对话分支

- 状态：已实现
- 来源：Runtime thread 之前只有线性事件流，checkpoint 虽保存消息 parentId，但无法从历史位置创建独立后续对话
- 影响范围：Runtime SQLite schema、事件与 checkpoint 归属、上下文恢复、RuntimeSessionTurnRunner、Runtime API 和测试
- 已实现：新增 runtime branch 记录，包含 parent_branch_id、fork_event_id、name 和 active 状态；事件与 checkpoint 增加 branch_id；上下文按根到当前分支的 lineage 截取，fork 后主分支和子分支互不污染；提供分支创建、列举和激活接口；切换分支后关闭旧 AgentSessionRuntime，下一轮按目标分支重建；旧 SQLite 表启动时自动补列并把既有数据归入 main
- 未实现：尚未实现分支重命名、删除和图形化展示；未对正在运行的 turn 允许强制切换分支
- 验证建议：运行 RuntimeThreadStoreTest、RuntimeApiServerTest、CliCommandParserTest、DevCliCompleterTest、SessionTreeServiceTest；真实模型下验证从 fork 前约束继续两条不同任务
- 风险：分支切换不会复制父分支的待处理队列，避免把旧分支输入带入新分支；大规模分支树和队列仍需分页接口

## 2026-08-07 CLI 对话分支

- 状态：已由 2026-08-14 持久 Session Tree 实现取代
- 来源：Runtime API 已支持持久分支，CLI 需要提供一致的最小操作入口
- 影响范围：CLI 命令解析、JLine 补全、SessionTreeService、RuntimeThreadStore、Agent 历史切换和 CLI 测试
- 已实现：`/session status|tree|fork|use|new|clear|use-thread` 操作 `runtime.db` 中的持久会话树；`/branch` 保留兼容别名并只提示一次迁移；切换分支时重建模型上下文并清空待处理 Steering / Follow-up 队列
- 未实现：分支重命名、删除、活动 turn 强制切换和图形化分支树
- 验证建议：运行 CLI 解析、补全、RuntimeThreadStore 和 SessionTreeService 限定测试；真实终端验证 Tab 补全、跨进程恢复、切换后上下文隔离和活动 turn 边界
- 风险：会话树只恢复模型上下文，不切换或回滚工作区文件；大规模会话树仍需分页和保留期限治理

## 2026-08-07 移除旧 CLI 活动队列

- 状态：已实现
- 来源：活动输入已经下沉到 AgentTurnInbox，但旧 ActiveTurnCoordinator / PromptQueue 仍作为无生产调用的第二套队列模型存在
- 影响范围：CLI 活动输入、旧队列类及其测试、编译回归
- 已实现：删除旧 CLI 队列实现和对应测试，Main 只保留 AgentTurnInbox；Steering / Follow-up 的容量、优先级和取消语义统一由 Agent 执行层维护
- 未实现：无
- 验证建议：运行 AgentTurnInboxTest、RuntimeApiServerTest、DevCliCompleterTest，并进行真实终端交互验证
- 风险：外部代码如果直接依赖旧的 com.devcli.cli.turn 队列类会在编译期失败；这些类此前没有生产调用，属于内部实现

## 2026-08-07 统一模型能力注册表

- 状态：已实现
- 来源：多个 Provider 客户端分别维护上下文窗口、缓存模式和能力常量，新增 OpenAI-compatible Provider 时容易出现策略分叉
- 影响范围：LLM 客户端能力接口、Provider 工厂、ContextProfile、模型能力测试和文档
- 已实现：新增 `ModelCapabilityRegistry`，统一解析 Provider 别名、上下文窗口、输出上限、prompt cache、工具调用、视觉和 reasoning 能力；内置 Provider 使用注册表默认值，允许按模型模式注册进程内覆盖规则；未知 Provider 使用安全通用默认值；`LlmClientFactory` 复用同一 Provider 规范化逻辑
- 未实现：能力注册表尚未从外部配置文件动态加载；Provider 真实能力仍需按官方模型版本定期校准
- 验证建议：运行模型能力、ContextProfile 和 LlmClientFactory 限定测试；不依赖真实 API Key
- 风险：模型厂商升级规格后，内置窗口和能力声明可能滞后；自定义覆盖是进程内状态，不跨进程持久化

## 2026-08-03 Agent 面经能力筛选与记忆链路优化

- 状态：已实现
- 来源：对照 Agent 开发面经复查长会话摘要、长期记忆注入、RAG 可观测性和原始会话审计能力
- 影响范围：会话预摘要缓存、长期记忆检索与意图分类、RAG 检索审计、CLI 会话归档、配置模板、记忆与检索测试、README、AGENTS 和详细行为文档
- 已实现：会话预摘要使用旧摘要和新增消息增量维护，并记录输入、覆盖、摘要长度及成功失败指标；长期记忆统一识别保存、删除、忽略、目录查看和历史依赖意图，检索保留语义、关键词和合并分数，按最低分数、第一名分差和最大数量限制注入；RAG 审计记录 keyword / semantic / graph、RRF、rerank、最终结果和降级状态，不保存代码正文；普通 CLI 会话归档默认关闭，启用后 ReAct 保存脱敏模型消息，Plan / Team 保存顶层输入输出，按期限清理，`/history clear` 支持删除归档
- 未实现：独立低成本摘要模型；普通 CLI 归档的跨文件会话重建命令
- 验证建议：运行记忆、RAG、会话归档、Trace 和工具 Provider 的限定测试
- 风险：不同 embedding 模型的分数分布可能需要调整默认阈值；增量摘要仍依赖模型输出完整替代摘要；启用 CLI 会话归档后会保存本机上下文，虽然执行脱敏和期限清理，仍需由用户承担本机文件访问控制

## 2026-07-17 非 Git 写时复制工作区后端

- 状态：已实现
- 来源：非 Git 隔离工作区此前始终完整复制，目录较大或并行 Worker 较多时产生重复磁盘占用和启动延迟
- 影响范围：WorkspaceBackend 选择、非 Git 工作区物化、复制回退、配置模板、README、AGENTS、详细架构文档和工作区测试
- 已实现：新增文件系统级写时复制后端；Linux 只接受 GNU `cp --reflink=always` 的强制 reflink 结果，Windows 11 24H2 / Windows Server 2025 及以上版本只在 ReFS 上启用系统块克隆路径；`auto` 模式对 Git 项目继续使用 worktree，对非 Git 项目优先使用写时复制；平台或文件系统不支持、命令失败、克隆遗漏文件或内容哈希不一致时，会先清理部分结果再回退有界复制；排除目录与符号链接安全边界保持不变；明确拒绝硬链接写前断链方案，避免直接文件写入或外部命令污染源目录
- 已验证：测试编译通过；限定测试覆盖写时复制策略成功、克隆结果内容校验、部分结果清理与复制回退、工厂自动选择、PatchSet 构建应用和并发提交；当前 C 盘为 NTFS，已验证自动回退复制后工作区写入不影响源目录
- 未验证：未在 Btrfs、XFS 或 ReFS 环境现场确认物理块共享；未运行全量测试，未启动项目
- 风险：NTFS、旧版 Windows、macOS 当前实现和不支持 reflink 的 Linux 文件系统仍会使用完整复制；Windows ReFS 路径依赖操作系统原生复制操作的块克隆语义

## 2026-07-17 RAG 强类型证据通道

- 状态：已实现
- 来源：`search_code` 的结构化证据仍嵌入展示文本，结果裁剪、格式调整和缓存治理存在破坏证据边界的风险
- 影响范围：工具结构化结果、工具结果缓存与尺寸治理、RAG Provider、WorkingMemory、ReAct、Plan、Multi-Agent 和相关文档与测试
- 已实现：新增工具结果强类型旁路接口和 RAG 证据载荷类型；`search_code` 的新结果只在旁路通道传递 evidence 与 negativeFact，不再向展示文本附加 `RAG_EVIDENCE_JSON`；工具尺寸治理、只读结果缓存和批量执行结果完整保留旁路载荷；ReAct、Plan、Worker 与 Reviewer 都把旁路载荷传入 WorkingMemory；旧 JSON 载荷和旧展示文本解析仅保留历史 checkpoint 与旧 Provider 兼容
- 已验证：编译通过；限定测试覆盖展示文本变化不影响证据写入、typed negativeFact 清理旧 symbolVersion、尺寸治理与缓存后载荷不丢失，以及新 `search_code` 文本不再包含旧 JSON 标记
- 未验证：未运行全量测试，未启动项目，未验证旧 checkpoint 的真实跨版本恢复
- 风险：`ToolSideChannel` 当前只在进程内传递，Runtime 事件和 checkpoint 不持久化完整旁路对象；历史恢复仍依赖旧文本兼容解析

## 2026-07-14 Multi-Agent 计划协议与空结果可靠性

- 状态：代码与针对性测试已实现，完整 5 任务已复跑；交付可靠性仍未达标
- 来源：Agent 受控评测中单 Agent 成功率 20%，Planner/Worker/Reviewer 成功率 0%；主要失败来自 Planner 非 JSON 输出、空工作区纯检查步骤阻塞实现，以及 Worker 最终文本为空。首次修复后真实复跑确认 Planner 已生成直接实现步骤，但 Worker 连续两次只描述准备写入、没有调用工具，仍被空结果阻断
- 影响范围：Multi-Agent 编排、Planner 与 Worker 协议守卫、SubAgent 单次执行证据、角色提示词、受控 Agent benchmark、配置模板、README、AGENTS、详细架构文档和 Agent 测试
- 已实现：Planner 支持从前后说明中提取完整 JSON；解析失败、DAG 无效或阻塞性空工作区纯检查步骤触发有界协议修复；修复请求携带原始任务、失败原因、无效输出预览和固定 schema；空工作区检查必须并入实现步骤；Worker 空文本但存在结构化成功工具证据时合成有界摘要进入 Reviewer；没有成功证据时追加一次强制执行协议，并按步骤类型指定首轮工具，FILE_WRITE / INTEGRATION 选择 `write_file`，COMMAND 选择 `execute_command`，其他类型选择 `list_dir`；Anthropic 与 OpenAI-compatible 均映射为命名工具选择；FILE_WRITE / INTEGRATION 步骤在成功 `write_file` 批次后直接以结构化证据结束 Worker，强制修复中的指定工具采用同一规则，不再发起收尾 LLM 请求，失败时才恢复 AUTO 纠正；Provider 忽略命名工具选择时追加一次严格 JSON 工具信封请求，只接受目标工具、对象参数和无尾随内容的完整 JSON，并继续走原工具安全管线；SubAgent 错误保留标准错误码和 retryable 标记；Pre-Review 区分跳过与硬检查实际通过，Reviewer 可重试故障或达到默认 2 轮上限时只有后者允许普通步骤降级接受；受控 benchmark 不暴露 execute_command，并把 Pre-Review 编译交给运行后的隐藏验证器，避免 Docker daemon 状态污染模型指标，生产沙箱策略不变
- 已验证：覆盖说明文本包裹 JSON、非 JSON 修复、合法 JSON 中阻塞性检查步骤修复、成功工具证据放行、失败工具证据可见、空结果强制工具修复、修复后仍无证据保持失败、文件写入步骤成功批次后结束、命名工具成功后单轮结束、失败后继续纠正、步骤类型映射、严格工具信封解析与拒绝规则、两类 Provider 请求体映射、Reviewer 轮数边界以及 Planner / Worker 提示词约束；2026-07-16 完整复跑中，单 Agent 0/5、隐藏检查平均完成率 0%；Planner/Worker/Reviewer 0/5、隐藏检查平均完成率 27.33%，其中 logops 9/10、ordermvc 7/15
- 已补强（2026-07-16）：OpenAI 兼容流式工具调用同时支持标准增量、累积快照和完整字段重复发送，避免工具名与 JSON 参数重复拼接；Krill AI `gpt-5.5` 完整 5 任务复跑中，单 Agent 成功 3/5、隐藏检查平均完成率 94%，Planner/Worker/Reviewer 成功 1/5、平均完成率 76%
- 已实现（2026-07-16）：新增订单履约 Saga 协作评测场景，预置只读公共契约，将库存、支付、配送、通知、审计拆为五个独立模块，并把履约编排设为最终集成步骤；单 Agent 与 Planner/Worker/Reviewer 使用同一任务说明、工具边界和隐藏验证器；测试工具白名单在隔离 ToolRegistry fork 中保持，避免 Worker 重新获得 `execute_command`
- 实验目的：验证 Multi-Agent 是否只在具备明确模块边界、可并行子任务和最终集成依赖的任务上取得收益，避免继续使用单文件 CLI 任务得出不适用的结论
- 实验变量：自变量只有执行模式，分别为单 Agent 与 Planner/Worker/Reviewer；控制变量包括同一 `gpt-5.5` 模型、同一 Provider、同一只读契约、同一初始空工作区、同一任务说明、同一工具白名单、同一 JDK 编译器、同一隐藏检查和相互独立的运行目录
- 实验流程：每种模式复制相同的 `SagaContracts.java` 到独立工作区；单 Agent 首轮强制读取契约后完成全部模块；Multi-Agent 由 Planner 生成 DAG，库存、支付、配送、通知、审计五个实现步骤可并行，履约编排依赖前五步，Reviewer 在最终集成前逐项审查；模型结束后再由模型不可见的外部验证器编译源码并通过隔离类加载器执行行为检查
- 工具控制：两种模式都只暴露 `read_file`、`write_file`、`list_dir`，不暴露 `execute_command`；工具限制必须随 Multi-Agent 隔离工作区 fork 继承，避免 Docker、本机命令和环境状态污染模型能力指标
- 验收设计：隐藏检查共 30 项，其中架构约束 3 项、库存 4 项、支付 4 项、配送 4 项、通知 3 项、审计 2 项、正常履约 4 项、失败补偿 3 项、幂等 2 项、并发 1 项
- 架构验收：公共契约 SHA-256 必须保持不变；六个指定实现类必须位于允许包目录并实现对应接口；五个服务必须同时提供 public 无参构造器和 FailureSwitch 构造器，履约编排必须提供五服务依赖构造器
- 模块验收：库存检查可用库存预留、库存不足拒绝、重复预留幂等和释放；支付检查授权引用、授权状态、重复授权幂等和退款；配送检查配送引用、活动状态、重复创建幂等和取消；通知检查成功与失败通知各最多一次及故障注入；审计检查事件顺序、订单隔离和故障注入
- 流程验收：正常路径必须执行库存预留、支付授权、创建配送、成功通知，并保留对应资源状态；审计顺序必须包含 NEW、INVENTORY_RESERVED、PAYMENT_AUTHORIZED、SHIPMENT_CREATED、COMPLETED
- 补偿验收：支付授权失败必须释放库存；配送创建失败必须退款并释放库存；成功通知失败必须按配送取消、支付退款、库存释放的逆序补偿；失败路径只能发送一次失败通知并返回 FAILED
- 幂等与并发验收：相同请求重复执行不能重复产生副作用；相同幂等键必须复用首次结果；同一请求并发执行时所有调用返回 COMPLETED，库存、支付、配送和成功通知各只产生一次有效副作用
- 计分规则：完成率为隐藏检查通过数除以 30；只有 LLM 调用完成且 30/30 才记为任务成功；同时记录端到端耗时。契约变更、工具白名单失效、使用被禁止工具或外部环境进入评分链路时，该轮结果作废
- 已验证（2026-07-16）：Krill AI `gpt-5.5` 单次有效运行中，单 Agent 通过 27/30（90.0%，192.8 秒），Planner/Worker/Reviewer 通过 30/30（100.0%，725.1 秒），正确率提升 10 个百分点，耗时为 3.76 倍；单 Agent 未通过退款后活动授权清理、取消后活动配送清理和幂等键冲突处理；首次运行因隔离 fork 丢失白名单而允许 Worker 看到 `execute_command`，该轮作废且不纳入统计
- 未完成：Saga 结果目前只有 1 次有效运行，尚不具备统计稳定性；现有 CLI 任务中 Multi-Agent 在 incidentops 仅通过 2/10，角色链路仍会放大语义偏差，Reviewer 平均耗时过长。Krill AI 端点仍会重复发送完整 content，并对部分公开长上下文样本触发安全拦截；需要完成 content 快照兼容后重新运行 LongBench/RULER
- 风险：阻塞性检查识别当前采用窄范围语义规则，覆盖空工作区、项目结构、目录和文件存在性；复杂自然语言计划仍依赖修复请求中的模型服从性。强制执行协议只有 1 次，避免无限消耗；最终结果仍必须经过 Pre-Review 与 Reviewer，只有硬检查实际通过且 Reviewer 属于可重试故障时允许安全降级

## 2026-07-13 运行时可靠性与记忆治理补强

- 状态：已实现
- 来源：架构复查发现多模型错误与重试语义不统一、压缩摘要缺少提交前语义校验、长期记忆缺少统一版本和过期机制、错误记忆没有自动矛盾检测、重复工具调用只比较原始参数且没有结果缓存
- 影响范围：`llm/`、`memory/`、`tool/`、AgentBudget、SQLite 记忆表、配置模板、README、AGENTS 和详细架构文档
- 已实现：统一 `LlmException/LlmErrorCode`，只重试限流、过载、超时、网络和 5xx；流式输出后禁止重试；压缩摘要写回前运行语义守卫并恢复缺失关键约束；长期记忆增加 schemaVersion、revision、expiresAt 和按类型 TTL；同主题或键值声明冲突自动标记并 supersede；工具调用使用规范化语义指纹，READ_ONLY 成功结果按 TTL 缓存，副作用执行和项目切换清空缓存
- 已增强（2026-07-16）：压缩语义守卫增加结构化声明对账，同一配置或自然语言声明只保留最新值，避免压缩时同时恢复已失效旧值；否定约束必须在包含同一语义锚点的摘要分段中保留否定极性，不能由无关的“不要”句子误判通过；长期记忆复用统一声明解析器，支持配置赋值、默认值、当前值、设置值和禁止使用等可确定表达，相同主题同值的改写自动去重，不同值或正反声明自动建立冲突并 supersede；工具指纹增加 Unicode NFKC 归一化，同时取消对正则 `pattern` 的大小写折叠，避免把大小写敏感查询错误命中缓存
- 验证结果：错误重试、压缩语义守卫、记忆生命周期与持久化、自动矛盾检测、语义停滞、缓存命中与失效测试通过；新增最新值覆盖、无关否定拒绝、自然语言改写去重、正反声明冲突、全角查询等价和正则大小写隔离测试；兼容长期记忆 supersede、MemoryManager 和压缩器既有测试
- 风险：声明解析只处理可以确定抽取主题和值的表达，不使用 embedding 直接决定冲突或缓存命中，避免相似但不等价内容产生错误覆盖；复杂隐含矛盾仍需受控 NLI 判定。语义守卫保护关键约束和结构化声明，不替代完整事实问答评测

## 2026-07-13 CLI 演示链路补强

- 状态：已实现
- 来源：真实启动测试发现 `/help` 与文档不一致、日志目录使用跨平台无效示例、Windows 无法直接执行 npm 无扩展脚本、重定向中文输入乱码，以及 HITL 与主提示符竞争读取标准输入
- 影响范围：CLI 命令解析与帮助、终端编码与能力探测、Renderer 输入所有权、MCP stdio transport、配置模板、README、AGENTS 和详细架构文档
- 已实现：`/help` 进入正式命令类型并复用统一命令清单；重定向输入默认 UTF-8，并保留旧式编码覆盖；日志目录模板改为用户主目录相对写法；Windows 按 PATH/PATHEXT 选择 `.cmd` / `.bat` 包装器；plain 与 inline HITL 后续输入复用主 LineReader；增加 ANSI 强制覆盖用于能力误判终端
- 验证结果：针对性单元测试、测试编译、ConPTY 交互测试通过；覆盖 `/help` 补全、方向键历史、底部状态栏、HITL 拒绝、中文输入、Chrome DevTools MCP 29 个工具就绪及真实页面标题读取
- 风险：`DEVCLI_TERMINAL_FORCE_ANSI` 只应用于确认支持 ANSI 的终端；对真正不支持光标控制的终端强制开启会产生转义序列

## 2026-07-13 架构复查剩余问题

- 状态：高优先级项已实现（2026-07-13），中低优先级项待处理
- 来源：副作用隔离与恢复事务完成后的二次架构复查
- 影响范围：命令执行、MCP 信任边界、跨进程提交、工作区物化、PatchSet 内存模型、checkpoint 日志、Runtime 串行器和大型入口类
- 已实现：隔离命令和 Pre-Review 强制通过受限 Docker 执行，禁止主机回退；项目提交增加跨进程 `FileLock`；MCP readOnly 注解默认不可信，并支持本地只读允许列表与拒绝列表；PatchSet 改为逐文件流式哈希，只读取变更文件内容
- 已补强：孤儿 `.patch-journal` 已增加 TTL 清理，恢复所需日志不会误删；备份使用 POSIX `600/700` 或 Windows 所有者专用 ACL；`KeyedSerialExecutor` 遇到 JVM `Error` 会终止同 key 通道并拒绝排队任务；项目锁缓存按使用者计数退役；复制等待和线程终止均增加上限
- 已补强：工作区后端默认自动选择 Git worktree 或文件系统级写时复制；worktree 会叠加当前脏文件、删除文件、未跟踪及被忽略文件；非 Git 写时复制不可用时自动回退有界复制；两类后端都会清理排除目录、符号链接和过期元数据
- 已补强：内置 Provider 支持直接返回结构化 `ToolOutput`，参数错误、策略拒绝、执行失败、超时、取消和非零命令退出不再依赖文本解析
- 待实现：继续拆分 CLI、Multi-Agent、Plan 和 ToolRegistry
- 优先级：高优先级四项、Git worktree 和文件系统级写时复制后端已完成；大型类拆分为中；其余体验和评测能力为低
- 验证结果：已覆盖 Docker 路由与参数、Pre-Review 沙箱要求、真实子 JVM 跨进程锁、64MB 文件低堆 PatchSet 构建、MCP 伪造注解与本地策略
- 风险：Docker daemon 本身属于主机高权限基础设施；跨进程文件锁在部分网络文件系统上的语义可能较弱；变更文件内容仍需载入内存；checkpoint 备份虽然已限制所有者访问，但内容仍是可恢复所需的原文件明文

## 2026-07-13 副作用隔离与补丁恢复事务补强

- 状态：已实现
- 来源：架构复查发现任务标签无法约束真实工具副作用、并行 PatchSet 可同时通过哈希预检、PatchSet 与 checkpoint 之间存在崩溃窗口、Runtime 同会话通道退役存在竞态，隔离工作区和预审编译还存在生命周期边界
- 影响范围：ToolRegistry 与执行管线、Plan、Multi-Agent、Runtime API、checkpoint、workspace、Pre-Review、Skill fork、README、AGENTS、详细架构文档和配置模板
- 已实现：新增 `ToolEffect` 与 `ToolAccessScope`，非隔离任务强制只读，隔离任务禁止外部副作用；MCP 缺失安全注解时保守拒绝；工具定义、`search_tools` 缓存和并行工具线程保持同一能力范围，并行线程继承资源租约归属；项目级 ToolRegistry fork 复制 SkillContextBuffer；新增项目级公平提交锁；checkpoint 协议升级为版本 3，PatchSet 应用前保存 before/after 哈希和原文件备份，resume 在同一项目锁内完成提升、继续或回滚，对账保存失败和回滚不完整时停止；未来 checkpoint 版本明确报告不兼容；PatchSet 回滚失败返回具体路径；Runtime keyed 串行器原子管理通道生命周期，调度拒绝通知等待者，单任务异常不阻塞后续 turn；隔离工作区新增后端接口、有界并行复制、TTL 孤儿清理和跨进程活动租约；无 Maven 的 Java 预审改用 javac 参数文件；从 Plan 和 Multi-Agent 大类抽离工作区执行与补丁提交协调职责
- 未实现：Docker 命令隔离不等同于独立 VM 或操作系统级沙箱；不支持原生 reflink / ReFS 块克隆的平台仍会回退完整复制
- 验证建议：运行 `ToolCapabilityTest`、`ToolRegistryForkTest`、`ToolExecutionPipelineTest`、`PlanExecuteAgentTest`、`AgentOrchestratorTest`、`AgentCheckpointTest`、`WorkspaceExecutionSessionTest`、`PatchSetTest`、`IsolatedWorkspaceTest`、`KeyedSerialExecutorTest`、`RuntimeApiServerTest`、`PreReviewVerifierTest`，并执行 `mvn -q -DskipTests test-compile`
- 风险：Docker daemon 本身仍是主机高权限组件；跨进程 FileLock 在网络文件系统上的可靠性取决于底层实现；Git worktree 仍会物化工作文件；非 Git 写时复制依赖底层文件系统能力，不支持时仍有完整复制成本

## 2026-07-12 Agent Runtime 架构统一改造

- 状态：已实现（架构主线）
- 来源：三条 Agent 执行路径、后台任务和 Runtime API 缺少统一运行上下文，工具结果、任务图、工作区隔离与会话并发仍存在分裂模型
- 影响范围：Runtime、Agent、Tool、Plan、Multi-Agent、Runtime API、后台任务、验证器、工作区隔离及相关文档与测试
- 已实现：新增运行级 `RunContext`，隔离项目路径、取消令牌和资源生命周期；取消状态不再使用进程级全局回退，线程中断可直接触发取消；后台任务为每个任务绑定独立运行上下文并在取消时同步取消令牌；无头 Agent 统一通过生命周期入口创建和关闭工具注册与记忆资源；工具大结果落盘路径改为使用所属工具注册实例的项目路径，消除跨项目静态串扰；Agent 明确区分自有与外部工具注册资源；工具结果新增状态、错误码、重试语义、图片和修改资源字段；ReAct、Plan、SubAgent 的错误熔断改用结构化错误码；工具执行统一进入分阶段中间件管线；HITL 从覆写执行入口改为管线中间件，拒绝和跳过返回结构化结果；新增统一 `AgentExecutionEngine`，ReAct、Plan task、SubAgent 共用预算检查、取消检查、LLM 调用、工具消息协议、工具结果回灌和异常出口，三条路径不再各自维护循环；新增共享 `ExecutionGraph`，Plan 与 Multi-Agent 共用依赖就绪判断、最终集成调度、缺失依赖和环检测；Runtime API 使用有界 keyed 串行执行器，同一 thread 的 turn 按提交顺序串行，不同 thread 仍可并行；新增共享 `ExecutionArtifact`，Plan `Task`、Multi-Agent `ExecutionStep` 和 checkpoint 统一使用状态、输出、摘要、修改资源、错误、尝试次数与时间戳；checkpoint 协议升级为版本 2，通过 `RecoveryState` 统一恢复，新版本保存共享 artifact，旧 completed/failed map 可迁移；新增 `IsolatedWorkspace`、`WorkspaceExecutionSession` 和 `PatchSet`，Plan 的 FILE_WRITE/COMMAND/VERIFICATION 与 Multi-Agent 副作用步骤在隔离目录执行，Reviewer 读取同一隔离产物，批准后才以哈希前置校验一次性应用主工作区，冲突、拒绝、失败或取消均不应用；PatchSet 拒绝非普通文件覆盖、路径逃逸和链接逃逸，并在应用失败时回滚；Pre-Review 编译、超时、输出解码和失败摘要已从编排器拆分到独立验证器
- 未实现：交互入口和编排器仍可继续按职责拆分，但本期已完成执行引擎、任务图、预审验证器、工作区生命周期和内置 Provider 结构化错误边界
- 验证建议：运行 `CancellationContextTest`、`RunContextTest`、`HeadlessAgentRunnerTest`、`DurableTaskManagerTest`、`AgentLifecycleTest`、`ToolRegistryProjectIsolationTest`、`ToolOutputTest`、`ToolExecutionPipelineTest`、`ToolRegistryStructuredResultTest`、`HitlToolRegistryTest`、`AgentBudgetTest`、`AgentExecutionEngineTest`、`PlanExecuteAgentTest`、`SubAgentTest`、`ExecutionGraphTest`、`ExecutionArtifactTest`、`ExecutionPlanTest`、`AgentCheckpointTest`、`AgentOrchestratorTest`、`PreReviewVerifierTest`、`IsolatedWorkspaceTest`、`PatchSetTest`、`ToolRegistryForkTest`、`KeyedSerialExecutorTest`、`RuntimeApiServerTest`
- 风险：隔离工作区是进程内文件系统隔离，不等同于容器或 VM 安全沙箱；命令仍可访问操作系统允许的外部资源，PatchSet 只约束回写主项目的文件变更；旧 Provider 返回的部分失败文本仍可能被视为成功状态；符号链接安全测试依赖运行环境是否允许创建链接

## 2026-07-09 长期记忆低价值显式保存确认

- 状态：已实现
- 来源：长期记忆写入策略需要避免显式但明显临时、低复用的信息直接进入持久层，减少记忆库长期噪声
- 影响范围：`src/main/java/com/devcli/memory/LongTermMemoryPolicy.java`、`src/test/java/com/devcli/memory/LongTermMemoryPolicyTest.java`、`README.md`、`AGENTS.md`、`docs/agents-reference.md`
- 已实现：显式保存请求命中临时信息或低复用第三方事实时返回 `CONFIRM`，reason_code 为 `EXPLICIT_LOW_VALUE_REQUIRES_CONFIRMATION`；稳定偏好、稳定项目事实和稳定个人属性仍按原策略保存
- 未实现：未引入 LLM judge 或人工确认交互 UI；当前仍由 `MemoryManager.storeFactWithPolicy` 返回确认提示
- 验证建议：`mvn -Dtest=LongTermMemoryPolicyTest -DskipTests=false test`
- 风险：用户确实想保存低复用事实时，需要上层确认流程继续承接；当前策略优先降低长期记忆噪声

## 2026-07-09 Multi-Agent 资源租约释放补强

- 状态：已实现
- 来源：资源租约边界检查发现 Plan Worker 只绑定 `runWithResourceLease` 上下文，步骤尝试结束后没有显式释放 step 租约；异常、Reviewer 打回或在位重做路径可能依赖超时抢占回收
- 影响范围：`src/main/java/com/devcli/agent/AgentOrchestrator.java`、`src/test/java/com/devcli/agent/AgentOrchestratorTest.java`、`README.md`、`AGENTS.md`、`docs/agents-reference.md`、`docs/runtime-resource-lease-design.md`
- 已实现：`AgentOrchestrator.executeWorkerOnce` 在 finally 中调用 `releaseResourceLeases(stepId)`；新增测试覆盖 Worker 尝试结束后释放资源租约
- 后续实现（2026-07-13）：ToolRegistry 托管共享 `ResourceLeaseMaintenance`，project fork 复用单个后台线程；默认每 60 秒清理过期租约，最后一个注册关闭后可靠终止，周期支持系统属性和环境变量配置
- 验证建议：`mvn -Dtest=AgentOrchestratorTest#shouldReleaseWorkerResourceLeaseAfterStepCompletes,ResourceLeaseMaintenanceTest,ResourceLeaseManagerTest,ToolRegistryForkTest -DskipTests=false test`
- 风险：并发 Worker 如果仍在同一毫秒级窗口写同一文件，冲突策略仍是拒绝后交给现有重试/审查流程处理，不做自动合并

## 2026-07-09 Side-Git 快照自动裁剪

- 状态：已实现
- 来源：长会话下 turn 级快照持续增长，`devcli.snapshot.max` 之前只限制展示/查询数量，没有真正裁剪 side-history
- 影响范围：`src/main/java/com/devcli/snapshot/SideGitManager.java`、`src/test/java/com/devcli/snapshot/SideGitManagerTest.java`、`README.md`、`AGENTS.md`、`docs/agents-reference.md`
- 已实现：每次新建快照后按 `SnapshotConfig.maxSnapshots` 重写 Side-Git 历史，只保留最新 N 条快照；新增测试覆盖超过保留上限时旧快照被裁剪
- 后续实现（2026-07-13）：裁剪计数持久化后按阈值或最小间隔触发有界回收；关闭 JGit 仓库句柄后扫描 refs 可达集合，只删除不可达松散对象，不在每次快照时执行完整 GC；超时或删除失败保留累计计数等待重试
- 验证建议：`mvn -Dtest=SideGitManagerTest,SnapshotGcPolicyTest -DskipTests=false test`
- 风险：当前只回收不可达松散对象，不重打包可达对象和既有 pack；单次回收受时间上限约束，大仓库可能需要多次触发完成

## 2026-07-09 独立 grep_code 精确检索工具

- 状态：已实现
- 来源：RAG / keyword / grep 检索边界讨论后，明确保留 `search_code` 的 SQLite keyword 通道和 RRF 融合，不把 grep 塞入 RAG 内部路由
- 影响范围：`src/main/java/com/devcli/tool/provider/GrepToolProvider.java`、`ToolRegistry` 工具注册、Agent / Plan / Reviewer 工具展示与提示词、README / AGENTS / agents-reference、工具与渲染测试
- 已实现：新增只读 `grep_code` 工具，按当前项目根实时扫描文件；支持 `pattern`、`path`、`regex`、`case_sensitive`、`limit`；通过 `PathGuard` 限制路径；跳过常见缓存/构建目录和大文件；Reviewer 可使用该工具做精确文本验证
- 未实现：无；`search_code` 内部检索链路、RRF 权重和 symbol-aware boost 未改动
- 验证建议：运行 `ToolRegistryTest`、`PlainRendererTest`、`ToolCallRendererTest`、涉及 Reviewer 工具可见性的 `AgentOrchestratorTest`
- 风险：`grep_code` 实时扫描大仓库时受文件数量影响，输出仍需依赖尺寸治理限制上下文体积

## 2026-07-02 ToolRegistry Provider 拆分第二阶段

- 状态：已实现
- 来源：第一阶段已拆分 File / Shell / Project / Memory / Snapshot Provider，剩余高耦合工具需要单独阶段处理，避免把可审查改动扩大成难定位的大重构
- 影响范围：`src/main/java/com/devcli/tool/ToolRegistry.java`、`src/main/java/com/devcli/tool/provider/`、RAG / Web / Browser / Skill / ToolSearch 相关测试
- 已实现：`ToolSearchProvider` 已迁移 `search_tools` 注册、搜索、缓存复用和 MCP 工具激活逻辑；`WebToolProvider` 已迁移 `web_search` / `web_fetch` 注册、搜索 provider 懒加载、HTTP 抓取、正文抽取和网络策略检查；`BrowserToolProvider` 已迁移 `browser_connect` / `browser_disconnect` / `browser_status` 注册和连接器调用逻辑；`RagToolProvider` 已迁移 `search_code` 注册、按项目路径复用 `CodeRetriever`、索引为空提示、semantic 降级提示、negativeFact 和强类型证据旁路载荷；`SkillToolProvider` 已迁移 `load_skill` 注册、`SkillRegistry` 查询、`SkillContextBuffer` 写入、usage 记录、allowedTools 和 context inline/fork 语义；`ToolRegistry` 保留工具目录版本、MCP 动态注册链路和预激活入口
- 未实现：无；MCP 动态工具注册链路继续保留在 `ToolRegistry`
- 约束：继续保留 `ToolRegistry` 作为统一执行入口、审计入口、参数校验入口和状态协调入口；不削弱路径安全、网络策略、浏览器安全策略、RAG 缓存、Skill allowedTools 和 MCP 动态工具可见性控制
- 建议验证：按拆分对象分别运行 `ToolRegistryTest`、Web / Browser / RAG / Skill 相关针对性测试；不默认运行项目或全量测试
- 风险：RAG / Web / Browser / Skill 与上下文、缓存、策略和 MCP 工具可见性耦合更深，Provider 上下文接口可能膨胀，需要逐个拆分并审计边界

## 2026-06-22 RAG 索引批量 embedding 降级

- 状态：已实现
- 来源：参考 `worenbudaoni/rag-study-helper` 的文档入库流程，选择迁移批量 embedding 与失败逐条降级策略，不引入其 Spring Boot / LangChain4j 技术栈
- 影响范围：`src/main/java/com/devcli/rag/EmbeddingClient.java`、`src/main/java/com/devcli/rag/CodeIndex.java`、`src/test/java/com/devcli/rag/CodeIndexTest.java`、`README.md`、`AGENTS.md`、`docs/agents-reference.md`
- 已实现：`EmbeddingClient` 增加 `embedAll`；OpenAI / Zhipu 兼容接口使用 `input` 数组批量请求，Ollama 保持逐条兼容；`CodeIndex` 按文件批量生成 chunk embedding，批量失败或返回数量异常时逐条降级，保留成功 chunk 并跳过单个失败 chunk
- 验证建议：`mvn -q -DskipTests=false "-Dtest=CodeIndexTest,EmbeddingClientTest" test`
- 风险：Ollama 仍没有真正批量请求能力；远程 OpenAI-compatible provider 若不支持 `input` 数组，会触发逐条降级，功能正确但性能收益降低

## 2026-06-22 Multi-Agent / RAG / Memory 上下文可信度修复

- 状态：已实现
- 来源：用户指出 `stepModifiedFiles` 未进入后续步骤上下文、RAG 证据解析依赖展示文本、长期记忆英文策略和跨层去重存在缺口
- 影响范围：`src/main/java/com/devcli/agent/`、`src/main/java/com/devcli/rag/`、`src/main/java/com/devcli/memory/`、`src/main/java/com/devcli/tool/`、`AGENTS.md`、`README.md`、`docs/agents-reference.md`
- 已实现：Multi-Agent 步骤终态把 `stepModifiedFiles` 同步到运行态 `ExecutionStep`、checkpoint 和 WorkingMemory；依赖步骤上下文和 `/plan resume` 恢复 completed artifact 时保留修改文件清单；`search_code` 已将结构化证据迁移到强类型旁路载荷，WorkingMemory 优先读取旁路证据并兼容旧 JSON 与旧展示文本；`ToolRegistry` 按项目路径复用 `CodeRetriever` / SQLite 连接；RAG 与 Memory 向量余弦相似度统一；`LongTermMemoryPolicy` 补充英文显式记忆、临时信息、个人属性和新状态规则；长期记忆注入抑制与 WorkingMemory 临时事实语义重复的条目
- 验证建议：`mvn -q -DskipTests=false "-Dtest=AgentOrchestratorTest,MemoryManagerTest,LongTermMemoryPolicyTest,SearchResultFormatterTest,VectorStoreTest,ToolRegistryTest" test`
- 风险：已于 2026-07-17 迁移为强类型旁路结果；当前剩余风险是旁路对象只在进程内传递，历史 checkpoint 仍依赖旧文本兼容解析

## 2026-06-16 公开数据集评测框架

- 状态：阶段二已实现（2026-07-16），完整公开集合规模评测继续推进
- 来源：用户希望围绕 Multi-Agent、Memory、Context Compression、RAG 四条链路进行公开数据集测试和量化
- 影响范围：`src/test/java/com/devcli/benchmark/`、`src/test/java/com/devcli/rag/`、`Data/processed/`、`Data/manifest/`、README、AGENTS 和详细架构文档
- 已实现：RAG 统一输出 Recall@5、MRR@5、nDCG@5；Agent 输出任务成功率；Memory 输出写入准确率、低价值拦截率、Recall@5 和注入命中率；Compression 输出事实保真率；聚合器生成固定 JSON、CSV 和数据清单
- 已验证：CodeSearchNet Java 公共 test split 50 条；Memory 25 条策略样本与 12 条召回查询；230k token 阈值、18 条事实、5 次真实压缩；2026-07-16 Agent 完整复跑中，单 Agent 成功率 0/5、隐藏检查平均完成率 0%，Planner/Worker/Reviewer 成功率 0/5、隐藏检查平均完成率 27.33%
- 已实现阶段二（2026-07-16）：固定 SWE-bench Lite、LongMemEval Oracle Cleaned、LongBench v1 和 RULER v1 官方版本、许可、SHA-256 与本地原始数据边界；新增统一目录清单、数据适配器、官方指标兼容实现、RULER 固定种子生成、SWE-bench predictions 与 Linux Docker harness 命令、真实长上下文报告及聚合 CSV 接入；首轮运行 LongMemEval 3 条、LongBench 6 条、RULER 3 条。SWE-bench Lite 单样本已生成 predictions，但补丁只包含复现脚本；官方 harness 已修复 fixtures 导入与本地镜像构建参数
- 待完成：LongMemEval 官方 LLM judge、SWE-bench Lite 官方 resolved 结果、LongBench/RULER 多长度和扩大样本评测；SWE-bench 基础镜像构建连续两次因 Ubuntu archive 返回 503 中断，尚无有效 resolved 分母；当前首轮公开样本只验证链路，不代表完整集合成绩
- 风险：真实 LLM、Embedding、Reranker 和公开数据集端点会引入费用、耗时、网络依赖和结果波动；50 条 RAG 样本、3 条公开长上下文样本和单次 Agent 运行不代表统计稳定结论

## 2026-06-16 CodeSearchNet Java RAG 评测适配

- 状态：已实现（2026-07-13）
- 影响范围：`src/test/java/com/devcli/benchmark/CodeSearchNetJavaDatasetAdapter.java`、`RagRetrievalBenchmarkIT`、`CodeRetriever` 及评测报告
- 已实现：通过 HuggingFace datasets-server 自动读取指定区间，将 CodeSearchNet Java rows 转为可索引源码，接入 RAG benchmark 并输出 Recall@5、MRR@5、nDCG@5
- 架构调整：长文档型 definition 查询直接进入 semantic route，避免 keyword fusion 与 reranker 排序噪声；短符号查询保留 precise-first 路由
- 验证结果：Java test split 50 条，Recall@5 1.0000、MRR@5 0.9900、nDCG@5 0.9926
- 建议验证命令：`mvn test -Dtest=CodeSearchNetJavaDatasetAdapterTest,RetrievalMetricsTest -DskipTests=false`

## 2026-06-19 对标 cc 的长会话上下文治理改造

- 状态：阶段 1-7 当前核心项已实现（2026-06-21；OAuth 不纳入当前阶段）
- 来源：对比 `C:\Document\Gongji Tech\FDE Workstation\cc` 中 Context Compression、Session Memory、Skill、MCP、工具发现等实现后形成的改造计划
- 总目标：在保留 DevCLI 现有 RAG 优势的基础上，补齐长会话压缩前置摘要、压缩后上下文恢复、结构化压缩边界、Skill 受控执行和 MCP 运行时治理能力
- 总影响范围：`src/main/java/com/devcli/memory/`、`src/main/java/com/devcli/agent/`、`src/main/java/com/devcli/tool/`、`src/main/java/com/devcli/skill/`、`src/main/java/com/devcli/mcp/`、`src/main/resources/prompts/`、`README.md`、`AGENTS.md`、`docs/agents-reference.md`
- 约束：不削弱现有 `CodeRetriever` 的 semantic + keyword + graph + rerank 链路；不引入远程 Skill 或复杂遥测作为第一阶段目标；所有阶段优先补针对性测试，不运行全量测试

### 阶段 1：结构化压缩边界与压缩元数据

- 状态：已实现（2026-06-19）
- 已实现：`ConversationHistoryCompactor` 在摘要消息中追加 `<compact_boundary>` 结构化边界块，记录压缩类型、触发原因、压缩模式、压缩前后 token、原始消息数、重建消息数、保留消息数和摘要字符数；增量压缩读取上一轮摘要时会剥离边界块，避免边界元数据进入 LLM 摘要正文；边界元数据已补充已加载 Skill、RAG epoch、MCP 工具快照和压缩后恢复入口状态，ReAct / Plan / SubAgent 压缩路径会注入当前运行时快照；MCP 工具快照已按 server 记录工具数量、schema 指纹和 server 生命周期版本；RAG epoch 已合并 WorkingMemory 已命中证据 epoch 与当前项目全局索引版本快照
- 后续可选：阶段 1 当前无剩余核心项；可在阶段 3 继续细化 MCP 工具状态和角色化恢复内容
- 影响范围：`ConversationHistoryCompactor`、`CompactBoundaryMetadata`、`CompactBoundaryRuntimeState`、`Agent`、`PlanExecuteAgent`、`SubAgent`、`MemoryManager`、`ToolRegistry`、`SkillContextBuffer`、`McpServer`、`McpServerManager`、`VectorStore`、相关 memory / MCP / RAG / tool 测试
- 目标：把当前基于 `[已压缩的历史对话摘要]` 文本标记的机制扩展为结构化 compact boundary，记录压缩类型、触发原因、压缩前后 token、保留消息范围、已加载 Skill、RAG epoch 和 MCP 工具快照
- 参考点：cc 的 `compact_boundary` / `microcompact_boundary` 元数据
- 验证建议：新增或扩展 `ConversationHistoryCompactorTest`、`ConversationHistoryCompactorStabilityTest`
- 风险：LLM messages 协议对 system/user/assistant 顺序敏感，边界消息必须避免破坏 tool_call / tool_result 配对

### 阶段 2：Session Memory 前置摘要

- 状态：已实现（2026-06-19）
- 已实现：新增 `SessionMemory` 会话预摘要缓存，按待压缩消息指纹判断预摘要是否覆盖旧消息；`ConversationHistoryCompactor` 首次全量压缩时优先复用匹配的预摘要，避免重复调用 LLM 摘要；`MemoryManager` 持有当前会话的 `SessionMemory`，ReAct 与 Plan 路径的压缩器共享该实例；ReAct turn 结束后会按 token 增量、工具调用次数和大工具结果阈值维护会话预摘要，当前只写入进程内 `SessionMemory`，不写长期记忆；Plan / Multi-Agent turn 结束后会提交后台预摘要维护任务；预摘要默认 30 分钟过期，过期后不再复用；后台维护使用 `MemoryManager` 内部单线程 daemon executor，关闭 `MemoryManager` 时同步关闭
- 后续可选：阶段 2 当前无剩余核心项；可评估跨进程持久化预摘要和持久化后台任务队列
- 影响范围：`SessionMemory`、`ConversationHistoryCompactor`、`MemoryManager`、`Agent`、`PlanExecuteAgent`、`AgentOrchestrator`、相关 memory / agent 测试
- 目标：在普通对话过程中按 token 增量和工具调用次数后台维护会话摘要；自动压缩时优先使用已维护摘要，缺失或过期时再调用现有 LLM 摘要压缩
- 参考点：cc 的 Session Memory extraction hook 与 `trySessionMemoryCompaction`
- 验证建议：新增 session memory 阈值判断、摘要更新时间、压缩复用路径测试
- 风险：后台摘要不能阻塞主对话；摘要写入必须受路径和权限约束，避免与长期记忆职责重叠

### 阶段 3：压缩后上下文恢复

- 状态：已实现（2026-06-20）
- 已实现：`ConversationHistoryCompactor` 支持压缩成功后插入 `[压缩后恢复上下文]` 消息；恢复内容位于摘要确认消息之后、保留尾部之前，并保持后续保留区仍从 user 消息边界开始；ReAct、Plan、SubAgent 路径已接入压缩恢复 supplier；`MemoryManager` 会输出结构化恢复段，拆分为最近读写文件、未完成子任务状态、关键工具结果引用和 RAG 证据 epoch；`TaskLedger` 提供未完成子任务专用恢复格式，只展开 running / failed / pending 并保留 completed_count；Agent / Plan / SubAgent 会追加 MCP 工具状态专用恢复段；恢复内容通过 `PostCompactRestoreContext` 做统一预算控制和行级去重；SubAgent 压缩恢复按 Planner / Worker / Reviewer 角色裁剪，Planner 不携带工具证据，Reviewer 不携带会话临时事件；`SkillContextBuffer` 会在压缩后恢复已加载 Skill 及其允许工具
- 后续可选：阶段 3 当前无剩余核心项；可继续把恢复预算从字符级升级为 token 级，并按路径或 toolCallId 做更细粒度语义去重
- 影响范围：`ConversationHistoryCompactor`、`MemoryManager`、`WorkingMemory`、`SkillContextBuffer`、`Agent`、`PlanExecuteAgent`、`SubAgent`、相关 memory / skill / agent 测试
- 目标：压缩后重新注入最近读取文件摘要、任务账本、已调用 Skill、MCP 工具状态、未完成子任务状态和关键 RAG 证据，减少模型压缩后重复读文件或丢失执行状态
- 参考点：cc 的 post-compact file attachments、invoked skills attachment、plan mode attachment、MCP instructions delta
- 验证建议：新增压缩后恢复内容的单元测试，覆盖 Skill、RAG 证据和工具结果去重
- 风险：恢复内容如果缺少预算控制，会抵消压缩收益

### 阶段 4：MicroCompact 按工具结果治理

- 状态：已实现（2026-06-21）
- 已实现：`ConversationHistoryCompactor` 的 microcompact 对旧的超大 tool 消息支持完整原文落盘，消息中写入 `<microcompact_boundary>`、toolCallId、原始字符数和 storedPath；落盘路径位于项目根 `.devcli/microcompact_tool_outputs/<session>/`，文件名做安全化；ReAct、Plan、SubAgent 路径会在压缩前刷新当前项目根；microcompact 会保留最近 2 个 user round 的工具结果，对更旧轮次中的 `tool_result` 按 toolCallId 成批落盘并替换为 boundary 引用，保持 tool_call / tool_result 消息配对；`WorkingMemory` 压缩后恢复区会将 microcompact 工具引用渲染为 toolCallId / originalChars / storedPath，并按 storedPath 或 toolCallId 去重
- 后续可选：阶段 4 当前无剩余核心项；可把“最近 2 个 user round”做成 ContextProfile 参数，并补充基于真实时间戳的保留策略
- 影响范围：`ConversationHistoryCompactor`、`Agent`、`PlanExecuteAgent`、`SubAgent`、相关 microcompact / tool result 测试
- 目标：从单条消息头尾截断升级为按工具调用 ID 清理旧工具结果；原始结果落盘保留，messages 中只保留引用、摘要和可恢复路径
- 参考点：cc 的 time-based microcompact 和 tool_result content clear
- 验证建议：覆盖大工具结果落盘、旧结果清理、最近结果保留、清理后仍可读取原文路径
- 风险：必须保证清理后仍不破坏工具调用配对；落盘路径不能泄漏项目根外内容

### 阶段 5：Skill 受控执行增强

- 状态：已实现（2026-06-21）
- 已实现：Skill frontmatter 支持 `allowedTools: [tool_a, tool_b]`、`context: inline|fork` 和 `paths`；`SkillRegistry` 会将允许工具、上下文偏好和路径条件写入 `Skill` 元数据；ReAct、Plan、SubAgent 的 Skill 索引会根据当前用户输入或任务文本中的项目相对路径筛选 path-scoped Skill；启用 Skill 按本进程内使用频率优先、名称次序兜底排序；`load_skill` 返回结果会提示允许工具范围和 context，并记录使用次数；声明了 `allowedTools` 的已加载 Skill 会在运行时强制限制后续工具调用，白名单状态随 `SkillContextBuffer` 隔离并在 `/clear` 时清空；压缩后恢复会保留已调用 Skill 的 context、allowedTools 和内容摘要
- 后续可选：阶段 5 当前无剩余核心项；可继续把 `context: fork` 从提示性上下文偏好升级为独立 fork 执行通道
- 影响范围：`Skill`、`SkillRegistry`、`SkillPathMatcher`、`SkillContextBuffer`、`SkillIndexFormatter`、`ToolRegistry.load_skill`、`HitlToolRegistry`、`Agent`、`PlanExecuteAgent`、`SubAgent`、Skill / Agent 相关测试
- 目标：支持 `allowedTools`、`context: fork`、`paths` 条件激活、Skill 使用频率排序，并在压缩后恢复已调用 Skill 内容
- 参考点：cc 的 Skill inline / fork 双路径、Safe Properties 权限白名单、条件激活和 invoked skills 恢复
- 验证建议：扩展 `SkillRegistryTest`、`SkillFrontmatterParserTest`、`LoadSkillToolTest`、新增 fork skill 行为测试
- 风险：Skill fork 需要隔离权限、WorkingMemory 和工具证据，避免污染主 Agent 上下文

### 阶段 6：MCP 运行时治理增强

- 状态：已实现（2026-06-21）
- 已实现：`McpToolDescriptor` 支持工具 `annotations` 元数据；`McpClient.tools/list` 会解析 `readOnlyHint`、`destructiveHint`、`openWorldHint`；MCP 工具注册到 `ToolRegistry` 后，工具描述会携带 `readOnly`、`destructive`、`openWorld` / `closedWorld` 标签，便于模型和 HITL 层识别风险语义；`HitlToolRegistry` 已将 `destructive` / `openWorld` annotations 接入逐次强制审批策略，这类 MCP 工具不会复用 tool/server 级全部放行缓存；`McpServerManager` 已记录本进程内连接事件，覆盖 STARTING / READY / ERROR / DISABLED / TOOLS_CHANGED，并携带 server、状态、生命周期版本、工具数量和消息；MCP 工具发现结果已进入本进程缓存，记录 server、生命周期版本、工具数量、工具名、schema 指纹和发现时间，server 禁用后仍保留上一轮发现元数据，供后续 Deferred Tool / 工具搜索复用
- 已实现补充：MCP 工具结果进入尺寸治理后会标记折叠分类，截断输出标记 `INLINE_TRUNCATED`，落盘预览标记 `PERSISTED_PREVIEW`；MCP server 启动失败后会进入后台自动重连，默认最多 3 次，并记录 `RECONNECTING` 连接事件，成功后重新注册工具；MCP `tools/call` 会携带 `_meta.progressToken`，并把同 token 的 `notifications/progress` 汇总到工具结果文本
- 不纳入当前阶段：OAuth 基础流程当前不做；个人使用场景没有真实登录计划，先保留 Bearer / 自定义 header 配置能力
- 后续可选：阶段 6 当前无剩余核心项
- 影响范围：`McpToolDescriptor`、`McpClient`、`McpServerManager`、`McpConnectionEvent`、`McpToolDiscoveryEntry`、`ToolRegistry`、`ToolResultSizeManager`、`HitlToolRegistry`、MCP / HITL / tool 注册测试
- 目标：补充 MCP 工具发现缓存、连接事件、重连、工具注解映射（readOnly/destructive/openWorld）、长运行进度和结果折叠分类
- 参考点：cc 的 MCP manager、tool discovery cache、MCPTool collapse classification
- 验证建议：扩展 `McpServerManagerTest`、`McpClientTest`、`McpToolRegistrationTest`、协议 schema 测试
- 风险：重连会改变启动与失败语义，需要保持首屏不被 MCP 阻塞

### 阶段 7：Deferred Tool / 工具搜索

- 状态：已实现（2026-06-21）
- 已实现：新增内置 `search_tools` 工具，可按工具名、描述和参数 schema 检索当前已注册工具；检索范围包含内置工具和运行时注册的 MCP 动态工具；结果返回工具名和一行描述，为后续延迟加载工具集提供入口；未知工具调用会提示先调用 `search_tools` 并给出基于原工具名的 query 示例；`search_tools` 使用工具索引缓存，工具目录未变化时复用索引，MCP 工具注册、卸载或替换后自动失效重建；`getToolDefinitions()` 默认只注入内置核心工具和已激活 MCP 工具，`search_tools` 命中的 MCP 工具会激活到后续 LLM 工具定义；ReAct、Plan 和 Multi-Agent turn 开始前会按当前用户输入预激活匹配到的 MCP 工具定义
- 后续可选：阶段 7 当前无剩余核心项
- 影响范围：`ToolRegistry`、`Agent`、`PlanExecuteAgent`、`AgentOrchestrator`、MCP 动态工具注册视图、工具注册测试
- 目标：当 MCP 工具数量较多时默认只注入核心工具和少量高频工具，提供 `search_tools` 或类似入口按工具名、描述、schema 检索并延迟加载
- 参考点：cc 的 `SearchExtraToolsTool`、TF-IDF 工具索引和 inter-turn prefetch
- 验证建议：新增工具索引、检索排序、延迟加载后可调用测试
- 风险：工具延迟加载会改变模型可见工具集合，必须保证错误提示能引导模型重新搜索工具

## 2026-07-15 LiveAgent 可复用架构提取

- 状态：参考源码已拉取并完成静态提取，两个 P1、两个 P2 与 P3 能力已实现
- 来源：`Stack-Cairn/LiveAgent`，提取基线提交 `8dc4b9d830af9d2a5549d7d10c267019d22ef90f`
- 影响范围：长期记忆治理、Runtime API 历史持久化、运行事件协议、Hook 生命周期、Multi-Agent 跨进程恢复
- 已完成：筛选长期记忆组织器、结构化证据与审核状态、持久化压缩检查点、强类型运行事件、受控 Hook、持久化子代理身份；明确排除外部 Shell 路径、文本错误模型、工具名特殊并行、普通 worktree 自动应用和技术栈迁移
- 已实现 P1（2026-07-15）：长期记忆新增结构化 `MemoryEvidence`，持久化 confidence、sourceQuote、reasoning、reviewState、conflictsWith，并按来源引用完整度自动降级 HIGH/MEDIUM；SQLite 旧库幂等补列，旧行按 REVIEWED 兼容迁移；显式写入默认 REVIEWED，策略自动写入默认 UNREVIEWED，REJECTED 保留审计但从关键词、语义召回和 prompt 注入排除；冲突关系结构化持久化并保留旧 metadata 兼容
- 已实现 P1（2026-07-15）：新增长期记忆离线组织器与 `/memory organize`、`/memory organize apply`；库存有界为 100 条、正文 300 字符并使用 JSON 数据载荷；模型输出 KEEP/MERGE/REVIEW/REJECT 结构化计划，解析失败有界修复 1 次；程序重新校验来源标识、类型、主题、审核状态、覆盖范围和计划置信度，仅自动应用同主题、同类型、全部 UNREVIEWED、覆盖完整且置信度不低于 0.9 的合并，已审核和高风险候选只在本次报告中标记为需要人工复核或由策略拒绝；当前不持久化复核队列
- 后续可选：增加语义主题聚类、持久化组织器运行历史和人工复核后的显式应用入口
- 已实现 P1（2026-07-15）：Runtime API 长 thread 默认在历史达到 32,000 token 后生成持久化压缩检查点；保存压缩消息窗口、覆盖完成事件、摘要、token 变化、语义守卫结果、Skill、RAG epoch 和 MCP 快照；恢复使用最新有效检查点并完整追加检查点后的已完成 turn，没有检查点时恢复全部已完成 turn；候选消息移除动态 system prompt、reasoning 和图片正文；检查点在 `turn.completed` 后保存，失败只产生独立事件，损坏记录回退更早检查点
- 已实现 P2（2026-07-15）：新增强类型 `RunEvent`、事件 sink、模型流适配器和 Runtime JSON 投影；`AgentExecutionEngine` 统一产生 reasoning/content delta、工具调用和工具结果事件，ReAct Renderer 直接消费同一事件流，Plan task 与 SubAgent 的旧 StreamListener 通过适配器兼容；Runtime API 的 turn、模型流、工具和 checkpoint 事件不再手工拼接 JSON，流式消息不重复写入最终输出；无头 Provider 只流式输出 reasoning 时从最终 assistant history 恢复答案
- 已实现 P2（2026-07-15）：新增 agent/turn/message/tool execution 四层幂等 Hook 生命周期，统一挂接 AgentExecutionEngine；用户级与项目级配置按 id 合并，支持 64 条上限和运行上下文占位符；Hook 只调用 ToolRegistry 工具，不提供旁路 shell/HTTP 执行器，READ_ONLY/LOCAL_CONTEXT 强制收窄能力，其他副作用必须显式允许、启用 HITL 并命中逐次审批策略；warn 失败不改变核心终态，required 失败进入标准 Agent 失败出口，异常和取消路径会闭合未结束生命周期
- 已实现 P3（2026-07-16）：Multi-Agent checkpoint 协议升级到版本 4，保存稳定 Planner/Worker/Reviewer 身份、步骤到 Worker/Reviewer 的绑定、单调消息游标和有界最近摘要；resume 按 checkpoint 重建 Worker 拓扑，配置数量变化时仍保持原步骤分配，并按上下文 schema 版本注入摘要；版本 1/2/3 继续兼容，重复消息边界不推进游标，损坏身份拓扑拒绝恢复；未持久化完整 SubAgent 对话对象图，也未新增独立消息数据库
- 文档：详细筛选结果记录在 `docs/liveagent-reference-extraction.md`
- 验证建议：每个候选能力单独设计和提交；优先补 Memory Store 契约测试、组织器风险矩阵测试、Runtime checkpoint 恢复测试和事件顺序测试
- 风险：LiveAgent 仍在快速迭代；这里只提取机制，不保证其实现可直接移植。禁止新增与 WorkingMemory、ExecutionArtifact、PatchSet 或 ToolExecutionPipeline 重复的状态源

## 2026-07-20 Grok Build 可复用架构提取

- 状态：已完成两项个人展示价值最高、可按生产标准落地的能力
- 来源：`xai-org/grok-build`，本地参考目录 `Temp/grok-build`，分析基线提交 `ba76b0a683fa52e4e60685017b85905451be17bc`
- 影响范围：CLI 活动轮次交互、模型调用取消和重试边界
- 已实现：ReAct 且 HITL 关闭时支持活动轮次继续输入；普通文本进入容量为 8 的会话内 FIFO 队列，`/now <任务>` 入队首部后取消当前任务，`/cancel` 只取消当前任务；队列满时拒绝新项，不因立即执行请求丢弃原队列；模型完成时唤醒 LineReader，未提交输入保留为草稿
- 已实现：模型采样请求具有稳定请求标识、独立取消令牌和请求代次；同标识新请求原子替换旧请求并取消旧线程；旧作用域关闭时不能删除新代次；取消状态进入统一 `LlmErrorCode.CANCELLED`，取消后不进入重试循环
- 明确排除：ACP、编辑器协议、云上传、GCS、遥测、反馈上传、企业认证、插件市场、Rust 代码移植，以及对 TUI、沙箱、Memory、PatchSet 的整体重写
- 已验证：活动轮次队列、输入解析、队首抢占、容量拒绝、采样请求替换、独立取消、重试中止和执行引擎注册清理的限定单元测试；Maven 测试编译通过
- 未验证：未启动项目，未进行真实终端的中途输入、方向键、补全、草稿唤醒、`/now` 抢占和流式输出交互测试；遵循当前仓库限制，不执行全量测试
- 已增强（2026-07-20）：取消动作增加真实执行线程退出屏障，不能再把 `Future.cancel` 误当作任务已经停止；默认等待 5 秒，模型调用未退出时停止接收新任务，避免旧轮次和新轮次并发写入 Agent 历史。`/now` 已纳入统一命令解析、帮助和补全，空闲时直接执行携带的任务，不再显示未知命令
- 风险：活动轮次队列只在 ReAct 且 HITL 关闭时启用，这是终端输入所有权约束，不应扩展到会触发计划审阅或审批读取的路径，除非先引入统一输入仲裁器
