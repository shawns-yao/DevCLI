# AGENTS Reference: Detailed Feature Behavior

This document contains detailed feature behavior descriptions, configuration reading orders, and implementation notes that were previously in `AGENTS.md`. Consult this when working on specific modules.

For the primary entry point, see `/AGENTS.md`.

---

## Configuration Reading Orders

### API Key

1. `~/.devcli/config.json` 中对应 provider 的 `apiKey`
2. 环境变量：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY`（Kimi 兼容 `MOONSHOT_API_KEY`）
3. 仓库当前目录下的 `.env`
4. 用户主目录下的 `.env`

### Persistence Locations

| 数据 | 默认路径 | 覆盖方式 |
|------|----------|----------|
| 长期记忆 | `~/.devcli/memory/long_term_memory.json` | `-Ddevcli.memory.dir` |
| RAG 索引 | `~/.devcli/rag/codebase.db` | `-Ddevcli.rag.dir` |
| 审计日志 | `~/.devcli/audit/audit-YYYY-MM-DD.jsonl` | `DEVCLI_AUDIT_DIR` / `-Ddevcli.audit.dir` |
| Side-Git 快照 | `~/.devcli/snapshots/<project_hash>/<worktree_hash>/.git` | `DEVCLI_SNAPSHOT_DIR` / `-Ddevcli.snapshot.dir` |
| 后台任务 | `~/.devcli/tasks/tasks.db` | — |

### Snapshot Config

系统属性 > 环境变量 > 默认值：`devcli.snapshot.enabled`(true) / `devcli.snapshot.max`(50) / `devcli.snapshot.excludes`(.git,.devcli/snapshots,target,node_modules,dist,.idea,*.class,*.jar) / `devcli.snapshot.dir`(~/.devcli/snapshots)

### Embedding Config

环境变量 > 系统属性 > 默认值：`EMBEDDING_PROVIDER`(ollama) / `EMBEDDING_MODEL`(nomic-embed-text:latest) / `EMBEDDING_BASE_URL`(http://localhost:11434)

### Log Config

系统属性 > 环境变量/.env > 默认值：`DEVCLI_LOG_DIR`(~/.devcli/logs) / `DEVCLI_LOG_LEVEL`(INFO) / `DEVCLI_LOG_MAX_HISTORY`(7) / `DEVCLI_LOG_MAX_FILE_SIZE`(10MB) / `DEVCLI_LOG_TOTAL_SIZE_CAP`(100MB)

### ReAct/SubAgent Budget Config

系统属性 > 默认值：`devcli.react.token.budget`(Integer.MAX_VALUE) / `devcli.react.stagnation.window`(3) / `devcli.react.hard.max.iterations`(50)

设计取舍：长上下文模型默认不再以 80% x window 为硬限。死循环防护由 stagnation 检测（连续 3 轮相同工具调用）和 hardMaxIterations（50 轮）兜底。Token 显示行 `📊 Token: 已用 X / Y` 的 Y 是软提示，不代表强制限制。

### LLM HTTP Timeout Config

系统属性 > 默认值：`devcli.llm.connect.timeout.seconds`(60) / `devcli.llm.read.timeout.seconds`(300) / `devcli.llm.write.timeout.seconds`(60) / `devcli.llm.call.timeout.seconds`(600)

SSE 流式下 readTimeout 是两次 read 间最大间隔，GLM-5.1 生成大段 reasoning 时可能长时间静默，所以放宽到 300 秒。

### Web Search Provider Config

1. `SEARCH_PROVIDER` 显式指定 `zhipu` / `serpapi` / `searxng`
2. 未指定时按 Key 自动判断：`GLM_API_KEY` → zhipu / `SERPAPI_KEY` → serpapi / `SEARXNG_URL` → searxng
3. 都没有 → zhipu 占位

各 provider：zhipu(`GLM_API_KEY` + 可选 `ZHIPU_SEARCH_ENGINE`) / serpapi(`SERPAPI_KEY`) / searxng(`SEARXNG_URL`)

### Web Fetch Security (NetworkPolicy)

scheme 白名单(http/https) / 主机黑名单(localhost/loopback/link-local/site-local) / 响应体上限 5MB / 超时 30s / 限流 30次/60s

### MCP Config

1. 用户级：`~/.devcli/mcp.json`
2. 项目级：`.devcli/mcp.json`
3. 按 server 名 merge，项目级覆盖用户级

格式兼容 Claude Code：`command` + `args` = stdio，`url` + `headers` = Streamable HTTP。内置变量：`${PROJECT_DIR}`、`${HOME}`。

---

## Detailed Feature Behavior

### ReAct Mode

- 主入口：`Agent.java`
- 退出条件由 LLM 自决（不返回 tool_calls 即结束）
- `AgentBudget` 三种兜底：token 超预算 / 连续 3 轮相同调用 / 50 轮硬上限
- 流式输出 reasoning_content + content；inline ReAct 用固定高度 live thinking 区动态预览 reasoning，同一次输入只把完整 reasoning 引用块落到 transcript 一次；live 区只允许清理自己占用的行，避免覆盖旧输出
- inline 流式回答用低调 `▪` 标记起始，不再输出强标题；plain / 非流式兜底仍可使用传统 reasoning + answer 文本

### Long Context Engineering

- `ContextProfile` 计算 short/balanced/long 模式
- GLM-5.1: 200k / DeepSeek V4: 1M / StepFun: 256k / Kimi K2.6: 256k
- long 模式(>=100k)：跳过 Memory 自动摘要，search_code topK=20，MCP resources 自动索引
- prompt caching：能力声明 + cached usage 解析

### Memory System

- 两道压缩：
  1. `ContextCompressor` 压缩 shortTermMemory
  2. `ConversationHistoryCompactor` 压缩 conversationHistory（真正发给 LLM 的消息）
- 第二道压缩切割在 user message 边界，保留最近 3 个 user 起算的尾部
- 三条路径(ReAct/Plan/SubAgent)都接入第二道压缩
- `SessionMemory` 保存当前进程内会话预摘要，按消息指纹复用，默认 30 分钟过期；Plan / Multi-Agent turn 结束后通过 `MemoryManager` 的单线程后台 executor 维护预摘要，避免主流程等待摘要 LLM 调用
- 压缩边界 `<compact_boundary>` 记录已加载 Skill、RAG epoch、MCP 工具快照和压缩后恢复入口状态；RAG epoch 合并当前会话已命中证据与当前项目全局索引版本，MCP 工具快照包含 server 工具数量、schema 指纹和生命周期版本
- 长期记忆主要通过 `/save` 或用户明确要求保存；少量稳定个人属性和多次重复出现的稳定项目/偏好事实可由策略自动保存
- 长期记忆只保存跨会话稳定事实，不保存临时指令；敏感信息和模糊新个人状态必须确认或跳过

### Multi-Agent

- 三角色：Planner / Worker(默认 2 个) / Reviewer
- 流程：规划 → 按依赖分配 Worker → Reviewer 审查 → 未通过重试(最多 2 次)
- SubAgent IOException 返回 ERROR 类型
- 所有子代理共享 ToolRegistry 和 MemoryManager

### HITL System

- 危险工具：write_file(中) / execute_command(高) / create_project(中) / revert_turn(高)
- 审批选项：y(批准) / a(全部放行) / n(拒绝) / s(跳过) / m(修改参数)
- fail-safe：连续 5 次无效输入判为 REJECTED
- 并发：requestApproval 整体 synchronized

### HITL Enhancement (Policy Layer)

- `PathGuard`：路径限定在项目根内（绝对路径外逃 / `..` 穿越 / 符号链接逃逸）
- `CommandGuard`：fast-fail 黑名单（sudo/rm -rf/mkfs/dd/fork bomb/curl|sh 等）
- `ResourceLimit`：write_file 5MB / execute_command 60s + 8KB 输出
- `AuditLog`：JSONL 字段 timestamp/tool/args/outcome/reason/approver/durationMs
- 拦截顺序：HitlToolRegistry → ToolRegistry → 策略层。用户无法批准策略拒绝的请求

### Parallel Tool Execution

- `executeTools()` 固定线程池并行，默认最多 4 个并发
- 返回结果保持原始顺序
- Agent/PlanExecuteAgent/SubAgent 三条路径都走 executeTools()

### Web Capabilities

- `web_search`：SearchProvider 接口，返回 SearchResult 列表
- `web_fetch`：NetworkPolicy → WebFetcher → HtmlExtractor，SPA/防爬墙返回空正文 + 边界提示
- JS 渲染 fallback 到 Chrome DevTools MCP

### MCP Protocol

- stdio + Streamable HTTP 双 transport
- 工具注册为 `mcp__{server}__{tool}`
- McpSchemaSanitizer 清洗 inputSchema
- 所有 mcp__ 工具默认走 HITL + AuditLog；带 destructive/openWorld annotations 的 MCP 工具强制逐次审批，不复用 tool/server 级全部放行缓存
- resources 双轨：虚拟工具 + @-mention 输入层
- CLI 首屏默认只等待 MCP 启动 8 秒，慢 server 后台继续初始化并保持 `starting`，用 `/mcp` / `/mcp logs <name>` 追踪
- McpServerManager 记录本进程连接事件：STARTING / READY / ERROR / DISABLED / RECONNECTING / TOOLS_CHANGED，事件携带 server、状态、生命周期版本、工具数量和消息
- 启动失败的 MCP server 会后台自动重连，默认最多 3 次；成功后重新注册工具并刷新 lifecycleVersion / 工具发现缓存
- 工具发现缓存记录 server、生命周期版本、工具数量、工具名、schema 指纹和发现时间；disable 只移除运行时注册工具，不清除上一轮发现元数据
- MCP 工具结果被尺寸治理截断或落盘预览时会在返回文本末尾标记折叠分类：`INLINE_TRUNCATED` 或 `PERSISTED_PREVIEW`
- notifications 路由：tools/list_changed → 工具全量替换，resources 变化 → cache 失效

### Chrome DevTools MCP

- 默认 server：chrome-devtools，`npx -y chrome-devtools-mcp@latest --isolated=true`
- `/browser connect`：切到 --autoConnect 复用登录态 Chrome
- `/browser connect <port>`：旧式 CDP 端口路径
- `/browser disconnect`：切回 isolated
- 敏感页面策略：改写型工具必须单步 HITL，不复用全部放行
- shared 模式 close_page 只允许关闭 DevCLI 创建的 tab

### Skill System

- 三层加载：jar 内置 < 用户级 ~/.devcli/skills/ < 项目级 .devcli/skills/
- frontmatter：name(必填) / description(必填,<=500) / version / author / tags / allowedTools / context / paths
- system prompt 索引段注入到三处提示词末尾，上限 20 个 / 4KB；排序按本进程内使用频率优先、名称兜底
- paths 使用项目相对路径匹配当前用户输入或任务文本中的路径；未声明 paths 的 Skill 始终可见，声明 paths 的 Skill 只在路径命中时进入索引
- load_skill 工具把 SKILL.md 正文(5KB 截断)写入 SkillContextBuffer，并记录本进程内使用次数
- buffer 正文一次性消费，最多 3 个 skill body；已加载 Skill 名称、context、allowedTools 和内容摘要保留给压缩后恢复，直到 clear
- allowedTools 为空表示不启用工具限制；声明 allowedTools 的已加载 Skill 会把后续工具调用限制在当前 SkillContextBuffer 白名单内，主 Agent、Plan 和 SubAgent 通过各自 buffer 隔离，/clear 清空白名单状态

### Post-Compact Restore

- ConversationHistoryCompactor 压缩成功后会在摘要确认消息之后、保留尾部之前插入 `[压缩后恢复上下文]`
- WorkingMemory 的恢复段不复用完整 system prompt 视图，而是按最近读写文件、未完成子任务状态、关键工具结果引用、RAG 证据 epoch 输出短结构化上下文
- Agent / PlanExecuteAgent / SubAgent 会在恢复段追加 MCP 工具状态和本地 SkillContextBuffer 的已加载 Skill、context、allowedTools 与内容摘要
- 恢复段通过 `PostCompactRestoreContext` 做统一预算控制和行级去重；SubAgent 恢复区使用 Planner / Worker / Reviewer 角色视图裁剪，Planner 不携带工具证据，Reviewer 不携带会话临时事件

### MicroCompact

- Microcompact 在 LLM 摘要前执行，不删除消息，保持 assistant tool_call 与 tool result 配对。
- 单条超大工具结果会落盘到 `.devcli/microcompact_tool_outputs/<session>/`，消息中保留 `<microcompact_boundary>`、toolCallId、originalChars 和 storedPath。
- 最近 2 个 user round 之前的旧 tool_result 会按 toolCallId 成批折叠为 boundary 引用；最近轮次保留原文，避免影响当前任务。
- WorkingMemory 压缩后恢复区遇到 microcompact 工具引用时，只输出 toolCallId / originalChars / storedPath，并按 storedPath 或 toolCallId 去重。

### TUI (v16.1 Renderer Architecture)

- 三个实现：InlineRenderer(默认) / LanternaRenderer / PlainRenderer
- 环境变量：`DEVCLI_RENDERER=inline|lanterna|plain`
- `DEVCLI_TUI=true`(旧) → lanterna + deprecation 提示
- `DEVCLI_NO_STATUSBAR=true`：禁用底部状态栏
- `NO_COLOR=1`：禁用 ANSI 颜色
- 当前开屏 Banner 是无右侧盒线边框的简洁布局，避免 ANSI/CJK 字宽导致竖线错位
- InlineRenderer 复用 JLine 4 的编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`
- BottomStatusBar 是 JLine `Status` 托管的底部 dock：由 JLine 负责滚动区域和状态行位置，不再手写 `\n`、`moveUp`、`CLEAR_TO_EOS` 或绝对光标行号；dock 上层展示 YOLO/HITL 与 MCP/Skill 摘要，下层展示 model、phase、ctx、token、cost、elapsed 与 cwd
- InlineRenderer 不使用独立 JLine `Display.update()` 维护 thinking 临时区；真实终端验证发现独立 Display 会在 transcript/status 输出后从错误位置向上清屏。当前实现用固定高度 live 区重写自身行，content/tool 边界先清理 live 区再追加 transcript。
- 交互期输出优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner`、`AgentOrchestrator` 都可接收同一个 renderer 输出流，避免绕过 inline renderer 直接写 stdout
- `CodeIndex` 通过 `ProgressListener` 上报索引开始 / 文件数量 / 进度 / 完成或失败，`/index` 绑定当前 renderer 输出流；内部异常细节写 logger

### LSP Diagnostics (Phase 17)

- write_file 成功后对 Java 文件做 JavaParser 语法诊断
- 诊断作为合成 user message 注入下一轮 LLM 请求
- `DEVCLI_LSP_ENABLED=false` 关闭

### Git Side-History Snapshot (Phase 18)

- side-git 在 ~/.devcli/snapshots/ 维护独立仓库（JGit，不依赖系统 git）
- pre-turn 同步，post-turn 异步
- revert_turn 纳入 HITL/AuditLog，恢复前先创建 pre-restore 快照

### Prompt Layering (Phase 19)

- 组装顺序：base → personality → mode → approval → project_context → skills → context_mgmt → handoff
- 覆盖优先级：jar 内置 < 用户级 ~/.devcli/prompts/ < 项目级 .devcli/prompts/
- 必要校验：base.md 和最终 prompt 必须包含 `## Language`

