# TODO

## 2026-07-12 Agent Runtime 架构统一改造

- 状态：已实现（架构主线）
- 来源：三条 Agent 执行路径、后台任务和 Runtime API 缺少统一运行上下文，工具结果、任务图、工作区隔离与会话并发仍存在分裂模型
- 影响范围：Runtime、Agent、Tool、Plan、Multi-Agent、Runtime API、后台任务、验证器、工作区隔离及相关文档与测试
- 已实现：新增运行级 `RunContext`，隔离项目路径、取消令牌和资源生命周期；取消状态不再使用进程级全局回退，线程中断可直接触发取消；后台任务为每个任务绑定独立运行上下文并在取消时同步取消令牌；无头 Agent 统一通过生命周期入口创建和关闭工具注册与记忆资源；工具大结果落盘路径改为使用所属工具注册实例的项目路径，消除跨项目静态串扰；Agent 明确区分自有与外部工具注册资源；工具结果新增状态、错误码、重试语义、图片和修改资源字段；ReAct、Plan、SubAgent 的错误熔断改用结构化错误码；工具执行统一进入分阶段中间件管线；HITL 从覆写执行入口改为管线中间件，拒绝和跳过返回结构化结果；新增统一 `AgentExecutionEngine`，ReAct、Plan task、SubAgent 共用预算检查、取消检查、LLM 调用、工具消息协议、工具结果回灌和异常出口，三条路径不再各自维护循环；新增共享 `ExecutionGraph`，Plan 与 Multi-Agent 共用依赖就绪判断、最终集成调度、缺失依赖和环检测；Runtime API 使用有界 keyed 串行执行器，同一 thread 的 turn 按提交顺序串行，不同 thread 仍可并行；新增共享 `ExecutionArtifact`，Plan `Task`、Multi-Agent `ExecutionStep` 和 checkpoint 统一使用状态、输出、摘要、修改资源、错误、尝试次数与时间戳；checkpoint 协议升级为版本 2，通过 `RecoveryState` 统一恢复，新版本保存共享 artifact，旧 completed/failed map 可迁移；新增 `IsolatedWorkspace`、`WorkspaceExecutionSession` 和 `PatchSet`，Plan 的 FILE_WRITE/COMMAND/VERIFICATION 与 Multi-Agent 副作用步骤在隔离目录执行，Reviewer 读取同一隔离产物，批准后才以哈希前置校验一次性应用主工作区，冲突、拒绝、失败或取消均不应用；PatchSet 拒绝非普通文件覆盖、路径逃逸和链接逃逸，并在应用失败时回滚；Pre-Review 编译、超时、输出解码和失败摘要已从编排器拆分到独立验证器
- 未实现：部分旧 Provider 仍通过文本表达业务失败，尚未逐项迁移为明确的业务错误码；交互入口和编排器仍可继续按职责拆分，但本期已完成执行引擎、任务图、预审验证器与工作区生命周期的关键边界拆分
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
- 来源：资源租约边界检查发现 `/team` Worker 只绑定 `runWithResourceLease` 上下文，步骤尝试结束后没有显式释放 step 租约；异常、Reviewer 打回或在位重做路径可能依赖超时抢占回收
- 影响范围：`src/main/java/com/devcli/agent/AgentOrchestrator.java`、`src/test/java/com/devcli/agent/AgentOrchestratorTest.java`、`README.md`、`AGENTS.md`、`docs/agents-reference.md`、`docs/runtime-resource-lease-design.md`
- 已实现：`AgentOrchestrator.executeWorkerOnce` 在 finally 中调用 `releaseResourceLeases(stepId)`；新增测试覆盖 Worker 尝试结束后释放资源租约
- 未实现：未新增后台定时清理；现有 `pruneExpiredLeases` 仍作为超时残留的主动清理入口
- 验证建议：`mvn -Dtest=AgentOrchestratorTest#shouldReleaseWorkerResourceLeaseAfterStepCompletes -DskipTests=false test`
- 风险：并发 Worker 如果仍在同一毫秒级窗口写同一文件，冲突策略仍是拒绝后交给现有重试/审查流程处理，不做自动合并

## 2026-07-09 Side-Git 快照自动裁剪

