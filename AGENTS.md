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
- 下一步：OAuth / sampling / recovery 作为后续 MCP 增强
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

三条主执行路径，共享 ToolRegistry / MemoryManager / SnapshotService：

| 路径 | 入口 | 触发 |
|------|------|------|
| ReAct | `Agent.java` | 默认模式 |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` |
| Multi-Agent | `AgentOrchestrator.java` | `/team` |

Multi-Agent 中 Planner 负责拆解 DAG，Worker 负责实现子任务，Reviewer 负责硬检查通过后的质量审查。

Planner 必须输出 `acceptance_criteria`；Orchestrator 会把验收点前置注入 Worker，并要求 Reviewer 用 `criteria_results` 逐条验证。critical/high 验收点失败或缺少覆盖时强制不通过。

Multi-Agent 的 WorkingMemory 按角色注入隔离视图：Planner 只看任务状态 + 会话关键事件，不看工具原文证据；Worker 看完整任务状态 + 关键事件 + 工具证据；Reviewer 只看任务状态 + 工具证据，避免把会话事件误当验收依据。

Multi-Agent 并行批次使用 `SubAgent.ForkContext` 共享冻结 system prompt 前缀、exact tool definitions 快照、skill body 快照和 fork fingerprint；每个子任务只追加自己的 user 后缀，避免并行 Worker / Reviewer 因历史或动态工具差异破坏 prompt cache 命中。

并行 Worker 写文件时，`ToolRegistry.write_file` 会进入运行时资源租约检查：每个 `/plan` task 或 `/team` step 以自己的 id 持有写租约，同一文件只能被一个运行中步骤写入；冲突返回策略拒绝，不做 last-writer-wins 覆盖或 LLM 自动合并。设计说明见 `docs/runtime-resource-lease-design.md`。

Reviewer 前置硬约束：Worker 产物进入 Reviewer LLM 前，`AgentOrchestrator` 会先跑 Pre-Review Hook；Java 项目优先 `mvn -q -DskipTests test-compile`，无 Maven 时用 `javac -encoding UTF-8` 编译 `src/main/java`。失败时直接生成 `approved=false` 反馈打回 Worker，不唤醒 Reviewer LLM。

Reviewer 输出必须包含三层评分：`functional_correctness`、`integration_completeness`、`code_quality`。任一分数低于 `0.6`，或 `functional_correctness < 1.0`，Orchestrator 强制判不通过。

Final integration 只做入口/API/默认参数/跨模块联动胶水；普通步骤失败比例达到 `50%` 时熔断，不让最终步骤强行修补。

失败步骤支持有界在位重做（默认 1 次）：正常步骤走完仍有失败时，失败步骤保持原 id/依赖在 DAG 原位换思路重做（buildStepContext 注入上次失败反馈），而非生成平行恢复计划——恢复始终长在原 DAG 上、通过依赖关系看到已完成成果，从机制上消除"平行计划 vs 已落盘成果"冲突；redo 用尽仍失败则保持 FAILED 终态，由熔断与汇总处理。在位重做的状态与决策由 `StepRedoTracker` 承载（与调度循环解耦）。Orchestration 的计划（步骤/依赖/验收点）与进度（完整 result + 修改文件清单）落盘到 `~/.devcli/checkpoints/`（原子写入；全部成功后删除）；中断后可用 `/team resume [id]` 断点续跑——已完成步骤直接带回 result，其余重置 PENDING 重新执行；resume 不恢复 WorkingMemory / 会话记忆。

副作用横向信息流：write_file/execute_command 等副作用工具的证据在 `WorkingMemory.recentToolResults` 中优先保留、不被只读操作（read_file/search）的 FIFO 淘汰挤出，使后续步骤/轮次持续看到"本会话改过哪些文件"。这是改进既有工具证据淘汰策略实现的，未新增重复的文件账本维度。

职责边界：`WorkingMemory` 是**当前会话内**的副作用证据缓存（会淘汰、不跨进程）；**失败步骤的持久化副作用**由 checkpoint `failedArtifacts` 负责——失败步骤可能已写文件，其 modifiedFiles 进 checkpoint，`/team resume`（崩溃/跨进程恢复，此时 WorkingMemory 为空）后注入重做上下文，让 Worker 知道上次失败留下哪些文件、先读其当前内容再改。同进程靠 WorkingMemory，跨进程靠 checkpoint，二者互补。

内置工具 11 个：`read_file` / `write_file` / `list_dir` / `execute_command` / `create_project` / `search_code` / `web_search` / `web_fetch` / `save_memory` / `list_memory` / `revert_turn`

Code RAG 检索链路当前为 keyword + semantic + bounded graph → `RRF（倒数排名融合）` → symbol-aware boost → `CrossEncoderReranker（交叉编码器重排）`。Rerank 默认开启，默认指向本地 Docker 暴露的 OpenAI-compatible `/rerank` endpoint；不可用时自动降级回 RRF 结果，不阻断检索。

MCP 动态工具：`mcp__{server}__{tool}`（+ resources 虚拟工具）

工具调用可靠性链路：LLM 先按 reasoning 说明目标、工具选择和参数来源；工具定义使用 JSON Schema 强约束类型、必填项、枚举值和未知字段；`ToolRegistry` 执行前通过 `json-schema-validator` + 本地兜底校验内置工具和 MCP 工具参数，失败以 `工具参数校验失败` 回传模型修正；危险工具继续走 HITL / Policy / AuditLog；工具结果进入 WorkingMemory，最终回答必须用工具证据闭环。

## 仓库结构

```
src/main/java/com/devcli/
├── agent/       Agent.java, PlanExecuteAgent.java, SubAgent.java, AgentOrchestrator.java
├── cli/         Main.java, CliCommandParser.java, PlanReviewInputParser.java
├── browser/     BrowserSession, BrowserGuard, SensitivePagePolicy
├── llm/         AnthropicClient, GLMClient, DeepSeekClient, StepClient, KimiClient, OpenAiClient
├── context/     ContextProfile, ContextMode, TokenUsageFormatter
├── memory/      MemoryManager, ConversationHistoryCompactor, LongTermMemory, TaskLedger
├── plan/        Planner, ExecutionPlan, Task
├── rag/         CodeIndex, CodeRetriever, VectorStore, CodeChunker
├── lsp/         LspManager, LspDiagnosticFormatter
├── prompt/      PromptAssembler, PromptContext, PromptRepository
├── image/       ImageReferenceParser
├── runtime/     api/ (RuntimeApiServer) + task/ (DurableTaskManager)
├── snapshot/    SideGitManager, SnapshotService
├── tool/        ToolRegistry
├── mcp/         McpClient, McpServerManager, transport/, resources/, mention/
├── hitl/        HitlToolRegistry, ApprovalPolicy, TerminalHitlHandler
├── web/         SearchProvider, WebFetcher, HtmlExtractor, NetworkPolicy
├── policy/      PathGuard, CommandGuard, AuditLog
├── skill/       SkillRegistry, SkillPathMatcher, SkillContextBuffer, SkillIndexFormatter
└── render/      Renderer, InlineRenderer, PlainRenderer, RendererFactory
```

Runtime API 只绑定 `127.0.0.1`，请求线程与 Agent turn 执行线程隔离；turn 执行池默认 2 线程 / 64 队列，过载返回 `429 runtime_busy`。同一 thread 的 turn 有上下文延续（存储即状态）：每 turn 新建 Agent，执行前经 `RuntimeThreadStore.turnHistory` 重放该 thread 最近 20 轮的输入/输出对（`TurnRunner` 接口带 threadId；失败/被拒 turn 不进历史）。

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
- 启动期 MCP 不得阻塞首屏：CLI 默认最多等待 8 秒（`DEVCLI_MCP_STARTUP_WAIT_SECONDS` / `-Ddevcli.mcp.startup.wait.seconds` 可调），超时后保留未完成 server 为 `STARTING` 并后台继续初始化；`/mcp` 查看最新状态。
- `LineReader` 使用 `DevCliHighlighter` 做输入实时高亮：slash 命令、`@` 引用、`@image:`、`@clipboard`、敏感词和明显危险 shell 片段会在编辑阶段被标记；不要把这类视觉提示混入最终提交文本。
- `LineReader` 使用 `DevCliCompleter` 做上下文补全：`/model` provider、`/mcp` 子命令与 server、`/skill` 子命令与 skill name、`/task` / `/browser` / `/snapshot` 子命令、`@image:` 本地路径、本地 `@path` 和 MCP resource `@server:uri` 引用都应从同一个 completer 出口维护。
- 普通用户输入进入 Agent 前会先展开 MCP resource mention，再由 `LocalPathMentionExpander` 展开本地 `@path`：文件会内联为 `<file>` 块，目录会内联为 `<directory>` 列表；绝对路径或符号链接逃逸项目根时保持原文不展开。
- `LineReader` 使用 `DevCliHistory` 持久化输入历史到 `~/.devcli/history/input.history`；如果 `devcli.history.file` / `DEVCLI_HISTORY_FILE` 指向目录，也会自动使用该目录下的 `input.history`，避免把目录当文件读；默认忽略空白、重复、明显密钥/Bearer、base64 图片和超长输入，用户可用 `/history clear` 清空本机输入历史。
- JLine 交互升级计划记录在 `docs/phase-22-jline-interaction-upgrade.md`。

## 关键行为约束（Agent 必读）

### Memory

- 长期记忆主要通过 `/save` 或用户明确要求保存；少量稳定个人属性和多次重复出现的稳定项目/偏好事实可由策略自动保存
- 长期记忆只保存跨会话稳定事实，不保存临时指令；敏感信息和模糊新个人状态必须确认或跳过
- 命中 `subject（主题键）` 的事实写入走 supersede：同主题旧事实置为 inactive、检索只返回 active（如用户从 Fastjson 改用 Jackson）；抽不到主题退回追加不覆盖
- `ConversationHistoryCompactor` 是唯一治理 LLM messages 窗口的压缩点；压缩前先走第 0 层 `microcompact`（单条超大消息头尾截断；旧轮次 tool_result 按 toolCallId 成批落盘并替换为 `<microcompact_boundary>` 引用；不删消息、保 tool_call 配对），扛不住再 LLM 摘要（九段结构化、超长走程序化 GC 按段裁剪、不够再 LLM 兜底）；`WorkingMemory` 是当前会话派生视图，不是压缩器，恢复区会按 storedPath/toolCallId 去重 microcompact 工具引用
- `SessionMemory` 维护当前进程内会话预摘要，自动压缩时优先复用覆盖同一消息指纹且未过期的预摘要；预摘要默认 30 分钟过期，ReAct 可同步维护，Plan / Multi-Agent turn 结束后提交后台单线程维护任务，不写长期记忆
- 压缩成功后会插入 `[压缩后恢复上下文]` 消息：恢复段按最近读写文件、未完成子任务状态、关键工具结果引用、RAG 证据 epoch 和 MCP 工具状态分节；恢复内容经统一预算与行级去重后注入，Multi-Agent 会按 Planner / Worker / Reviewer 角色裁剪；SkillContextBuffer 追加已加载 Skill 与 allowedTools 状态
- 压缩边界 `<compact_boundary>` 会记录已加载 Skill、RAG epoch、MCP 工具快照和压缩后恢复入口状态；RAG epoch 合并当前会话已命中证据与当前项目全局索引版本，MCP 工具快照按 server 记录工具数量、schema 指纹和生命周期版本
- `McpServerManager` 会记录本进程 MCP 连接事件：STARTING / READY / ERROR / DISABLED / TOOLS_CHANGED，事件携带 server、状态、生命周期版本、工具数量和消息
- 滚动摘要超过字符上限时触发"摘要的摘要"再压缩，失败则保留超长摘要并打日志（宁可贵不丢事实）
- `search_code` 结果中的 negativeFact 携带 `oldSymbolVersion=` 时，WorkingMemory 即时清理对应的失效 RAG 证据
- `TaskLedger`（计划执行进度投影）挂在 `WorkingMemory`、不进 conversationHistory，压缩不触碰它；当前仅 `PlanExecuteAgent` 接入（task 开始/完成/失败时更新），经 working memory 段注入，让长 plan 压缩后仍能看到当前 step / 已完成 / 待执行 / 失败
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

以下在路线图但未交付：容器/VM 沙箱 / MCP OAuth + sampling + server 自动重启

不要把 `ROADMAP.md` 中"将来要做"误读成"现在已有"。

## 持续维护约定

形成稳定协作规则时直接补进本文件，不要只留在聊天记录里。详细实现细节补到 `docs/agents-reference.md`。
