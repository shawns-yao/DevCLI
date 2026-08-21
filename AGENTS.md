# AGENTS.md

仓库给 Agent / 新线程使用的首读入口。详细行为描述见 `docs/agents-reference.md`。

## 信息优先级

1. 代码实际行为 > 2. `AGENTS.md` > 3. `README.md` > 4. `ROADMAP.md` > 5. `CLAUDE.md`

`ROADMAP.md` 代表演进方向，不代表已交付。

## 输出风格

- 默认简短回答，严禁长篇大论、冗余铺垫、重复表达和过度展开。
- 语言极度凝练，只说核心重点；删掉多余解释、铺垫话术和延伸赘述。
- 如确需扩展，先给结论，再给最少必要依据。
- 谈及 Planner/Worker/Reviewer 架构时，三角色职责一句话极简概括，不拆分长讲。
- 区分测试任务时，直接点明旧任务弊端、新任务优势，不讲冗长原理。
- 表达观点直击结论，短句输出，拒绝大段文案。
- 涉及架构测试、任务选型、对比差异时，全部压缩精简，言简意赅。
- 用户要求润色、改写、简历表述或面试回答时，默认只给最优一版；不要列多个相似版本，除非用户明确要求备选。

## 项目快照

- 项目名：`DevCLI`
- 定位：面向商业使用的 Java Agent CLI 产品，对标 Claude Code
- 已交付 21 期（ReAct → Plan+DAG → Memory → RAG → Multi-Agent → HITL → 并行工具 → 多模型 → 联网 → MCP 核心 → MCP 高级 → 长上下文 → Chrome DevTools → CDP 会话复用 → Skill → TUI → LSP 诊断 → Side-Git 快照 → Prompt 分层 → Runtime API → 图片输入）
- 下一步：sampling / recovery 作为后续增强；OAuth 暂不纳入个人使用优先级
- Banner 版本：`v16.1.0`，Maven 产物：`devcli-1.0-SNAPSHOT.jar`

## 运行前提

- Java 17+ / Maven
- 默认 LLM provider 是 `anthropic`；至少一个 API Key：`ANTHROPIC_AUTH_TOKEN`（Anthropic Messages 兼容，可配 `ANTHROPIC_BASE_URL` / `ANTHROPIC_MODEL` / `ANTHROPIC_MAX_TOKENS`）/ `OPENAI_API_KEY` / `GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY`

## 常用命令

```bash
cp .env.example .env
mvn clean package        # 默认跳过测试，优先产出可手工验收 jar
java -jar target/devcli-1.0-SNAPSHOT.jar
mvn test -Pquick          # 常规回归
mvn test -Pphase16-smoke  # TUI 相关
mvn test -Dtest=XxxTest -DskipTests=false   # 针对性
mvn test -DskipTests=false                  # 全量回归
```

## 架构概览

顶层分为默认 ReAct 与显式 `/plan` 编排入口，共享 ToolRegistry / MemoryManager / SnapshotService；`/plan` 固定进入 Planner/Worker/Reviewer DAG 链路，不按任务内容自动切换，串行或并行由 DAG 与资源冲突决定。ReAct、Plan task、SubAgent 的单轮控制流统一由 `AgentExecutionEngine` 承载，负责取消、预算、LLM 调用、工具消息协议和异常出口：

| 路径 | 入口 | 触发 |
|------|------|------|
| ReAct | `Agent.java` | 默认模式 |
| Plan 编排 | `AgentOrchestrator.java` | `/plan`；旧 `/plan --team`、`/team` 仅保留解析兼容 |

Multi-Agent 中 Planner 负责拆解 DAG，Worker 负责实现子任务，Reviewer 先审计划语义闭环，再在硬检查通过后审查真实产物。

Plan 的任务适配由 `PlanTaskBatchExecutor` 负责，Team 的 Worker 适配由 `MultiAgentBatchExecutor` 负责；两者把冲突波次交给 `OrchestrationWaveExecutor`，共用有界并发、异常归属、独立输出缓冲与稳定顺序归并。任务结果及有界摘要由 `PlanTaskExecutionResult` 统一承载；Worker 池、公平锁、Pre-Review、Reviewer、角色记忆和 checkpoint 恢复拓扑仍只属于 Team。

Plan 与 Multi-Agent 的 DAG 就绪判断和图结构校验统一使用 `ExecutionGraph`：`Task` 与 `ExecutionStep` 都实现只读 `ExecutionNode`，普通节点只在依赖全部完成后执行，最终集成节点可在依赖进入完成或失败终态后执行；缺失依赖和环会在执行前拒绝。两类节点和 checkpoint 共用 `ExecutionArtifact`，状态、输出、摘要、修改资源、错误、尝试次数和时间戳不再分散存储。Planner 必须输出 `acceptance_criteria`；每条标准必须声明 `test_signal`、`verification_method=TOOL|HUMAN`、`verifier` 和 `applies_to`，目标只能引用有效 DAG 节点或 `FINAL`。普通节点只注入自己的验收点，Final integration 重新检查全部验收点。Team 在确定性预检后使用独立、无工具的 Reviewer 上下文检查原始需求到节点和验收标准的覆盖；语义拒绝进入 Planner 有界修复，评审协议损坏则失败关闭；通过后才进入用户执行、补充重规划或取消。Reviewer 执行期再用 `criteria_results` 逐条验证真实产物；TOOL 标准的声明验证器必须在本轮真实成功工具调用中出现，人工标准不能伪装为工具通过。验收点 `severity` 会随计划和 checkpoint 固化；critical/high 自动验收点失败或缺少覆盖时强制不通过。

Agent、Plan、Worker 和 Reviewer 的流式输出状态机统一委托 `AgentStreamPresenter`；Memory/Compactor/Skill/MCP 恢复装配统一委托 `AgentRuntimeSupport`。旧内部 Renderer 包装类只保留兼容构造器，不再维护第二套状态逻辑。关键启动配置使用 `ConfigResolver`，显式非法值在加载时拒绝。

