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
- 默认 LLM provider 是 `anthropic`；至少一个 API Key：`ANTHROPIC_AUTH_TOKEN`（Anthropic Messages 兼容，可配 `ANTHROPIC_BASE_URL` / `ANTHROPIC_MODEL`）/ `OPENAI_API_KEY` / `GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY`

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

三条主执行路径，共享 ToolRegistry / MemoryManager / SnapshotService；ReAct、Plan task、SubAgent 的单轮控制流统一由 `AgentExecutionEngine` 承载，负责取消、预算、LLM 调用、工具消息协议和异常出口，各路径只实现差异钩子：

| 路径 | 入口 | 触发 |
|------|------|------|
| ReAct | `Agent.java` | 默认模式 |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` |
| Multi-Agent | `AgentOrchestrator.java` | `/team` |

Multi-Agent 中 Planner 负责拆解 DAG，Worker 负责实现子任务，Reviewer 负责硬检查通过后的质量审查。

Plan 与 Multi-Agent 的 DAG 就绪判断和图结构校验统一使用 `ExecutionGraph`：普通节点只在依赖全部完成后执行，最终集成节点可在依赖进入完成或失败终态后执行；缺失依赖和环会在执行前拒绝。Plan `Task`、Multi-Agent `ExecutionStep` 和 checkpoint 共用 `ExecutionArtifact`，状态、输出、摘要、修改资源、错误、尝试次数和时间戳不再分散存储。Planner 必须输出 `acceptance_criteria`；Orchestrator 会把验收点前置注入 Worker，并要求 Reviewer 用 `criteria_results` 逐条验证。验收点 `severity` 会随计划和 checkpoint 固化；critical/high 验收点失败或缺少覆盖时强制不通过。

Multi-Agent Planner 输出前后允许存在说明文本，编排器会提取完整 JSON 对象；解析失败、图结构无效或出现阻塞后续实现的空工作区纯检查步骤时，清空 Planner 历史并携带失败原因有界修复，默认 2 次，可通过 `DEVCLI_TEAM_PLANNER_REPAIR_MAX_ATTEMPTS` / `-Ddevcli.team.planner.repair.max.attempts` 调整。空工作区是合法状态，目录或文件存在性检查应并入实现步骤并写明“若不存在则创建”。Worker 最终文本为空但本轮存在结构化 `SUCCESS` 工具证据时，编排器生成执行摘要并继续 Reviewer；没有成功工具证据时先进行一次强制执行协议修复，代码任务必须调用 `write_file` 并最小验证，读取或分析任务必须取得真实工具证据；该请求通过 `LlmClient.ToolChoice.REQUIRED` 强制 Provider 选择工具，Anthropic 使用 `tool_choice.type=any`，OpenAI-compatible 使用 `tool_choice=required`，修复后仍无成功证据才判失败。

Multi-Agent 的 WorkingMemory 按角色注入隔离视图：Planner 只看任务状态 + 会话关键事件，不看工具原文证据；Worker 看完整任务状态 + 关键事件 + 工具证据；Reviewer 只看任务状态 + 工具证据，避免把会话事件误当验收依据。

Multi-Agent 并行批次使用 `SubAgent.ForkContext` 共享冻结 system prompt 前缀、exact tool definitions 快照、skill body 快照和 fork fingerprint；每个子任务只追加自己的 user 后缀，避免并行 Worker / Reviewer 因历史或动态工具差异破坏 prompt cache 命中。

并行 Worker 写文件时，隔离 ToolRegistry 内的 `write_file` 仍进入运行时资源租约检查：每个 `/plan` task 或 `/team` step 以自己的 id 持有写租约，同一隔离工作区文件只能被一个运行中步骤写入；冲突返回策略拒绝，不做 last-writer-wins 覆盖或 LLM 自动合并。`/plan` task 和 `/team` Worker 尝试结束后都会在 finally 中释放本步骤租约。ToolRegistry 共享后台清理器，project fork 不重复创建线程，最后一个注册表关闭后终止；默认周期 60 秒，可通过 `DEVCLI_RESOURCE_LEASE_CLEANUP_INTERVAL_SECONDS` / `-Ddevcli.resource.lease.cleanup.interval.seconds` 调整。设计说明见 `docs/runtime-resource-lease-design.md`。

副作用执行协议：工具通过 `ToolEffect` 声明 READ_ONLY / LOCAL_CONTEXT / PROJECT_MUTATION / HOST_PROCESS / EXTERNAL_MUTATION，执行管线按 `ToolAccessScope` 强制能力范围；非隔离任务只能使用只读和本地上下文工具，隔离任务允许项目写入与受限命令，但禁止外部副作用。隔离任务的 `execute_command` 与 Pre-Review 强制进入 Docker，不可用时失败且禁止回退主机；默认镜像 `maven:3.9.9-eclipse-temurin-17` 必须提前拉取，容器禁网、只读根文件系统并限制能力与资源。MCP 服务端 readOnly 注解默认不可信，只有本地 `trustReadOnlyAnnotations` 或 `readOnlyTools` 才可授权只读，`deniedTools` 不注册，destructive/openWorld 始终视为外部副作用。`/plan` 副作用任务与 `/team` 副作用步骤使用 `WorkspaceExecutionSession`；工作区后端默认 `auto`，Git 项目使用原生 worktree 并叠加当前未提交、删除、未跟踪和被忽略文件，非 Git 目录使用有界复制，可通过 `DEVCLI_WORKSPACE_BACKEND` 指定。worktree 物化后删除排除目录和符号链接，关闭时通过 Git 注销，崩溃残留元数据会在后续创建前 prune。批准后逐文件流式哈希生成 `PatchSet`，只读取变更文件内容；JVM 公平锁和跨进程文件锁共同串行化写前准备、全量冲突预检、应用和 checkpoint 终态。应用中途失败会回滚并报告未恢复路径。工作区创建前清理超过 TTL 且没有活动文件租约的孤儿目录，默认 24 小时，可通过 `DEVCLI_WORKSPACE_ORPHAN_TTL_HOURS` / `-Ddevcli.workspace.orphan.ttl.hours` 调整。

Reviewer 前置硬约束：Worker 产物进入 Reviewer LLM 前，`AgentOrchestrator` 委托 `PreReviewVerifier` 执行 Pre-Review Hook；Java 项目优先 `mvn -q -DskipTests test-compile`，无 Maven 时使用 UTF-8 javac 参数文件传递源码清单，避免 Windows 命令行长度限制。两类命令都通过统一命令服务强制进入 Docker 沙箱。验证器独立负责 Java 文件扫描、命令选择、超时、参数文件清理和失败摘要。失败时直接生成 `approved=false` 反馈打回 Worker，不唤醒 Reviewer LLM。

Reviewer 输出必须是可解析 JSON，并包含三层评分：`functional_correctness`、`integration_completeness`、`code_quality`。任一分数低于 `0.6`，或 `functional_correctness < 1.0`，Orchestrator 强制判不通过；非 JSON 文本不再凭“通过”等关键词放行。

Final integration 只做入口/API/默认参数/跨模块联动胶水；普通步骤失败比例达到 `50%` 时熔断，不让最终步骤强行修补。

失败步骤支持有界在位重做（默认 1 次）：失败步骤保持原 id/依赖在 DAG 原位换思路重做，redo 用尽后保持 FAILED。checkpoint 协议版本 3 保存共享 `ExecutionArtifact` 和 pending PatchSet 写前日志；应用前记录 before/after 哈希与原文件备份，恢复时在项目提交锁内按最终哈希提升 COMPLETED、继续 PENDING 或自动回滚。写前日志目录和备份限制为当前所有者访问，超过 TTL 且没有对应 checkpoint 的孤儿日志会清理。对账保存失败、回滚不完整时停止 resume；高于当前版本的 checkpoint 明确报告不兼容，版本 1/2 保持兼容。计划、依赖、验收点和执行产物原子写入 `~/.devcli/checkpoints/`，全部成功后删除；resume 不恢复 WorkingMemory / 会话记忆。

Side-Git 快照按 `devcli.snapshot.max` / `DEVCLI_SNAPSHOT_MAX` 保留最近快照；每次新建快照后会重写 side-history，只保留最新 N 条。裁剪累计达到阈值或超过最小间隔后，会在时间上限内回收不可达松散对象；默认阈值 100、间隔 24 小时、上限 30 秒，可通过 `DEVCLI_SNAPSHOT_GC_ENABLED`、`DEVCLI_SNAPSHOT_GC_PRUNED_THRESHOLD`、`DEVCLI_SNAPSHOT_GC_MIN_INTERVAL_HOURS`、`DEVCLI_SNAPSHOT_GC_MAX_SECONDS` 调整。

副作用横向信息流：write_file/execute_command 等副作用工具的证据在 `WorkingMemory.recentToolResults` 中优先保留、不被只读操作（read_file/search）的 FIFO 淘汰挤出，使后续步骤/轮次持续看到"本会话改过哪些文件"。这是改进既有工具证据淘汰策略实现的，未新增重复的文件账本维度。

职责边界：`WorkingMemory` 是当前会话内的副作用证据缓存，会淘汰且不跨进程；`ExecutionArtifact` 是 Plan / Multi-Agent / checkpoint 的任务终态唯一来源。隔离执行期间的修改只存在工作区内，PatchSet 成功应用后才把 `modifiedResources` 同步到运行态、checkpoint 和 WorkingMemory。后续依赖步骤读取已批准的主项目成果；同进程靠 WorkingMemory，跨进程靠 checkpoint `RecoveryState`。

内置核心工具 12 个：`read_file` / `write_file` / `list_dir` / `execute_command` / `create_project` / `search_code` / `grep_code` / `web_search` / `web_fetch` / `save_memory` / `list_memory` / `revert_turn`

Code RAG 检索链路当前为 keyword + semantic + bounded graph → `RRF（倒数排名融合）` → symbol-aware boost → `CrossEncoderReranker（交叉编码器重排）`。Rerank 默认开启，默认指向本地 Docker 暴露的 OpenAI-compatible `/rerank` endpoint；不可用时自动降级回 RRF 结果，不阻断检索。`/index` 按文件批量生成 chunk embedding；批量请求失败或返回数量异常时逐条降级并保留成功 chunk。`ToolRegistry` 会按项目路径复用 `CodeRetriever` / SQLite 连接，项目路径切换时关闭旧连接。索引替换会为变更和删除的 symbol 生成 `negativeFact`，`search_code` 会输出相关失效事实，并附带结构化 `RAG_EVIDENCE_JSON` 载荷供 WorkingMemory 清理旧 RAG 证据，展示文本变化不应影响证据提取。keyword 通道保持 SQLite 索引实现，`grep_code` 作为独立实时精确检索工具存在，不替代 `search_code`，用于类名、方法名、配置键、错误文本和固定字符串片段定位。长文档型 definition 查询直接使用 semantic route，避免 keyword fusion 与 reranker 对文档描述引入排序噪声；短符号查询仍保留 precise-first 链路。

量化评测覆盖 RAG、Agent、Memory 和 Context Compression。RAG 可接入 CodeSearchNet Java 公共 test split，统一输出 Recall@5、MRR@5、nDCG@5；Agent 输出任务成功率，Memory 输出写入准确率与 Recall@5，Compression 输出事实保真率。受控 Agent benchmark 不暴露 `execute_command`，由隐藏验证器在运行后统一编译和执行行为检查，避免 Docker daemon 状态污染模型指标；生产 Pre-Review 仍强制 Docker。原始报告位于 `target/benchmark-reports/` 和 `target/agent-benchmark/`，聚合报告写入 `Data/processed/` 与 `Data/manifest/`；详细方法见 `docs/benchmark-evaluation.md`。

MCP 动态工具：`mcp__{server}__{tool}`（+ resources 虚拟工具）

模型调用可靠性链路：Anthropic 与全部 OpenAI-compatible Provider 统一抛出 `LlmException`，错误码覆盖认证、限流、过载、超时、网络、参数、上下文超限、内容过滤、服务端和响应格式错误；只对限流、过载、超时、网络和 5xx 做指数退避有界重试，流式内容开始输出后禁止重试。默认 3 次、500ms 初始退避、8s 上限、0.2 jitter，可通过 `DEVCLI_LLM_RETRY_*` 或对应系统属性调整。

工具调用可靠性链路：LLM 先按 reasoning 说明目标、工具选择和参数来源；工具定义使用 JSON Schema 强约束类型、必填项、枚举值和未知字段；`ToolRegistry` 通过 `ToolExecutionPipeline` 分阶段执行取消、工具存在性、能力范围、Skill 权限、参数校验、HITL、审计、策略和结果尺寸治理；并行工具线程显式继承能力范围、资源租约和 Skill buffer 快照，项目 fork 复制 `SkillContextBuffer`，不共享可变状态；工具结果使用 `ToolStatus`、`ToolErrorCode` 和 retryable 结构化表达；内置 Provider 可通过结构化执行器直接返回状态，参数错误、策略拒绝、命令非零退出、超时和取消不再先压成普通文本；ReAct、Plan、SubAgent 的重复错误熔断不再依赖结果文本关键词；执行前通过 `json-schema-validator` + 本地兜底校验内置工具和 MCP 工具参数，失败以 `工具参数校验失败` 回传模型修正；默认只注入内置核心工具和已激活 MCP 工具；ReAct、Plan 和 Multi-Agent turn 开始前会按当前用户输入预激活匹配到的 MCP 工具；`search_tools` 使用工具索引缓存，MCP 工具变更后自动失效，命中 MCP 工具后激活到后续工具定义；未知工具会提示先调用 `search_tools`；危险工具继续走 HITL / Policy / AuditLog；工具参数通过稳定语义指纹参与停滞判断，JSON 字段顺序、查询大小写和冗余空白不会绕过重复检测；成功且无图片的 READ_ONLY 工具结果按会话短期缓存，任何非只读工具执行和项目路径切换都会清空缓存；MCP 工具结果被截断或落盘预览时会标记折叠分类；工具结果进入 WorkingMemory，最终回答必须用工具证据闭环。

## 仓库结构

```
src/main/java/com/devcli/
├── agent/       Agent.java, PlanExecuteAgent.java, SubAgent.java, AgentOrchestrator.java, PlanTaskWorkspaceExecutor.java, WorkspaceCommitCoordinator.java
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
├── runtime/     api/ (RuntimeApiServer) + task/ (DurableTaskManager)
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