- 状态：已实现
- 来源：长会话下 turn 级快照持续增长，`devcli.snapshot.max` 之前只限制展示/查询数量，没有真正裁剪 side-history
- 影响范围：`src/main/java/com/devcli/snapshot/SideGitManager.java`、`src/test/java/com/devcli/snapshot/SideGitManagerTest.java`、`README.md`、`AGENTS.md`、`docs/agents-reference.md`
- 已实现：每次新建快照后按 `SnapshotConfig.maxSnapshots` 重写 Side-Git 历史，只保留最新 N 条快照；新增测试覆盖超过保留上限时旧快照被裁剪
- 未实现：未对 unreachable Git object 做额外磁盘压缩；后续可评估 JGit GC 的稳定触发策略
- 验证建议：`mvn -Dtest=SideGitManagerTest -DskipTests=false test`
- 风险：历史重写会改变保留快照的 commit id；恢复功能按当前保留列表工作，不依赖旧 commit id

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
- 已实现：`ToolSearchProvider` 已迁移 `search_tools` 注册、搜索、缓存复用和 MCP 工具激活逻辑；`WebToolProvider` 已迁移 `web_search` / `web_fetch` 注册、搜索 provider 懒加载、HTTP 抓取、正文抽取和网络策略检查；`BrowserToolProvider` 已迁移 `browser_connect` / `browser_disconnect` / `browser_status` 注册和连接器调用逻辑；`RagToolProvider` 已迁移 `search_code` 注册、按项目路径复用 `CodeRetriever`、索引为空提示、semantic 降级提示、negativeFact 和 `RAG_EVIDENCE_JSON` 载荷；`SkillToolProvider` 已迁移 `load_skill` 注册、`SkillRegistry` 查询、`SkillContextBuffer` 写入、usage 记录、allowedTools 和 context inline/fork 语义；`ToolRegistry` 保留工具目录版本、MCP 动态注册链路和预激活入口
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
- 已实现：Multi-Agent 步骤终态把 `stepModifiedFiles` 同步到运行态 `ExecutionStep`、checkpoint 和 WorkingMemory；依赖步骤上下文和 `/team resume` 恢复 completed artifact 时保留修改文件清单；`search_code` 增加结构化 `RAG_EVIDENCE_JSON` 载荷，WorkingMemory 优先读取结构化证据并兼容旧文本；`ToolRegistry` 按项目路径复用 `CodeRetriever` / SQLite 连接；RAG 与 Memory 向量余弦相似度统一；`LongTermMemoryPolicy` 补充英文显式记忆、临时信息、个人属性和新状态规则；长期记忆注入抑制与 WorkingMemory 临时事实语义重复的条目
- 验证建议：`mvn -q -DskipTests=false "-Dtest=AgentOrchestratorTest,MemoryManagerTest,LongTermMemoryPolicyTest,SearchResultFormatterTest,VectorStoreTest,ToolRegistryTest" test`
- 风险：结构化 RAG 载荷仍嵌入工具文本返回；如后续工具执行框架支持 typed result，应迁移为真正的 side-channel 结果对象

## 2026-06-16 公开数据集评测框架

- 状态：未实现
- 来源：用户希望围绕 Multi-Agent、Memory、Context Compression、RAG 四条链路进行公开数据集测试和量化
- 影响范围：`src/test/java/com/devcli/benchmark/`、`src/test/java/com/devcli/rag/eval/`、未来可选 `eval/` 数据与报告目录
- 目标：接入或适配 CodeSearchNet Java、Defects4J、SWE-bench Lite、LongMemEval、RULER/LongBench 等公开集合，形成可复现实验命令和指标报告
- 初步方案：优先复用现有 `RagRetrievalBenchmarkIT`、`AgentCollaborationBenchmarkIT`、`RealLlmMemoryBenchmarkIT`、`RealLlmCompressionRetentionIT`，再补数据集下载/采样/转换脚本与固定 CSV/JSON 报告格式
- 风险：公开数据集与 DevCLI 的 Java Agent CLI 场景不完全匹配；真实 LLM、embedding、rerank、Defects4J/SWE-bench 环境会引入成本、耗时和复现波动

## 2026-06-16 CodeSearchNet Java RAG 评测适配

- 状态：部分实现
- 影响范围：`src/test/java/com/devcli/benchmark/CodeSearchNetJavaDatasetAdapter.java`、`src/test/java/com/devcli/benchmark/CodeSearchNetJavaDatasetAdapterTest.java`
- 已实现：将 HuggingFace datasets-server 返回的 CodeSearchNet Java rows 转为 DevCLI 可索引的 synthetic Java source cases
- 未实现：从 HuggingFace API 自动分页下载、接入 `RagRetrievalBenchmarkIT` 聚合公开数据集指标、输出 MRR/nDCG
- 建议验证命令：`mvn test -Dtest=CodeSearchNetJavaDatasetAdapterTest -DskipTests=false`

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