### Async Tasks + Runtime API (Phase 20)

- DurableTaskManager(SQLite) / CLI: /task, /task list, /task add, /task cancel, /task log
- Runtime API: `serve --http --port 8080`，仅 127.0.0.1，需 API Key
- 端点：POST /v1/threads / POST /v1/threads/{id}/turns / GET /v1/threads/{id}/events

### Image Input (Phase 21)

- ContentPart 支持图片 block（base64 + mimeType）
- ImageProcessor：铺白底/缩放 2000x2000/压缩 5MB
- 输入：`@image:file:///path.png` / `@image:/path.png` / `@image:relative.png`
- GLM-5V-Turbo 通过 `/model glm-5v-turbo` 切换
- 历史 image payload 替换为文本占位，避免旧截图消耗上下文

---

## Core File Descriptions

### Main.java
CLI 入口 / Banner / .env 读取 / 日志初始化 / 模式切换 / JLine raw mode

### Agent.java
ReAct 主循环 / 对话历史 / 工具调用与结果回灌

### PlanExecuteAgent.java
规划后执行 / 计划审阅 / DAG 任务执行 / 并行批次 / 失败重规划

### AgentOrchestrator.java
Multi-Agent 编排器 / 三角色管理 / 按依赖分配 / 审查重试

### SubAgent.java
可配置角色子代理 / 独立对话历史 / Worker 用工具、Planner/Reviewer 不用