Runtime API 只绑定 `127.0.0.1`，请求线程与 Agent turn 执行线程隔离；turn 执行池默认 2 线程 / 64 队列，过载返回 `429 runtime_busy`；`KeyedSerialExecutor` 通过同 key 原子创建、入队和退役保证同一 thread 永远串行，底层调度拒绝会传递给全部等待提交者，普通 turn 异常不会阻塞同通道后续任务；JVM `Error` 会立即终止该通道并把已排队 turn 标记为 `fatal_runtime_error`，禁止继续执行潜在损坏状态。同一 thread 的 turn 有上下文延续（存储即状态）：每 turn 新建 Agent，执行前经 `RuntimeThreadStore.turnHistory` 重放该 thread 最近 20 轮的输入/输出对（`TurnRunner` 接口带 threadId；失败/被拒 turn 不进历史）。交互、后台任务和无头 turn 使用运行级 `RunContext` 隔离项目路径、取消令牌和资源生命周期；取消状态不再回退到进程级全局 token，线程中断也视为取消。`HeadlessAgentRunner` 统一创建并关闭无头 Agent 使用的 ToolRegistry / MemoryManager，工具大结果落盘使用所属 ToolRegistry 的实例项目路径，不使用跨实例静态路径。

启动与 inline 渲染当前约定：

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
- `LineReader` 使用 `DevCliHistory` 持久化输入历史到 `~/.devcli/history/input.history`；如果 `devcli.history.file` / `DEVCLI_HISTORY_FILE` 指向目录，也会自动使用该目录下的 `input.history`，避免把目录当文件读；默认忽略空白、重复、明显密钥/Bearer、base64 图片和超长输入，用户可用 `/history clear` 清空本机输入历史。plain 与 inline 的 HITL 后续输入复用主 LineReader，禁止再创建竞争读取 `System.in` 的独立入口。
- 重定向输入默认按 UTF-8 解码；旧式控制台可用 `DEVCLI_TERMINAL_ENCODING` 覆盖。ANSI 能力被误判时可用 `DEVCLI_TERMINAL_FORCE_ANSI=true` 强制使用 xterm-256color 终端类型。
- JLine 交互升级计划记录在 `docs/phase-22-jline-interaction-upgrade.md`。