Multi-Agent Planner 输出前后允许存在说明文本，编排器会提取完整 JSON 对象；解析失败、图结构无效或出现阻塞后续实现的空工作区纯检查步骤时，清空 Planner 历史并携带失败原因有界修复，默认 2 次，可通过 `DEVCLI_TEAM_PLANNER_REPAIR_MAX_ATTEMPTS` / `-Ddevcli.team.planner.repair.max.attempts` 调整。空工作区是合法状态，目录或文件存在性检查应并入实现步骤并写明“若不存在则创建”。Worker 最终文本为空但本轮存在结构化 `SUCCESS` 工具证据时，编排器生成执行摘要并继续 Reviewer；没有成功工具证据时先进行一次强制执行协议修复，代码任务必须调用 `write_file` 并最小验证，读取或分析任务必须取得真实工具证据；该请求按步骤类型强制具体工具，FILE_WRITE / INTEGRATION 选择 `write_file`，COMMAND 选择 `execute_command`，其他类型选择 `list_dir`；Anthropic 与 OpenAI-compatible 都映射为命名工具选择。FILE_WRITE / INTEGRATION 步骤出现成功 `write_file` 批次后直接以结构化证据结束当前 Worker 执行；强制修复中的指定工具也采用同一规则，不再追加 LLM 收尾调用。Provider 忽略命名工具选择时，执行引擎追加一次严格 JSON 工具信封请求；只接受完整 JSON、目标工具名和对象参数，随后仍由工具参数校验与权限管线执行，不解析 reasoning、Markdown 或代码围栏。工具失败时继续让模型纠正，最终仍无成功证据才判失败。

Multi-Agent 的 `SessionMemory` 按角色注入隔离视图：Planner 只看任务状态 + 会话关键事件，不看工具原文证据；Worker 看完整任务状态 + 关键事件 + 工具证据；Reviewer 只看任务状态 + 工具证据，避免把会话事件误当验收依据。

Multi-Agent 并行批次由 `MultiAgentBatchExecutor` 负责资源冲突分波、Worker 分配和公平锁，再委托 `OrchestrationWaveExecutor` 执行并发与输出归并；批次使用 `SubAgent.ForkContext` 共享冻结 system prompt 前缀、exact tool definitions 快照、skill body 快照和 fork fingerprint，每个子任务只追加自己的 user 后缀，避免并行 Worker / Reviewer 因历史或动态工具差异破坏 prompt cache 命中。

并行 Worker 写文件时，隔离 ToolRegistry 内的 `write_file` 仍进入运行时资源租约检查：每个 Plan step 以自己的 id 持有写租约，同一隔离工作区文件只能被一个运行中步骤写入；冲突返回策略拒绝，不做 last-writer-wins 覆盖或 LLM 自动合并。Worker 尝试结束后都会在 finally 中释放本步骤租约。ToolRegistry 共享后台清理器，project fork 不重复创建线程，最后一个注册表关闭后终止；默认周期 60 秒，可通过 `DEVCLI_RESOURCE_LEASE_CLEANUP_INTERVAL_SECONDS` / `-Ddevcli.resource.lease.cleanup.interval.seconds` 调整。设计说明见 `docs/runtime-resource-lease-design.md`。

副作用执行协议：工具通过 `ToolEffect` 声明 READ_ONLY / LOCAL_CONTEXT / PROJECT_MUTATION / HOST_PROCESS / EXTERNAL_MUTATION，执行管线按 `ToolAccessScope` 强制能力范围；非隔离任务只能使用只读和本地上下文工具，隔离任务允许项目写入与受限命令，但禁止外部副作用。隔离任务的 `execute_command` 与 Pre-Review 强制进入 Docker，不可用时失败且禁止回退主机；默认镜像 `maven:3.9.9-eclipse-temurin-17` 必须提前拉取，容器禁网、只读根文件系统并限制能力与资源。MCP 服务端 readOnly 注解默认不可信，只有本地 `trustReadOnlyAnnotations` 或 `readOnlyTools` 才可授权只读，`deniedTools` 不注册，destructive/openWorld 始终视为外部副作用。Plan 副作用步骤使用 `WorkspaceExecutionSession`；工作区后端默认 `auto`，Git 项目使用原生 worktree 并叠加当前未提交、删除、未跟踪和被忽略文件，非 Git 目录优先使用文件系统级写时复制；Linux 只接受强制 reflink，Windows 11 24H2 / Windows Server 2025 及以上版本只在 ReFS 上启用系统块克隆，其他平台或失败场景回退有界复制，可通过 `DEVCLI_WORKSPACE_BACKEND=git|cow|copy|auto` 指定。worktree 物化后删除排除目录和符号链接，关闭时通过 Git 注销，崩溃残留元数据会在后续创建前 prune。批准后逐文件流式哈希生成 `PatchSet`，只读取变更文件内容；JVM 公平锁和跨进程文件锁共同串行化写前准备、全量冲突预检、应用和 checkpoint 终态。应用中途失败会回滚并报告未恢复路径。工作区创建前清理超过 TTL 且没有活动文件租约的孤儿目录，默认 24 小时，可通过 `DEVCLI_WORKSPACE_ORPHAN_TTL_HOURS` / `-Ddevcli.workspace.orphan.ttl.hours` 调整。写时复制设计见 `docs/filesystem-cow-workspace-design.md`。

Reviewer 前置硬约束：Worker 产物进入 Reviewer LLM 前，`AgentOrchestrator` 委托 `PreReviewVerifier` 执行 Pre-Review Hook；Java 项目优先 `mvn -q -DskipTests test-compile`，无 Maven 时使用 UTF-8 javac 参数文件传递源码清单，避免 Windows 命令行长度限制。两类命令都通过统一命令服务强制进入 Docker 沙箱。验证器独立负责 Java 文件扫描、命令选择、超时、参数文件清理和失败摘要。失败时直接生成 `approved=false` 反馈打回 Worker，不唤醒 Reviewer LLM。