### Planner.java
LLM 生成计划 JSON / 简单任务最小计划 / 重编号 task_1..N / 依赖计算

### ExecutionPlan.java
DAG 拓扑排序 / 可执行任务判定 / 进度可视化

### ToolRegistry.java
11 个内置工具 + MCP 动态工具 / executeTools() 并行入口 / ToolInvocation / ToolExecutionResult

### MCP Package
McpServerManager / McpClient / JsonRpcClient / StdioTransport / StreamableHttpTransport / McpSchemaSanitizer / resources/ / mention/ / notifications/

### TUI Package
TuiBootstrap / LanternaWindow / TuiSessionController / pane/ / hitl/ / history/ / highlight/

### LLM Clients
- AnthropicClient：默认 provider，Claude / Anthropic Messages 原生兼容端点
- OpenAiClient：OpenAI 官方或 Chat Completions 兼容端点
- GLMClient：glm-5.1，glm-5v 开头切多模态接口
- DeepSeekClient：deepseek-v4-flash
- StepClient：step-3.5-flash，可通过 STEP_BASE_URL 切通道
- KimiClient：kimi-k2.6，thinking + tool calls 带回 reasoning_content

---

## .env.example Reference

```bash
# ANTHROPIC_AUTH_TOKEN=your_anthropic_auth_token_here
# ANTHROPIC_BASE_URL=https://api.anthropic.com
# ANTHROPIC_MODEL=claude-sonnet-4-20250514
# OPENAI_API_KEY=your_openai_api_key_here
# OPENAI_MODEL=gpt-4o
# OPENAI_BASE_URL=https://api.openai.com/v1
# OPENAI_CHANNEL=Other
# OPENAI_GROUP=Other
# GLM_API_KEY=your_glm_api_key_here
# GLM_MODEL=glm-5.1
# GLM_MODEL=glm-5v-turbo
# DEEPSEEK_API_KEY=your_deepseek_api_key_here
# DEEPSEEK_MODEL=deepseek-v4-flash
# STEP_API_KEY=your_step_api_key_here
# STEP_MODEL=step-3.5-flash
# STEP_BASE_URL=https://api.stepfun.com/v1
# KIMI_API_KEY=your_kimi_api_key_here
# MOONSHOT_API_KEY=your_moonshot_api_key_here
# KIMI_MODEL=kimi-k2.6
# KIMI_BASE_URL=https://api.moonshot.ai/v1
EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=nomic-embed-text:latest
EMBEDDING_BASE_URL=http://localhost:11434
# EMBEDDING_API_KEY=your_api_key_here
# DEVCLI_LOG_LEVEL=INFO
# DEVCLI_LOG_DIR=/Users/yourname/.devcli/logs
# DEVCLI_LOG_MAX_HISTORY=7
# DEVCLI_LOG_MAX_FILE_SIZE=10MB
# DEVCLI_LOG_TOTAL_SIZE_CAP=100MB
# DEVCLI_SNAPSHOT_ENABLED=true
# DEVCLI_SNAPSHOT_MAX=50
# DEVCLI_SNAPSHOT_EXCLUDES=.git,.devcli/snapshots,target,node_modules,dist,.idea,*.class,*.jar
# DEVCLI_SNAPSHOT_DIR=/Users/yourname/.devcli/snapshots
# DEVCLI_TUI=true
# NO_TUI=true
```