## 关键行为约束（Agent 必读）

### Memory

- 长期记忆主要通过 `/save` 或用户明确要求保存；中英文显式记忆意图、少量稳定个人属性和多次重复出现的稳定项目/偏好事实可由策略自动保存
- 长期记忆只保存跨会话稳定事实，不保存临时指令；显式保存请求如果内容仍然明显临时或低复用，需要确认而不是直接落库；中英文临时表达、敏感信息和模糊新个人状态必须确认或跳过；与 WorkingMemory volatile fact 语义重复的长期记忆在 prompt 注入时会被抑制
- 用户显式要求忽略记忆（如“别管记忆”“忽略记忆”）时，本会话不注入长期记忆、通用 WorkingMemory 和角色裁剪后的 WorkingMemory
- 反馈类长期记忆按 `FEEDBACK` 类型落库，不混入普通 `FACT`
- 长期记忆统一记录 `schemaVersion`、主题内 `revision` 和 `expiresAt`；新条目按类型 TTL 写入，检索、计数和上下文构建前清理过期项。命中 `subject（主题键）` 的事实写入走 supersede；显式同主题内容变化或可解析键值事实冲突会写入 `conflict_detected/conflict_with`，旧事实置为 inactive。无法提取主题且无法识别声明键时才追加
- `ConversationHistoryCompactor` 是唯一治理 LLM messages 窗口的压缩点；压缩前先走第 0 层 `microcompact`（单条超大消息头尾截断；旧轮次 tool_result 按 toolCallId 成批落盘并替换为 `<microcompact_boundary>` 引用；不删消息、保 tool_call 配对），扛不住再 LLM 摘要（九段结构化、超长走程序化 GC 按段裁剪、不够再 LLM 兜底）。摘要写回 history 前必须经过 `CompactionSemanticGuard`，从原消息提取必须、禁止、默认值、命令、版本和配置赋值等保护约束；缺失内容直接追加恢复段；`<compact_boundary>` 记录保护约束数、恢复数和 pass/repaired 状态。`WorkingMemory` 是当前会话派生视图，不是压缩器，恢复区会按 storedPath/toolCallId 去重 microcompact 工具引用
- `SessionMemory` 维护当前进程内会话预摘要，自动压缩时优先复用覆盖同一消息指纹且未过期的预摘要；预摘要默认 30 分钟过期，ReAct 可同步维护，Plan / Multi-Agent turn 结束后提交后台单线程维护任务，不写长期记忆
- 压缩成功后会插入 `[压缩后恢复上下文]` 消息：恢复段按最近读写文件、未完成子任务状态、关键工具结果引用、RAG 证据 epoch 和 MCP 工具状态分节；恢复内容经统一预算与行级去重后注入，Multi-Agent 会按 Planner / Worker / Reviewer 角色裁剪；SkillContextBuffer 追加已加载 Skill 与 allowedTools 状态
- 压缩边界 `<compact_boundary>` 会记录已加载 Skill、RAG epoch、MCP 工具快照和压缩后恢复入口状态；RAG epoch 合并当前会话已命中证据与当前项目全局索引版本，MCP 工具快照按 server 记录工具数量、schema 指纹和生命周期版本
- `McpServerManager` 会记录本进程 MCP 连接事件：STARTING / READY / ERROR / DISABLED / RECONNECTING / TOOLS_CHANGED，事件携带 server、状态、生命周期版本、工具数量和消息；启动失败后会后台自动重连，默认最多 3 次
- MCP 工具发现缓存记录 server、生命周期版本、工具数量、工具名、schema 指纹和发现时间；disable 不清除上一轮发现元数据
- MCP `tools/call` 会携带 `_meta.progressToken`，同 token 的 `notifications/progress` 会汇总进工具结果文本
- MCP 工具结果进入尺寸治理后会标记折叠分类：截断输出为 `INLINE_TRUNCATED`，落盘预览为 `PERSISTED_PREVIEW`
- 滚动摘要超过字符上限时触发"摘要的摘要"再压缩，失败则保留超长摘要并打日志（宁可贵不丢事实）
- `search_code` 结果中的结构化 negativeFact 携带 `oldSymbolVersion` 时，WorkingMemory 即时清理对应的失效 RAG 证据；旧文本格式保留兼容解析
- `TaskLedger`（计划执行进度投影）挂在 `WorkingMemory`、不进 conversationHistory，压缩不触碰它；当前仅 `PlanExecuteAgent` 接入（task 开始/完成/失败时更新），经 working memory 段注入，让长 plan 压缩后仍能看到当前 step / 已完成 / 待执行 / 失败。`/plan` task 完成或失败时会记录结构化 `modifiedFiles` 和短 `resultSummary`；失败后 replan 是无工具的 Planner LLM 调用，只读取这些最小产物事实生成后续计划，不依赖完整 result 文本。
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
| Multi-Agent | AgentOrchestrator.java + SubAgent.java |
| MCP | McpServerManager.java + McpClient.java |
| TUI/渲染 | render/Renderer.java + RendererFactory.java |

## 当前已知边界

以下在路线图但未交付：容器/VM 级完整系统沙箱、MCP sampling + server 自动重启；当前隔离命令已使用受限 Docker，但 Docker daemon 仍属于主机高权限基础设施。MCP OAuth 暂不纳入个人使用优先级

不要把 `ROADMAP.md` 中"将来要做"误读成"现在已有"。

## 持续维护约定

形成稳定协作规则时直接补进本文件，不要只留在聊天记录里。详细实现细节补到 `docs/agents-reference.md`。