Reviewer 输出必须是可解析 JSON，并包含三层评分：`functional_correctness`、`integration_completeness`、`code_quality`。任一分数低于 `0.6`，或 `functional_correctness < 1.0`，Orchestrator 强制判不通过；非 JSON 文本不再凭“通过”等关键词放行。Pre-Review 会区分“未执行硬检查”和“硬检查实际通过”；Reviewer 发生可重试 LLM 故障时，只有实际执行的硬检查已通过才允许降级接受普通步骤，未执行硬检查继续失败关闭。Reviewer 默认最多 2 轮，可通过 `DEVCLI_TEAM_REVIEWER_MAX_ITERATIONS` / `-Ddevcli.team.reviewer.max.iterations` 调整到 `[1, 8]`；达到上限按可恢复 Reviewer 故障处理，但不绕过硬检查条件。Final integration 保留既有瞬时故障降级策略。

Final integration 只做入口/API/默认参数/跨模块联动胶水；普通步骤失败比例达到 `50%` 时熔断，不让最终步骤强行修补。

失败步骤支持有界在位重做（默认 1 次）：失败步骤保持原 id/依赖在 DAG 原位换思路重做，redo 用尽后保持 FAILED；最终结果显式输出 Reviewer 重试、原位重做、最后失败原因、checkpoint 和人工处理选项。checkpoint 协议版本 7 保存共享 `ExecutionArtifact`、验收方式、验证器、适用节点、pending PatchSet 写前日志、稳定 Planner/Worker/Reviewer 身份、步骤分配、单调消息游标、已消耗的重做次数和重做失败现场；应用前记录 before/after 哈希与原文件备份，恢复时在项目提交锁内按最终哈希提升 COMPLETED、继续 PENDING 或自动回滚。恢复优先重建 checkpoint 中的 Worker 拓扑并保持原步骤绑定，沿用原重做额度，并按上下文 schema 版本注入最近摘要；不持久化完整 SubAgent 对话对象图。旧协议缺失适用节点时迁移为 `FINAL`，缺失验证字段时迁移为人工验收；没有可执行验收标准的未完成 checkpoint 拒绝恢复。对账保存失败、回滚不完整或身份拓扑损坏时停止 resume；高于当前版本的 checkpoint 明确报告不兼容。计划、依赖、验收点、执行产物和恢复元数据原子写入 `~/.devcli/checkpoints/`，全部成功后删除；resume 不恢复 `SessionMemory`。

Side-Git 快照按 `devcli.snapshot.max` / `DEVCLI_SNAPSHOT_MAX` 保留最近快照；每次新建快照后会重写 side-history，只保留最新 N 条。裁剪累计达到阈值或超过最小间隔后，会在时间上限内回收不可达松散对象；默认阈值 100、间隔 24 小时、上限 30 秒，可通过 `DEVCLI_SNAPSHOT_GC_ENABLED`、`DEVCLI_SNAPSHOT_GC_PRUNED_THRESHOLD`、`DEVCLI_SNAPSHOT_GC_MIN_INTERVAL_HOURS`、`DEVCLI_SNAPSHOT_GC_MAX_SECONDS` 调整。

副作用横向信息流：write_file/execute_command 等副作用工具的证据在 `SessionMemory.EvidenceJournal` 中按高重要性保留；普通读取优先压缩或淘汰，失败压缩成 AttemptDigest，使后续步骤持续看到本任务改过哪些文件和已经排除的方案。

职责边界：`SessionMemory` 是当前任务内的运行投影，会按预算压缩且不跨进程；`ExecutionArtifact` 是 Plan / Multi-Agent / checkpoint 的任务终态唯一来源。隔离执行期间的修改只存在工作区内，PatchSet 成功应用后才把 `modifiedResources` 同步到运行态、checkpoint 和 `SessionMemory`。后续依赖步骤读取已批准的主项目成果；同进程靠 `SessionMemory`，跨进程靠 checkpoint `RecoveryState`。

内置核心工具 13 个：`read_file` / `write_file` / `list_dir` / `execute_command` / `create_project` / `search_code` / `grep_code` / `web_search` / `web_fetch` / `save_memory` / `confirm_memory` / `list_memory` / `revert_turn`

Code RAG 检索链路当前为 keyword + semantic + bounded graph → `RRF（倒数排名融合）` → symbol-aware boost → `CrossEncoderReranker（交叉编码器重排）`。Rerank 默认开启，默认指向本地 Docker 暴露的 OpenAI-compatible `/rerank` endpoint；不可用时自动降级回 RRF 结果，不阻断检索。`/index` 按文件批量生成 chunk embedding；批量请求失败或返回数量异常时逐条降级并保留成功 chunk。`ToolRegistry` 会按项目路径复用 `CodeRetriever` / SQLite 连接，项目路径切换时关闭旧连接。索引替换会为变更和删除的 symbol 生成 `negativeFact`，`search_code` 会输出相关失效事实，并通过工具结果强类型旁路载荷把 evidence 与 negativeFact 传给 `SessionMemory`；展示文本不再嵌入结构化 JSON，旧 JSON 与旧展示文本解析只保留历史兼容。keyword 通道保持 SQLite 索引实现，`grep_code` 作为独立实时精确检索工具存在，不替代 `search_code`，用于类名、方法名、配置键、错误文本和固定字符串片段定位。长文档型 definition 查询直接使用 semantic route，避免 keyword fusion 与 reranker 对文档描述引入排序噪声；短符号查询仍保留 precise-first 链路。

