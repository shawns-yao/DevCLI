# TODO

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

- 状态：未实现
- 来源：对比 `C:\Document\Gongji Tech\FDE Workstation\cc` 中 Context Compression、Session Memory、Skill、MCP、工具发现等实现后形成的改造计划
- 总目标：在保留 DevCLI 现有 RAG 优势的基础上，补齐长会话压缩前置摘要、压缩后上下文恢复、结构化压缩边界、Skill 受控执行和 MCP 运行时治理能力
- 总影响范围：`src/main/java/com/devcli/memory/`、`src/main/java/com/devcli/agent/`、`src/main/java/com/devcli/tool/`、`src/main/java/com/devcli/skill/`、`src/main/java/com/devcli/mcp/`、`src/main/resources/prompts/`、`README.md`、`AGENTS.md`、`docs/agents-reference.md`
- 约束：不削弱现有 `CodeRetriever` 的 semantic + keyword + graph + rerank 链路；不引入远程 Skill 或复杂遥测作为第一阶段目标；所有阶段优先补针对性测试，不运行全量测试

### 阶段 1：结构化压缩边界与压缩元数据

- 状态：部分实现（2026-06-19）
- 已实现：`ConversationHistoryCompactor` 在摘要消息中追加 `<compact_boundary>` 结构化边界块，记录压缩类型、触发原因、压缩模式、压缩前后 token、原始消息数、重建消息数、保留消息数和摘要字符数；增量压缩读取上一轮摘要时会剥离边界块，避免边界元数据进入 LLM 摘要正文
- 未实现：已加载 Skill、RAG epoch、MCP 工具快照、压缩后上下文恢复入口尚未写入边界元数据
- 影响范围：`ConversationHistoryCompactor`、`CompactBoundaryMetadata`、相关 memory 测试；暂未触及 `Agent` / `PlanExecuteAgent` 的 history 组装
- 目标：把当前基于 `[已压缩的历史对话摘要]` 文本标记的机制扩展为结构化 compact boundary，记录压缩类型、触发原因、压缩前后 token、保留消息范围、已加载 Skill、RAG epoch 和 MCP 工具快照
- 参考点：cc 的 `compact_boundary` / `microcompact_boundary` 元数据
- 验证建议：新增或扩展 `ConversationHistoryCompactorTest`、`ConversationHistoryCompactorStabilityTest`
- 风险：LLM messages 协议对 system/user/assistant 顺序敏感，边界消息必须避免破坏 tool_call / tool_result 配对

### 阶段 2：Session Memory 前置摘要

- 状态：部分实现（2026-06-19）
- 已实现：新增 `SessionMemory` 会话预摘要缓存，按待压缩消息指纹判断预摘要是否覆盖旧消息；`ConversationHistoryCompactor` 首次全量压缩时优先复用匹配的预摘要，避免重复调用 LLM 摘要；`MemoryManager` 持有当前会话的 `SessionMemory`，ReAct 与 Plan 路径的压缩器共享该实例
- 未实现：主 Agent turn 结束后自动维护预摘要、按 token 增量和工具调用次数触发预摘要刷新、预摘要过期策略和后台异步调度尚未接入
- 影响范围：`SessionMemory`、`ConversationHistoryCompactor`、`MemoryManager`、`Agent`、`PlanExecuteAgent`、相关 memory 测试
- 目标：在普通对话过程中按 token 增量和工具调用次数后台维护会话摘要；自动压缩时优先使用已维护摘要，缺失或过期时再调用现有 LLM 摘要压缩
- 参考点：cc 的 Session Memory extraction hook 与 `trySessionMemoryCompaction`
- 验证建议：新增 session memory 阈值判断、摘要更新时间、压缩复用路径测试
- 风险：后台摘要不能阻塞主对话；摘要写入必须受路径和权限约束，避免与长期记忆职责重叠

### 阶段 3：压缩后上下文恢复

- 状态：未实现
- 影响范围：`WorkingMemory`、`SkillContextBuffer`、`ToolRegistry`、MCP 工具注册状态、Plan / Team 状态注入
- 目标：压缩后重新注入最近读取文件摘要、任务账本、已调用 Skill、MCP 工具状态、未完成子任务状态和关键 RAG 证据，减少模型压缩后重复读文件或丢失执行状态
- 参考点：cc 的 post-compact file attachments、invoked skills attachment、plan mode attachment、MCP instructions delta
- 验证建议：新增压缩后恢复内容的单元测试，覆盖 Skill、RAG 证据和工具结果去重
- 风险：恢复内容如果缺少预算控制，会抵消压缩收益

### 阶段 4：MicroCompact 按工具结果治理

- 状态：未实现
- 影响范围：`ToolResultSizeManager`、`ConversationHistoryCompactor`、`WorkingMemory`
- 目标：从单条消息头尾截断升级为按工具调用 ID 清理旧工具结果；原始结果落盘保留，messages 中只保留引用、摘要和可恢复路径
- 参考点：cc 的 time-based microcompact 和 tool_result content clear
- 验证建议：覆盖大工具结果落盘、旧结果清理、最近结果保留、清理后仍可读取原文路径
- 风险：必须保证清理后仍不破坏工具调用配对；落盘路径不能泄漏项目根外内容

### 阶段 5：Skill 受控执行增强

- 状态：未实现
- 影响范围：`SkillRegistry`、`SkillFrontmatterParser`、`SkillContextBuffer`、`ToolRegistry.load_skill`、SubAgent fork 上下文
- 目标：支持 `allowedTools`、`context: fork`、`paths` 条件激活、Skill 使用频率排序，并在压缩后恢复已调用 Skill 内容
- 参考点：cc 的 Skill inline / fork 双路径、Safe Properties 权限白名单、条件激活和 invoked skills 恢复
- 验证建议：扩展 `SkillRegistryTest`、`SkillFrontmatterParserTest`、`LoadSkillToolTest`、新增 fork skill 行为测试
- 风险：Skill fork 需要隔离权限、WorkingMemory 和工具证据，避免污染主 Agent 上下文

### 阶段 6：MCP 运行时治理增强

- 状态：未实现
- 影响范围：`McpServerManager`、`McpClient`、`McpConfigLoader`、`ToolRegistry`、MCP 命令与文档
- 目标：补充 MCP 工具发现缓存、连接事件、重连、OAuth 基础流程、工具注解映射（readOnly/destructive/openWorld）、长运行进度和结果折叠分类
- 参考点：cc 的 MCP manager、tool discovery cache、OAuth/XAA、MCPTool collapse classification
- 验证建议：扩展 `McpServerManagerTest`、`McpClientTest`、`McpToolRegistrationTest`、协议 schema 测试
- 风险：OAuth 和重连会改变启动与失败语义，需要保持首屏不被 MCP 阻塞

### 阶段 7：Deferred Tool / 工具搜索

- 状态：未实现
- 影响范围：`ToolRegistry`、Agent prompt、MCP 动态工具注册、工具选择提示词
- 目标：当 MCP 工具数量较多时默认只注入核心工具和少量高频工具，提供 `search_tools` 或类似入口按工具名、描述、schema 检索并延迟加载
- 参考点：cc 的 `SearchExtraToolsTool`、TF-IDF 工具索引和 inter-turn prefetch
- 验证建议：新增工具索引、检索排序、延迟加载后可调用测试
- 风险：工具延迟加载会改变模型可见工具集合，必须保证错误提示能引导模型重新搜索工具