---

## Test Coverage Summary

测试覆盖偏向：解析、计划结构、RAG 核心、Multi-Agent 编排、HITL 策略、策略层拦截、MCP 协议、资源输入层、长上下文策略与 Skill 加载。

不覆盖：真实 LLM 联调、真实 Embedding API、真实 MCP server 联调、终端完整手工体验。

完整测试类列表：CliCommandParserTest / MainBrowserCommandTest / PlanReviewInputParserTest / MainInputNormalizationTest / ExecutionPlanTest / MemoryEntryTest / ConversationMemoryTest / LongTermMemoryTest / MemoryRetrieverTest / MemoryManagerTest / ExplicitMemoryHintsTest / ContextProfileTest / PlanExecuteAgentTest / AgentMemoryHintTest / AgentRoleTest / AgentMessageTest / AgentOrchestratorTest / EmbeddingClientTest / SearchResultTest / NetworkPolicyTest / HtmlExtractorTest / WebFetcherTest / SearchProviderFactoryTest / ZhipuSearchProviderTest / VectorStoreTest / CodeChunkerTest / CodeAnalyzerTest / CodeIndexTest / ApprovalPolicyTest / ApprovalResultTest / HitlToolRegistryTest / TerminalHitlHandlerTest / ToolRegistryTest / BrowserSessionTest / BrowserConnectivityCheckTest / SensitivePagePolicyTest / BrowserGuardTest / McpSchemaSanitizerTest / McpConfigLoaderTest / JsonRpcClientTest / McpToolBridgeTest / McpResourceCacheTest / AtMentionParserTest / AtMentionExpanderTest / AtMentionCompleterTest / NotificationRouterTest / PathGuardTest / CommandGuardTest / AuditLogTest / SkillFrontmatterParserTest / SkillRegistryTest / SkillStateStoreTest / SkillBuiltinExtractorTest / SkillContextBufferTest / SkillIndexFormatterTest / LoadSkillToolTest / SkillCommandHandlerTest