量化评测覆盖 RAG、Agent、Memory 和 Context Compression / Long Context。公开集合固定接入 CodeSearchNet Java、SWE-bench Lite、LongMemEval Oracle Cleaned、LongBench v1 和 RULER v1；版本、SHA-256、许可、原始文件边界与官方 harness 由 `Config/public-benchmarks.json` 和数据清单统一记录。RAG 输出 Recall@5、MRR@5、nDCG@5；受控 Agent 输出任务成功率；SWE-bench 只接受官方 Docker harness resolved 结果；LongMemEval 区分 normalized answer hit 代理指标和官方 LLM judge accuracy；LongBench 与 RULER 复用官方 prompt/指标并记录子任务、长度和样本量。受控 Agent benchmark 不暴露 `execute_command`，由隐藏验证器统一检查；订单履约 Saga 场景以只读契约、六个实现模块和 30 项隐藏检查比较单 Agent 与 Planner/Worker/Reviewer，测试工具白名单在隔离 ToolRegistry fork 中保持，真实模型运行由 `devcli.benchmark.saga` 显式启用；2026-07-16 的 `gpt-5.5` 单次有效结果为单 Agent 27/30、Planner/Worker/Reviewer 30/30，后者耗时为前者 3.76 倍；生产 Pre-Review 仍强制 Docker。原始报告位于 `target/benchmark-reports/` 和 `target/agent-benchmark/`，聚合报告写入 `Data/processed/` 与 `Data/manifest/`；详细方法见 `docs/benchmark-evaluation.md`。

MCP 动态工具：`mcp__{server}__{tool}`（+ resources 虚拟工具）

模型调用可靠性链路：Anthropic 与全部 OpenAI-compatible Provider 统一抛出 `LlmException`，错误码覆盖认证、限流、过载、超时、网络、参数、上下文超限、内容过滤、服务端和响应格式错误；只对限流、过载、超时、网络和 5xx 做指数退避有界重试，流式内容开始输出后禁止重试。OpenAI-compatible 工具调用流同时兼容标准增量片段、累积快照和完整字段重复发送，避免工具名或完整 JSON 参数重复拼接。SubAgent 错误消息保留标准错误码和 `retryable` 标记，Orchestrator 不再依赖具体网络错误文案判断瞬时故障。默认 3 次、500ms 初始退避、8s 上限、0.2 jitter，可通过 `DEVCLI_LLM_RETRY_*` 或对应系统属性调整。

工具调用可靠性链路：LLM 先按 reasoning 说明目标、工具选择和参数来源；工具定义使用 JSON Schema 强约束类型、必填项、枚举值和未知字段；`ToolRegistry` 通过 `ToolExecutionPipeline` 分阶段执行取消、工具存在性、能力范围、Skill 权限、参数校验、HITL、审计、策略和结果尺寸治理；并行工具线程显式继承能力范围、资源租约和 Skill buffer 快照，项目 fork 复制 `SkillContextBuffer`，不共享可变状态；工具结果使用 `ToolStatus`、`ToolErrorCode` 和 retryable 结构化表达；内置 Provider 可通过结构化执行器直接返回状态，参数错误、策略拒绝、命令非零退出、超时和取消不再先压成普通文本；ReAct、Plan、SubAgent 的重复错误熔断不再依赖结果文本关键词；执行前通过 `json-schema-validator` + 本地兜底校验内置工具和 MCP 工具参数，失败以 `工具参数校验失败` 回传模型修正；默认只注入内置核心工具和已激活 MCP 工具；ReAct、Plan 和 Multi-Agent turn 开始前会按当前用户输入预激活匹配到的 MCP 工具；`search_tools` 使用工具索引缓存，MCP 工具变更后自动失效，命中 MCP 工具后激活到后续工具定义；未知工具会提示先调用 `search_tools`；危险工具继续走 HITL / Policy / AuditLog；工具参数通过稳定语义指纹参与停滞判断，JSON 字段顺序、查询大小写、Unicode 等价字符和冗余空白不会绕过重复检测；正则 pattern 保持大小写敏感，避免错误缓存命中；成功且无图片的 READ_ONLY 工具结果按会话短期缓存，任何非只读工具执行和项目路径切换都会清空缓存；MCP 工具结果被截断或落盘预览时会标记折叠分类；工具结果进入 SessionMemory，最终回答必须用工具证据闭环。

执行内核补强：`AgentExecutionEngine` 按原始 `tool_call_id` 对结果去重、拒绝未知结果、补齐缺失结果并恢复原始顺序；并行危险调用的审批输入通过共享公平锁串行化，项目 fork 复用同一审批仲裁；工具声明 `COOPERATIVE` 或 `INTERRUPT_ONLY` 取消能力；重复提醒与硬熔断分别记录结构化动作。

## 仓库结构

```
src/main/java/com/devcli/
├── agent/       Agent.java, PlanExecuteAgent.java, PlanTaskBatchExecutor.java, PlanTaskExecutionResult.java, SubAgent.java, AgentOrchestrator.java, MultiAgentBatchExecutor.java, PlanTaskWorkspaceExecutor.java, WorkspaceCommitCoordinator.java
├── cli/         Main.java, CliCommandParser.java, PlanReviewInputParser.java
├── browser/     BrowserSession, BrowserGuard, SensitivePagePolicy
├── llm/         AnthropicClient, GLMClient, DeepSeekClient, StepClient, KimiClient, OpenAiClient
├── context/     ContextProfile, ContextMode, TokenUsageFormatter
├── memory/      MemoryManager, ConversationHistoryCompactor, LongTermMemory, TaskLedger
├── plan/        Planner, ExecutionPlan, ExecutionGraph, ExecutionArtifact, Task
├── rag/         CodeIndex, CodeRetriever, VectorStore, CodeChunker
├── lsp/         LspManager, LspDiagnosticFormatter
├── prompt/      PromptAssembler, PromptContext, PromptRepository
├── image/       ImageReferenceParser
├── runtime/     RunCoordinator + store/ (RunStore) + api/ + task/
├── session/     SessionTree, SessionTreeService
├── config/      ConfigResolver, DevCliConfig
├── snapshot/    SideGitManager, SnapshotService
├── workspace/   IsolatedWorkspace, WorkspaceExecutionSession, PatchSet, WorkspaceBackend, ProjectCommitCoordinator
├── tool/        ToolRegistry
├── mcp/         McpClient, McpServerManager, transport/, resources/, mention/
├── hitl/        HitlToolRegistry, ApprovalPolicy, TerminalHitlHandler
├── web/         SearchProvider, WebFetcher, HtmlExtractor, NetworkPolicy
├── policy/      PathGuard, CommandGuard, AuditLog
├── skill/       SkillRegistry, SkillPathMatcher, SkillContextBuffer, SkillIndexFormatter
└── render/      Renderer, InlineRenderer, PlainRenderer, RendererFactory
```

Runtime API 只绑定 `127.0.0.1`，请求线程与 Agent turn 执行线程隔离；turn 执行池默认 2 线程 / 64 队列，过载返回 `429 runtime_busy`；`KeyedSerialExecutor` 保证同一 thread 串行。CLI、Runtime API 和后台任务通过 `RunCoordinator` 写入同一 `RunStore`，后台任务不再维护独立状态表；旧 `tasks.db` 只读导入 `runtime.db`。CLI `/session` 与 Runtime branch 共用持久事件树，切换分支只重建 Agent 历史，不恢复工作区；`/branch` 仅为兼容别名。检查点和会话投影是事件日志的可重建缓存。模型 reasoning/content delta、模型上下文、工具调用、工具结果和 turn/checkpoint 生命周期统一使用强类型 `RunEvent`；`AgentExecutionEngine` 是领域事件出口，Runtime API 使用 schema v2 JSON 投影。交互、后台任务和无头 turn 使用运行级 `RunContext` 隔离项目路径、取消令牌和资源生命周期。

执行状态同样使用强类型 `RunEvent`，由执行内核输出 `THINKING`、`TOOL_EXECUTING`、`TOOL_RESULTS_PAIRED` 以及完成、取消、预算退出、迭代上限和失败终态，供 CLI 与 Runtime 投影统一消费。

受控 Hook 生命周期：`AgentExecutionEngine` 在 ReAct、Plan task 和 SubAgent 共用 agent/turn/message/tool execution 四层幂等生命周期。Hook 配置从 `~/.devcli/hooks.json` 与项目 `.devcli/hooks.json` 按 id 合并，或由 `DEVCLI_HOOKS_FILE` / `-Ddevcli.hooks.file` 指定；最多 64 条。Hook 只能调用 ToolRegistry 已注册工具，不直接开放 shell/HTTP 执行器；READ_ONLY / LOCAL_CONTEXT 强制在只读能力范围执行，其他 ToolEffect 必须显式 `allowSideEffects`、使用已启用的 `HitlToolRegistry`，且目标工具必须有逐次审批策略，否则拒绝。所有调用继续经过参数校验、HITL、策略、审计和当前工作区能力范围，不允许 Hook 提升 Plan/SubAgent 的权限。`warn` 失败只记录警告，`required` 失败进入 Agent 统一失败出口；异常和取消出口都会按 message → turn → agent 顺序闭合生命周期。模板位于 `Config/hooks.example.json`。

启动与 inline 渲染当前约定：

- 交互渲染器只保留 Inline 与 Plain。`DEVCLI_RENDERER=lanterna|tui` 和旧 `DEVCLI_TUI=true` 在兼容期映射到 Inline；`Main` 不再调用 `TuiBootstrap`。

- 开屏 Banner 使用无右边框的简洁布局，避免 CJK/ANSI 字宽导致右侧竖线错位；Phase 22 后默认是 π 主题彩色 logo + Qoder 风格首屏，只展示模型、MCP、Skill、ReAct 状态和三条 getting-started tips，不再把 MCP server 明细刷成启动日志。
- inline 模式使用 JLine 4 的 LineReader 编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`。
- 默认 CLI 启动路径应先 `Renderer.start()` 并初始化底部 dock；inline 首屏不要在 `readLine` 前裸写 stdout，而是通过 `InlineRenderer.installStartupScreen(...)` 挂到 `LineReader.CALLBACK_INIT`，首次进入输入时用 `printAbove` 一次性显示完整 Banner + tips，避免 logo 被 LineReader 首次重绘滚出可视区域。
- `BottomStatusBar` 现在是 JLine `Status` 托管的底部 dock：由 JLine 维护滚动区域和状态行位置，不再手写 `\n` / `moveUp` / `CLEAR_TO_EOS` 清屏。输入期会把 LineReader 光标定位到 dock 上方一行，让 `*` 输入行和 Status 同处底部区域；dock 保留两类信息：上层模式 + MCP/Skill 摘要，下层 Auto Model / model / phase / ctx 百分比与 token / cost / elapsed / cwd。
- 普通任务提交后，`Main` 会把本轮原始用户 prompt 以暗色整行块写回 transcript：输入态左提示仍是 `* `，提交回显左提示改为 `>`；单行输入只占一行，不额外追加空白行。随后再展开 MCP resource / 本地 `@path` 并进入 Agent；不要只依赖 JLine 提交行残留，否则 activity 重绘或 dock 刷新可能让用户提示词从可见历史里消失。
- ReAct LLM 调用期间，inline renderer 使用固定高度 live thinking 区动态显示 `Thinking...` 和灰色竖线 reasoning 预览；该区域只能清理自己刚打印的几行，不能用独立 JLine `Display.update()` / `CLEAR_TO_EOS` 向上覆盖 transcript。content 或 tool call 开始前先清掉 live 区，再把完整 reasoning 引用块落到正文区，正文回答用低调标记起始，不再刷强标题。
- 交互期输出应优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner`、`AgentOrchestrator` 都支持把输出流接到 inline renderer，避免直接争抢 stdout。`CodeIndex` 的索引进度通过 `ProgressListener` 注入，`/index` 应绑定到当前 renderer 输出流。
- Phase 22 开始，`InlineRenderer` 可绑定当前 `LineReader`；当 `LineReader.isReading()` 为 true 时，`Renderer.stream()` 的完整行输出优先通过 `LineReader#printAbove` 显示在输入行上方，未绑定 / 非读取态 / 测试路径回退到原 `PrintStream`。
- ReAct 正常结束后不再把 `📊 Token: ...` 打进正文区；token/cost/elapsed 会保留在底部强状态行，phase 回到 `idle`。
- 默认 CLI 启动路径应尽早建立 `Terminal -> LineReader -> Renderer`，启动 Banner、模型加载、MCP 启动、Skill summary、ReAct 提示和退出提示都应走 `Renderer.stream()`；除 fatal bootstrap / runtime API / legacy TUI 降级外，不要在交互主路径新增裸 `System.out.println`。
- 启动期 MCP 不得阻塞首屏：CLI 默认最多等待 8 秒（`DEVCLI_MCP_STARTUP_WAIT_SECONDS` / `-Ddevcli.mcp.startup.wait.seconds` 可调），超时后保留未完成 server 为 `STARTING` 并后台继续初始化；`/mcp` 查看最新状态。Windows stdio transport 必须按 `PATH` / `PATHEXT` 解析 `.cmd` / `.bat` 包装器，不能把无扩展名的 npm shell 脚本直接交给 ProcessBuilder。
- `LineReader` 使用 `DevCliHighlighter` 做输入实时高亮：slash 命令、`@` 引用、`@image:`、`@clipboard`、敏感词和明显危险 shell 片段会在编辑阶段被标记；不要把这类视觉提示混入最终提交文本。
- `LineReader` 使用 `DevCliCompleter` 做上下文补全：`/model` provider、`/mcp` 子命令与 server、`/skill` 子命令与 skill name、`/task` / `/browser` / `/snapshot` 子命令、`@image:` 本地路径、本地 `@path` 和 MCP resource `@server:uri` 引用都应从同一个 completer 出口维护；`/help` 必须由 CLI 直接解析并显示同一份命令清单。
- 普通用户输入进入 Agent 前会先展开 MCP resource mention，再由 `LocalPathMentionExpander` 展开本地 `@path`：文件会内联为 `<file>` 块，目录会内联为 `<directory>` 列表；绝对路径或符号链接逃逸项目根时保持原文不展开。
- mention 展开会先按完整请求剩余 Token 预算决定内联还是生成 `<file_reference>` 快照；内容分析、总结、定位、精确错误等请求由 `ContextReferenceGuard` 在首轮强制 `read_file`。后续用户使用“里面/该文件/附件”等指代继续追问时，会复用最近引用；文件名、路径、大小、哈希等元数据问题不强制读取。错误路径、读取失败或快照哈希变化累计两次后失败关闭，禁止无证据推理
- `LineReader` 使用 `DevCliHistory` 持久化输入历史到 `~/.devcli/history/input.history`；如果 `devcli.history.file` / `DEVCLI_HISTORY_FILE` 指向目录，也会自动使用该目录下的 `input.history`，避免把目录当文件读；默认忽略空白、重复、明显密钥/Bearer、base64 图片和超长输入，用户可用 `/history clear` 清空本机输入历史。plain 与 inline 的 HITL 后续输入复用主 LineReader，禁止再创建竞争读取 `System.in` 的独立入口。
- 重定向输入默认按 UTF-8 解码；旧式控制台可用 `DEVCLI_TERMINAL_ENCODING` 覆盖。ANSI 能力被误判时可用 `DEVCLI_TERMINAL_FORCE_ANSI=true` 强制使用 xterm-256color 终端类型。
- JLine 交互升级计划记录在 `docs/phase-22-jline-interaction-upgrade.md`。

## 关键行为约束（Agent 必读）

### Memory

- 记忆只分两层：`SessionMemory` 是当前任务共享的短期记忆，`LongTermMemory` 是跨会话长期记忆。Conversation History / Summary 属于上下文治理，`RuleContext` 属于规则系统，均不算记忆层
- `SessionMemory` 通过 `accept(SessionEvent)` 统一接收工具结果、用户确认和步骤变化；内部 `WorkState` 按键覆盖或按步骤状态机推进，`EvidenceJournal` 按 CRITICAL / FAILURE / MILESTONE / ORDINARY / REGENERABLE 分级。`beginTask/endTask` 管理明确 Plan 任务边界：任务结束后保留最终投影，到下一个 taskId 开始时清理；ReAct 仍以 `/clear` 作为显式边界
- SessionMemory Prompt 使用单一硬 Token 预算：先保留任务状态、修改文件和失败摘要，再按 importance/sequence 注入关键事件和工具证据；关键原文超限时折叠成规范化引用。Multi-Agent 共享单一实例，真实 agentId、stepId 和单调 sequence 会参与证据归属与迟到计划/步骤事件拒绝；`ExecutionArtifact` 仍是任务终态唯一来源
- `CompactionSummaryCache` 只缓存压缩预摘要，不是记忆；`RuleContext` 加载 `DEVCLI.md` 和 `/rule add` 强约束，支持 `/rule list`、`/rule remove`，旧 `pinned_facts.json` 只列为待分类迁移候选，不会静默当成规则。稳定事实使用 `/save` 写入 `LongTermMemory`，旧 `/save --pin` 仅保留废弃提示
- 长期记忆主要通过 `/save` 或用户明确要求保存；中英文显式记忆意图、少量稳定个人属性和多次重复出现的稳定项目/偏好事实可由策略自动保存
- 长期记忆只保存跨会话稳定事实，不保存临时指令；显式保存请求如果内容仍然明显临时或低复用，需要确认而不是直接落库；中英文临时表达、敏感信息和模糊新个人状态必须确认或跳过；与 SessionMemory 关键事件语义重复的长期记忆在 prompt 注入时会被抑制；普通 turn 只注入达到最低分数、与第一名差距未超限且数量受限的查询相关记忆，长期记忆目录快照仅在统一意图分类器识别出查看、列出或审计意图时注入
- 用户显式要求忽略记忆（如“别管记忆”“忽略记忆”）时，本会话不注入长期记忆、通用 SessionMemory 和角色裁剪后的 SessionMemory
- 反馈类长期记忆按 `FEEDBACK` 类型落库，不混入普通 `FACT`
- 长期记忆统一记录 `schemaVersion`、主题内 `revision`、`expiresAt` 和结构化 `MemoryEvidence`；证据包含 confidence、sourceQuote、reasoning、reviewState、conflictsWith；HIGH 至少需要 5 字符来源引用，MEDIUM 需要非空引用，否则领域层自动降级。显式写入默认 REVIEWED，策略自动写入默认 UNREVIEWED，REJECTED 保留审计但不进入召回。命中 subject 的等价事实直接去重，只有值变化才 supersede；工具可通过 `CurrentStateObservationSideChannel(subject,value,evidence)` 泛化当前状态推翻，Maven/Gradle 文本检测仅作兼容回退
- 敏感 `save_memory` 返回一次性 `confirmation_id`；模型必须先询问用户，再调用 `confirm_memory(save_redacted|save_edited|cancel)`。确认 id 默认 10 分钟过期且只能使用一次，最终仍统一经过明文脱敏边界
- `/memory organize` 只生成整理计划；`/memory organize apply` 仍由程序重新计算风险，只自动应用同主题、同类型、全部未审核、覆盖完整且计划置信度不低于 0.9 的合并。已审核条目、跨主题、跨类型、部分覆盖、REVIEW 和 REJECT 候选不得自动应用，只在本次报告中标记为需要人工复核；记忆正文按 JSON 数据载荷交给整理模型，不作为指令
- `ConversationHistoryCompactor` 是唯一治理 LLM messages 窗口的压缩点；压缩前先走第 0 层 `microcompact`（单条超大消息头尾截断；旧轮次 tool_result 按 toolCallId 成批落盘并替换为 `<microcompact_boundary>` 引用；不删消息、保 tool_call 配对），扛不住再摘要。首次摘要使用 Map-Reduce；后续固定保留九段，模型只提出受限生命周期操作，程序负责覆盖、完成迁移和删除；默认每 5 次成功压缩执行生命周期 GC，不再二次压缩旧摘要。摘要写回 history 前必须经过 `CompactionSemanticGuard`；恢复区会按 storedPath/toolCallId 去重 microcompact 工具引用
- `CompactionSummaryCache` 维护当前进程内会话预摘要，自动压缩时优先复用覆盖同一消息指纹且未过期的预摘要；已有预摘要覆盖当前历史前缀时，只用旧摘要和新增消息生成完整替代摘要；预摘要默认 30 分钟过期，不写长期记忆
- RAG 每次检索保存不含代码正文的分阶段审计记录，覆盖 keyword / semantic / graph 候选、RRF 融合、rerank、最终选择和降级状态；普通 CLI 会话归档默认关闭，启用后 ReAct 保存脱敏模型消息，Plan / Team 保存顶层输入输出，`/history clear` 同时删除归档
- 压缩成功后会插入 `[压缩后恢复上下文]` 消息：恢复段按最近读写文件、未完成子任务状态、关键工具结果引用、RAG 证据 epoch 和 MCP 工具状态分节；恢复内容经统一预算与行级去重后注入，Multi-Agent 会按 Planner / Worker / Reviewer 角色裁剪；SkillContextBuffer 追加已加载 Skill 与 allowedTools 状态
- 压缩边界 `<compact_boundary>` 会记录已加载 Skill、RAG epoch、MCP 工具快照和压缩后恢复入口状态；RAG epoch 合并当前会话已命中证据与当前项目全局索引版本，MCP 工具快照按 server 记录工具数量、schema 指纹和生命周期版本
- `McpServerManager` 会记录本进程 MCP 连接事件：STARTING / READY / ERROR / DISABLED / RECONNECTING / TOOLS_CHANGED，事件携带 server、状态、生命周期版本、工具数量和消息；启动失败后会后台自动重连，默认最多 3 次
- MCP 工具发现缓存记录 server、生命周期版本、工具数量、工具名、schema 指纹和发现时间；disable 不清除上一轮发现元数据
- MCP `tools/call` 会携带 `_meta.progressToken`，同 token 的 `notifications/progress` 会汇总进工具结果文本
- MCP 工具结果进入尺寸治理后会标记折叠分类：截断输出为 `INLINE_TRUNCATED`，落盘预览为 `PERSISTED_PREVIEW`
- 结构化滚动摘要超过字符上限时执行确定性生命周期 GC；稳定决策和未解决事项不因压缩次数删除，仍超限时保留并告警，不交给 LLM 二次改写
- `search_code` 结果中的结构化 negativeFact 携带 `oldSymbolVersion` 时，`SessionMemory` 即时清理对应的失效 RAG 证据；旧文本格式保留兼容解析
- `TaskLedger` 作为 `SessionMemory.WorkState` 的计划执行进度投影，不进 conversationHistory，压缩不触碰它；Plan 和 Multi-Agent 通过统一事件入口更新，让长 plan 压缩后仍能看到当前 step / 已完成 / 待执行 / 失败。任务完成或失败时记录结构化 `modifiedFiles` 和短 `resultSummary`，不依赖完整结果正文。
- prompt cache（各模型自动前缀缓存）：system prompt 每轮刷新易变段（memory / workingMemory）以让 LLM 看最新状态，代价是自动前缀缓存只命中固定头部（base/personality/mode/approval）；`PromptAssembler` 把稳定段（Sticky）前置、易变段后置以尽量延长可缓存前缀，`PromptAssemblerTest` 锁定"固定头部不被动态内容污染"契约。进一步延长命中（动态段全后移 / 移出 system 到尾部 message）需 prompt 评估 + 真实 API 命中率 A/B，未做

### HITL + 策略层

- 拦截顺序：HitlToolRegistry → ToolRegistry → PathGuard/CommandGuard
- 用户无法批准策略拒绝的请求
- 带 destructive/openWorld annotations 的 MCP 工具必须逐次 HITL 审批，不复用 tool/server 级全部放行缓存
- PathGuard 强制路径限定在项目根内
- CommandGuard 是辅助黑名单，不是主防线

### Plan 审阅交互

- `Enter` 执行 / `Ctrl+O` 展开 / `ESC` 取消 / `I` 补充重规划
- 方向键不应被误判为 ESC
- 涉及改动要连 raw mode 和回退路径一起看

### 并行工具

- 三条路径都走 `executeTools()`，不手写 for-loop
- 默认最多 4 个并发，结果保持原始顺序
- 参数非法时不进入真实执行，返回可读校验错误给 LLM 纠偏

### Web + Browser

- 已知 URL 先 `web_fetch`，SPA/防爬墙 fallback 到 Chrome DevTools MCP
- 浏览器读取优先 `take_snapshot`，不默认 `take_screenshot`
- 公开页面不要提前切 shared 模式

### Skill

- system prompt 索引段注入三处提示词，上限 20 个 / 4KB
- Skill frontmatter 支持 `allowedTools`、`context: inline|fork` 和 `paths`；`paths` 会按当前输入或任务文本中的项目相对路径条件激活，`context: fork` 会在加载结果和恢复段标记，提示优先放入 fork / 子任务上下文
- system prompt 索引段注入三处提示词，上限 20 个 / 4KB；启用 Skill 按使用频率优先、名称次序排序
- `load_skill` → SkillContextBuffer → 下一轮 user message 前置注入，并记录本进程内使用频率
- `allowedTools` 为空表示不启用 Skill 工具限制；已加载 Skill 声明 `allowedTools` 时，后续工具调用必须命中当前 SkillContextBuffer 的白名单，`/clear` 清空该状态；压缩后恢复会保留已调用 Skill 的 context、allowedTools 和内容摘要

## 修改时的硬规则

### 1. 改行为 → 同步文档

`AGENTS.md` / `README.md` / `ROADMAP.md`（仅状态变化时）

### 2. 改命令入口 → 联动

`Main.java` + `CliCommandParser.java` + 测试 + `README.md` + `AGENTS.md`

未识别的 `/xxx` 在 CLI 层直接报"未知命令"，不回退给 Agent。

### 3. 改 Plan 审阅交互 → 联动

`Main.java` + `PlanReviewInputParser.java` + 测试 + 手工验证

### 4. 改工具集 → 联动

`ToolRegistry.java` + Agent/PlanExecuteAgent/SubAgent 提示词 + 可能 Planner 提示词 + 文档

### 5. 改模型/接口 → 联动

对应 Client + `LlmClientFactory.java` + `.env.example` + 文档

### 5.1 改 Embedding → `EmbeddingClient` + `VectorStore` + `.env.example` + 文档

### 5.2 改 Web/搜索 → `web/` 相关 + ToolRegistry + `.env.example` + 文档 + 测试

### 5.3 改 Memory → `MemoryManager` + `LongTermMemory` + `TokenBudget` + 测试 + 文档

### 5.4 改 HITL/策略 → `policy/` + ToolRegistry + HitlToolRegistry + 提示词 + `.env.example` + 文档 + 测试

### 5.5 改 MCP → `mcp/` + ToolRegistry + HITL + AuditLog + 提示词 + 文档 + 测试

### 6. 不提交 `.env` / 真实 API Key / `target/` 产物

### 7. 保持代码可读性，不过度抽象

## 验证路径

| 场景 | 命令 |
|------|------|
| 命令解析 | `mvn test -Dtest=CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest` |
| DAG/Plan | `mvn test -Dtest=ExecutionPlanTest` |
| Multi-Agent | `mvn test -Dtest=AgentRoleTest,AgentMessageTest,AgentOrchestratorTest` |
| TUI/终端 | `mvn test -Pphase16-smoke` |
| RAG | `mvn test -Dtest=CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest` |
| 常规回归 | `mvn test -Pquick` |

## 给新线程的导航

1. 先看本文件 → 2. `README.md` → 3. `Main.java` → 4. 按任务进入对应模块

| 任务类型 | 先看 |
|----------|------|
| CLI 命令 | Main.java + CliCommandParser.java |
| 规划/DAG | PlanExecuteAgent.java + Planner.java + ExecutionPlan.java |
| 工具调用 | ToolRegistry.java + Agent.java |
| 模型/API | llm/*Client.java + LlmClientFactory.java |
| RAG | CodeRetriever.java + CodeIndex.java + VectorStore.java |
| Multi-Agent | AgentOrchestrator.java + MultiAgentBatchExecutor.java + SubAgent.java |
| MCP | McpServerManager.java + McpClient.java |
| TUI/渲染 | render/Renderer.java + RendererFactory.java |

## 当前已知边界

以下在路线图但未交付：容器/VM 级完整系统沙箱、MCP sampling + server 自动重启；当前隔离命令已使用受限 Docker，但 Docker daemon 仍属于主机高权限基础设施。MCP OAuth 暂不纳入个人使用优先级

不要把 `ROADMAP.md` 中"将来要做"误读成"现在已有"。

## 持续维护约定

形成稳定协作规则时直接补进本文件，不要只留在聊天记录里。详细实现细节补到 `docs/agents-reference.md`。
