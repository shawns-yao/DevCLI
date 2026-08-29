# AGENTS Reference: Detailed Feature Behavior

This document contains detailed feature behavior descriptions, configuration reading orders, and implementation notes that were previously in `AGENTS.md`. Consult this when working on specific modules.

For the primary entry point, see `/AGENTS.md`.

---

## Configuration Reading Orders

### Default Agent Delegation

默认入口由主 Agent 使用 `delegate_task` 按需调用子 Agent；它不是 `/plan` 的替代命令，也不预先构造 DAG。复用执行内核、现有工具并行池、审批锁、隔离工作区和补丁提交服务，没有第二套调度框架。

| 角色 | 可用能力 | 上下文 |
| --- | --- | --- |
| explorer / planner / reviewer | 只读工具和本地 Skill 上下文 | system 规则快照、Skill 快照、显式 task/context、自己的工具结果 |
| worker | 以上能力，加隔离项目修改和受限命令 | 同上；不自动继承父会话或长期记忆 |
| 主 Agent | 保留原有工具及委派能力，负责最终验收 | 自己的会话、子任务有界报告和已归并文件清单 |

子 Agent 不能递归委派、直接读写长期记忆或执行外部副作用。`search_tools` 与最终执行管线同时过滤权限，不能通过猜测工具名绕过；MCP 继续使用已有信任策略。项目内代码可读取，不将任务描述中的文件范围冒充操作系统级访问隔离。`read_tool_result` 仅允许受限运行读取本运行生成的结果，主 Agent 可以恢复历史结果。子任务的完整消息不混入父历史，不另存为可恢复 checkpoint。

Worker 完成正常工具循环后才生成 PatchSet；未解决的写入/命令失败、模型失败、预算退出、超时和提交前取消均不应用产物。版本冲突返回结构化失败，不覆盖主项目的并发修改。修改已开始原子提交后，取消不自动撤销已提交补丁；父任务可使用既有快照回滚。成功报告只代表委派执行和归并成功，不等于业务验收通过。默认委派不强制运行 `/plan` 的 Pre-Review/Reviewer 门禁，主 Agent 必须检查实际产物和验证证据。

所有新参数按系统属性 > 进程环境变量 > 默认值读取；不自动导出 `.env`：

- `devcli.delegate.<role>.provider/model` / `DEVCLI_DELEGATE_<ROLE>_PROVIDER/MODEL`：四种角色独立配置，未设置复用本轮主模型；显式无效配置失败，不静默换模型。凭据仍由现有 `DevCliConfig` 读取。
- `devcli.delegate.max.iterations` / `DEVCLI_DELEGATE_MAX_ITERATIONS`：每个子循环默认 32 轮，范围 `[1,100]`。
- `devcli.delegate.timeout.seconds` / `DEVCLI_DELEGATE_TIMEOUT_SECONDS`：每次委派默认 300 秒，范围 `[1,3600]`，从工具批次提交时开始计算，包含排队。只延长委派调用，不改变同批普通工具的时限。
- 父子共享 `devcli.react.token.budget` 和 `devcli.react.hard.max.iterations`；子上下文摘要调用同样计入 Token 和总调用轮数。每个循环独立检测重复工具和重复错误，重新委派不能重置总预算。并行中的模型响应可能让 Token 使用超出阈值，后续调用会停止；不是按最坏响应预扣费的硬成本配额。

子取消令牌连接父工具调用；Anthropic 和全部 OpenAI-compatible 客户端在取消时关闭底层 HTTP Call，不仅依赖线程中断。外部不合作的工具仍须等待其清理结束，不能将超时描述为能强杀任意主机代码。生命周期以 `delegation.started/tools/completed` 事件及 `child_id` 记录，子执行终态不覆盖父运行终态。

角色指引使用已有 `PromptRepository`：内置 `prompts/modes/delegate-<role>.md`，可由 `~/.devcli/prompts/modes/`、项目 `.devcli/prompts/modes/` 覆盖；覆盖文本不改变工具权限和预算。

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

系统属性 > 环境变量 > 默认值：`devcli.snapshot.enabled`(true) / `devcli.snapshot.max`(50，自动保留最近 N 条快照) / `devcli.snapshot.excludes`(.git,.devcli/snapshots,target,node_modules,dist,.idea,*.class,*.jar) / `devcli.snapshot.dir`(~/.devcli/snapshots)

### Embedding Config

环境变量 > 系统属性 > 默认值：`EMBEDDING_PROVIDER`(ollama) / `EMBEDDING_MODEL`(nomic-embed-text:latest) / `EMBEDDING_BASE_URL`(http://localhost:11434)

### Log Config

系统属性 > 环境变量/.env > 默认值：`DEVCLI_LOG_DIR`(~/.devcli/logs) / `DEVCLI_LOG_LEVEL`(INFO) / `DEVCLI_LOG_MAX_HISTORY`(7) / `DEVCLI_LOG_MAX_FILE_SIZE`(10MB) / `DEVCLI_LOG_TOTAL_SIZE_CAP`(100MB)

### ReAct/SubAgent Budget Config

系统属性 > 默认值：`devcli.react.token.budget`(Integer.MAX_VALUE) / `devcli.react.stagnation.window`(3) / `devcli.react.hard.max.iterations`(50)

设计取舍：长上下文模型默认不再以 80% x window 为硬限。死循环防护由 stagnation 检测（连续 3 轮相同工具调用）和 hardMaxIterations（50 轮）兜底。Token 显示行 `📊 Token: 已用 X / Y` 的 Y 是软提示，不代表强制限制。

### LLM HTTP Timeout Config

系统属性 > 默认值：`devcli.llm.connect.timeout.seconds`(60) / `devcli.llm.read.timeout.seconds`(300) / `devcli.llm.write.timeout.seconds`(60) / `devcli.llm.call.timeout.seconds`(600)。OpenAI-compatible 默认 `devcli.llm.http.protocol=AUTO` 协商 HTTP/2 与 HTTP/1.1，兼容异常网关时可显式设为 `HTTP_1_1`；环境变量为 `DEVCLI_LLM_HTTP_PROTOCOL`。

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

格式兼容 Claude Code：`command` + `args` = stdio，`url` + `headers` = Streamable HTTP。内置变量：`${PROJECT_DIR}`、`${HOME}`。安全策略字段包括 `trustReadOnlyAnnotations`、`readOnlyTools`、`deniedTools`；默认不信任服务端 readOnly 注解，destructive/openWorld 不受只读授权覆盖。

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

- 记忆按生命周期分三层：`conversationHistory + RollingSummary` 治理当前线程窗口，`SessionMemory` 保存当前任务共享的 WorkState 与 EvidenceJournal，`LongTermMemory` 保存跨任务稳定事实
- `SessionMemory` 统一接收幂等 SessionEvent；ReAct、Plan 和 Team 使用 beginTask/completeTask/endTask 轮换任务投影，全部 Prompt 区块共享一个硬 Token 预算；Multi-Agent 保存真实 agentId、stepId、sequence 和 context epoch，并拒绝迟到事件
- `ConversationHistoryCompactor` 是唯一消息窗口治理点；六段 `RollingSummary` 不保存待办、当前工作或下一步，旧九段摘要只兼容读取；`CompactionSummaryCache` 只保存可复用预摘要，默认 30 分钟过期
- `RuleContext` 加载规则文件和 `/rule add` 强约束，支持 `/rule list`、`/rule remove`；旧 pinned facts 仅列为待分类候选。敏感 `save_memory` 必须通过持久化 `confirmation_id` 和 `confirm_memory` 完成用户确认；票据默认保留 24 小时，完成后可幂等重放终态结果
- 压缩边界 `<compact_boundary>` 记录已加载 Skill、RAG epoch、MCP 工具快照和压缩后恢复入口状态；RAG epoch 合并当前会话已命中证据与当前项目全局索引版本，MCP 工具快照包含 server 工具数量、schema 指纹和生命周期版本
- 普通用户消息不直接写长期记忆。显式配置独立 Curator 后，任务完成先持久化脱敏、限长的 `TaskMemorySnapshot`，再由空工具、无旧记忆的 `IsolatedMemoryCurator` 输出 `SAVE / CONFIRM / SKIP`；未配置时跳过自动晋升，不创建无人消费的队列作业。除模型推理传输外不提供 Web、MCP、Skill、文件、命令或子 Agent 入口；待确认候选通过 `/memory pending|confirm|reject` 处理
- 长期记忆只保存跨会话稳定事实，不保存临时指令；显式保存请求如果内容仍然明显临时或低复用，需要确认而不是直接落库；与 SessionMemory 关键事件语义重复的长期记忆在 prompt 注入时会被抑制
- 长期检索先按 `scope_type/scope_key` 过滤，再融合关键词、向量和新鲜度；实际注入 Turn Context 只批量更新 `recallCount/lastRecalledAt` 作为观测，不刷新新鲜度、不续期、不按召回次数提权。新鲜度以 `lastValidatedAt` 为优先年龄锚点；用户确认、同值重复显式保存等强验证信号累计 `validatedUseCount` 并分档延长 TTL；到期后先软归档
- RAG 检索审计按 JSONL 保存各召回通道、RRF、rerank、最终结果和降级状态，不保存代码正文；普通 CLI 会话归档默认关闭，启用后 ReAct 保存脱敏模型消息，Plan / Team 保存顶层输入输出，并按配置期限清理
- 用户显式要求忽略记忆（如“别管记忆”“忽略记忆”）时，本会话不注入长期记忆、通用 SessionMemory 和角色裁剪后的 SessionMemory
- 反馈类长期记忆按 `FEEDBACK` 类型落库，不混入普通 `FACT`

### Plan Multi-Agent

- 唯一公开入口为 `/plan`，固定进入 Planner/Worker/Reviewer 链路；旧 `/plan --team` 与 `/team` 仅保留解析兼容，不再出现在帮助和补全中。串行或并行由 DAG 就绪状态与资源冲突决定。
- `MultiAgentBatchExecutor` 委托 `OrchestrationWaveExecutor` 完成有界并发、异常归属、输出隔离和稳定顺序归并；`PlanExecuteAgent` 与 STANDARD profile 仅保留内部兼容，不进入 CLI 路由。
- 三角色：Planner / Worker(默认 2 个) / Reviewer
- 流程：规划与确定性预检 → Reviewer 计划语义评审 → 用户确认 → 按依赖分配 Worker → Pre-Review / 产物 Reviewer → 未通过有界重试
- 计划 Reviewer 使用独立、无工具上下文，逐项输出需求覆盖、节点引用、验收标准评审和结构化问题；critical/high 标准必须给出反例输入及预期失败信号。输出前后允许少量说明文本，但只提取包含 `approved` 的完整合法 JSON 对象；无法提取时由同一 Reviewer 进行一次协议修复，仍无效才失败关闭。可选的独立 Provider / 模型通过 `DEVCLI_TEAM_REVIEWER_PROVIDER` / `DEVCLI_TEAM_REVIEWER_MODEL` 配置，显式配置不可用时失败关闭。拒绝反馈给 Planner 有界修复。恢复未完成 checkpoint 时重新评审，避免旧计划绕过新门禁。
- 每条验收标准必须包含判定信号、`TOOL|HUMAN` 验证方式、验证器和 `applies_to`。目标只能是有效 DAG 节点或 `FINAL`；普通节点只接收自己的标准，Final integration 接收全部标准。缺失字段、重复 ID、未知节点、未知工具或具有项目写入副作用的自动验证器会在执行前拒绝。
- Planner 输出先做协议与结构校验：支持从前后说明中提取完整 JSON；步骤类型限制为固定枚举，纯读取或检查语义不能声明为 `FILE_WRITE`。解析失败、DAG 无效或阻塞性空工作区纯检查步骤会触发有界修复。修复前清空 Planner 历史，并把原始任务、失败原因、无效输出预览和固定 schema 放入新请求。默认修复 2 次，可通过 `devcli.team.planner.repair.max.attempts` / `DEVCLI_TEAM_PLANNER_REPAIR_MAX_ATTEMPTS` 调整到 `[0, 3]`。
- Planner 不调用工具；空工作区属于合法状态。目录和文件存在性检查不能成为阻塞实现的独立步骤，必要检查并入首个实现步骤并采用“若不存在则创建”语义。
- SubAgent 按单次执行保存结构化工具证据。Worker 最终 content 为空但存在 `ToolStatus.SUCCESS` 时，由 Orchestrator 合成有界证据摘要进入 Pre-Review / Reviewer；无论 content 是否为空，只要没有成功工具证据，独立 Worker 协议守卫都会追加一次强制执行上下文，阻止文字方案或伪代码冒充执行结果，并要求文件任务调用 `write_file`、分析任务调用读取工具，并在该请求首轮设置命名 `LlmClient.ToolChoice`：FILE_WRITE / INTEGRATION 选择 `write_file`，COMMAND 选择 `execute_command`，其他类型选择 `list_dir`。Anthropic Messages 请求映射为 `tool_choice: {"type":"tool","name":"..."}`，OpenAI-compatible 请求映射为命名 function choice。FILE_WRITE / INTEGRATION 步骤出现成功 `write_file` 批次后直接以结构化证据结束当前 Worker 执行；强制修复中的指定工具也采用同一完成策略，避免再发起无必要的 LLM 收尾请求。Provider 忽略命名工具选择时，`AgentExecutionEngine` 追加一次严格 JSON 工具信封请求；SubAgent 只接受单一完整 JSON 对象、目标工具名及对象参数，拒绝 reasoning、Markdown、代码围栏、尾随文本和未知字段，解析后仍通过原有工具参数校验、能力范围、HITL 与策略管线。工具失败时才恢复 AUTO 进入下一轮纠正，最终仍无成功证据才返回“执行结果为空”。
- SubAgent IOException 返回 ERROR 类型
- Planner 共享主 ToolRegistry；副作用 Worker 使用 `WorkspaceExecutionSession` 创建隔离 ToolRegistry，Pre-Review 与 Reviewer 在同一隔离目录读取真实产物，MemoryManager 继续共享角色裁剪视图。
- Plan `Task`、Multi-Agent `ExecutionStep` 和 checkpoint 共用 `ExecutionArtifact`，统一保存 state、output、summary、modifiedResources、error、attempt、startedAt、finishedAt。
- checkpoint 协议版本 9 通过 `RecoveryState` 恢复共享 artifact、验收方式、验证器、适用节点、pending PatchSet 写前日志及文件权限、稳定子代理身份、步骤分配、消息游标、有界且按步骤归属的 AttemptDigest、已消耗的在位重做次数和重做失败现场；旧协议缺失适用节点时迁移为 `FINAL`，缺失验证字段时迁移为人工验收。恢复保持原步骤绑定和原重做额度，只注入 schema 兼容的最近摘要及当前步骤失败尝试，不恢复完整私有对话。
- Plan Worker 每次尝试都通过隔离 ToolRegistry 的 `runWithResourceLease(stepId, ...)` 绑定资源租约上下文，并在 finally 中释放；并行工具线程显式继承步骤租约归属。ToolRegistry 统一托管 `ResourceLeaseMaintenance`，project fork 共享同一个定时线程；最后一个注册关闭后停止。默认每 60 秒清理过期租约，可通过 `devcli.resource.lease.cleanup.interval.seconds` / `DEVCLI_RESOURCE_LEASE_CLEANUP_INTERVAL_SECONDS` 调整。
- Plan 副作用步骤在隔离工作区执行；`ToolEffect` / `ToolAccessScope` 在工具管线中强制限制非隔离任务只能使用只读能力。隔离命令和 Pre-Review 默认通过受限 Docker 执行，无网络且失败关闭；每次执行使用唯一容器名，超时或取消时终止客户端进程树并有界执行 `docker rm -f`；显式 `HOST_WARN` 只允许 Maven 离线执行 `clean/validate/compile/test-compile/test/package/verify`、`javac` 与只读 Git，拒绝命令行指定的任意 Maven 插件和发布阶段，并输出主机风险提示，不自动回退。PatchSet 逐文件流式哈希，只保留变更内容；JVM 公平锁与跨进程文件锁共同串行提交，锁缓存按活跃使用者计数退役。应用前保存 before/after 哈希和原文件备份；备份限制为当前所有者访问，孤儿日志按 TTL 清理，恢复时提升完成、继续待执行或回滚，失败回滚会报告具体路径。
- `PreReviewVerifier` 独立负责 Maven/javac 选择、Java 文件扫描、超时、进程输出解码和失败摘要。Maven 多模块根目录即使没有根级 Java 源码也执行硬检查，存在 `mvnw` 时优先使用 Wrapper；结果区分代码失败与超时、取消、沙箱不可用、依赖或工具链缺失等环境失败。Reviewer 声称 TOOL 标准通过时，声明验证器必须出现在本轮真实成功工具调用中；实际执行的 Pre-Review 命令计为 `execute_command` 证据。Reviewer 重试和节点重做耗尽后，结果显式输出失败步骤、额度、最后原因、checkpoint 和人工处理选项。
- `Planner.replan()` 不是 Agent 循环，没有工具调用权，因此失败后重规划只读取 ExecutionArtifact 的最小结构化产物事实，不读取完整任务 result 作为主要依据。

### HITL System

- 危险工具：write_file(中) / edit_file(中) / execute_command(高) / create_project(中) / revert_turn(高)
- 审批选项：y(批准) / a(全部放行) / n(拒绝) / s(跳过) / m(修改参数)
- fail-safe：连续 5 次无效输入判为 REJECTED
- 并发：requestApproval 整体 synchronized

### HITL Enhancement (Policy Layer)

- `PathGuard`：路径限定在项目根内（绝对路径外逃 / `..` 穿越 / 符号链接逃逸）
- `CommandGuard`：fast-fail 黑名单（sudo/rm -rf/mkfs/dd/fork bomb/curl|sh 等）
- `ResourceLimit`：write_file / edit_file 5MB / execute_command 60s + 8KB 输出
- `AuditLog`：JSONL 字段 timestamp/tool/args/outcome/reason/approver/durationMs
- 拦截顺序：HitlToolRegistry → ToolRegistry → 策略层。用户无法批准策略拒绝的请求

### Parallel Tool Execution

- `executeTools()` 固定线程池并行，默认最多 4 个并发，返回结果保持原始顺序
- 并行执行线程显式继承调用方的 `ToolAccessScope`、资源租约步骤和 SkillContextBuffer 快照，避免 ThreadLocal 回退到 FULL 或丢失修改文件归属
- `search_tools` 缓存键包含当前能力范围，不能从 FULL 缓存泄露副作用工具到只读任务
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
- MCP `tools/call` 请求会携带 `_meta.progressToken`；同 token 的 `notifications/progress` 会按最近 5 条追加到工具结果文本
- MCP 工具结果被尺寸治理截断或落盘预览时会在返回文本末尾标记折叠分类：`INLINE_TRUNCATED` 或 `PERSISTED_PREVIEW`
- notifications 路由：tools/list_changed → 工具全量替换，resources 变化 → cache 失效

### Chrome DevTools MCP

- 默认 server：chrome-devtools，`npx -y chrome-devtools-mcp@latest --isolated=true`；Windows stdio 启动前按 `PATH` / `PATHEXT` 选择可执行的 `.cmd` / `.bat` 包装器
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
- allowedTools 为空表示不启用工具限制；声明 allowedTools 的已加载 Skill 会把后续工具调用限制在当前 SkillContextBuffer 白名单内。项目级 ToolRegistry fork 使用 `SkillContextBuffer.copy()` 冻结副本，并行任务不会互相消费正文或污染允许工具集合；/clear 清空当前实例状态

### Post-Compact Restore

- ConversationHistoryCompactor 压缩成功后会在摘要确认消息之后、保留尾部之前插入 `[压缩后恢复上下文]`
- `SessionMemory` 的恢复段不复用完整 system prompt 视图，而是按最近读写文件、未完成子任务状态、关键工具结果引用、RAG 证据 epoch 输出短结构化上下文
- Agent / PlanExecuteAgent / SubAgent 会在恢复段追加 MCP 工具状态和本地 SkillContextBuffer 的已加载 Skill、context、allowedTools 与内容摘要
- 恢复段通过 `PostCompactRestoreContext` 做统一预算控制和行级去重；SubAgent 恢复区使用 Planner / Worker / Reviewer 角色视图裁剪，Planner 不携带工具证据，Reviewer 不携带会话临时事件
- RAG 证据从 `search_code` 的工具结果强类型旁路载荷进入 `SessionMemory`；尺寸治理、只读结果缓存和批量执行结果都会保留该载荷。展示文本不再嵌入结构化 JSON；旧 JSON 与旧展示文本只用于历史兼容，typed negativeFact 仍会即时清理旧 symbolVersion。

### MicroCompact

- Microcompact 在 LLM 摘要前执行，不删除消息，保持 assistant tool_call 与 tool result 配对。
- 单条超大工具结果会落盘到 `.devcli/microcompact_tool_outputs/<session>/`，消息中保留 `<microcompact_boundary>`、toolCallId、originalChars 和 storedPath。
- 最近 2 个 user round 之前的旧 tool_result 会按 toolCallId 成批折叠为 boundary 引用；最近轮次保留原文，避免影响当前任务。
- `SessionMemory` 压缩后恢复区遇到 microcompact 工具引用时，只输出 toolCallId / originalChars / storedPath，并按 storedPath 或 toolCallId 去重。

### Terminal Renderer

- 活跃实现：InlineRenderer（默认）/ PlainRenderer
- 环境变量：`DEVCLI_RENDERER=inline|plain`
- `lanterna`、`tui` 和旧 `DEVCLI_TUI=true` 在兼容期输出迁移提示并映射到 Inline；Main 不再进入 TuiBootstrap
- `DEVCLI_NO_STATUSBAR=true`：禁用底部状态栏
- `NO_COLOR=1`：禁用 ANSI 颜色
- 当前开屏 Banner 是无右侧盒线边框的简洁布局，避免 ANSI/CJK 字宽导致竖线错位
- InlineRenderer 复用 JLine 4 的编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`；`/help`、补全和历史导航共用命令清单与 LineReader
- ReAct 且 HITL 关闭时，活动轮次继续由主 LineReader 接收输入：普通文本进入容量为 8 的 FIFO 队列，`/now <任务>` 先入队首部再取消当前轮次，`/cancel` 只取消当前轮次；模型结束信号唤醒输入后保留未提交草稿。Plan、Multi-Agent 与 HITL 保持原输入所有权，禁止队列读取器和审批读取器并发访问终端
- BottomStatusBar 是 JLine `Status` 托管的底部 dock：由 JLine 负责滚动区域和状态行位置，不再手写 `\n`、`moveUp`、`CLEAR_TO_EOS` 或绝对光标行号；dock 上层展示 YOLO/HITL 与 MCP/Skill 摘要，下层展示 model、phase、ctx、token、cost、elapsed 与 cwd
- 重定向输入默认 UTF-8；`DEVCLI_TERMINAL_ENCODING` 可覆盖旧式控制台编码，`DEVCLI_TERMINAL_FORCE_ANSI=true` 可为误判终端启用 xterm-256color
- plain / inline 的 HITL 后续文本复用主 LineReader；inline 首选项继续通过 raw mode 单键读取，避免独立 BufferedReader 抢读残留换行
- InlineRenderer 不使用独立 JLine `Display.update()` 维护 thinking 临时区；真实终端验证发现独立 Display 会在 transcript/status 输出后从错误位置向上清屏。当前实现用固定高度 live 区重写自身行，content/tool 边界先清理 live 区再追加 transcript。
- 交互期输出优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner`、`AgentOrchestrator` 都可接收同一个 renderer 输出流，避免绕过 inline renderer 直接写 stdout
- `CodeIndex` 通过 `ProgressListener` 上报索引开始 / 文件数量 / 进度 / 完成或失败，`/index` 绑定当前 renderer 输出流；索引阶段按文件批量生成 chunk embedding，批量失败或返回数量异常时逐条降级并保留成功 chunk；内部异常细节写 logger

### LSP Diagnostics (Phase 17)

- write_file 成功后对 Java 文件做 JavaParser 语法诊断
- 诊断作为合成 user message 注入下一轮 LLM 请求
- `DEVCLI_LSP_ENABLED=false` 关闭

### Git Side-History Snapshot (Phase 18)

- side-git 在 ~/.devcli/snapshots/ 维护独立仓库（JGit，不依赖系统 git）
- pre-turn 同步，post-turn 异步
- 每次新建快照后按 `devcli.snapshot.max` 重写 side-history，只保留最新 N 条快照
- 裁剪后调用 JGit `autoGC`，由其原生 loose object / pack 阈值决定是否后台维护；不再手写对象遍历，也不在每次裁剪时强制完整重打包
- revert_turn 纳入 HITL/AuditLog，恢复前先创建 pre-restore 快照

### Prompt Layering (Phase 19)

- 组装顺序：base → personality → mode → approval → project_context → skills → context_mgmt → handoff
- 覆盖优先级：jar 内置 < 用户级 ~/.devcli/prompts/ < 项目级 .devcli/prompts/
- 必要校验：base.md 和最终 prompt 必须包含 `## Language`

### Async Tasks + Runtime API (Phase 20)

- `RunStore` 是 CLI turn、Runtime turn 和后台任务的运行状态事实来源；`DurableTaskManager` 只负责后台提交与 Worker 领取
- 默认数据库统一为 `~/.devcli/runtime/runtime.db`；旧 `tasks.db` 使用只读连接导入，重复 id 不覆盖
- CLI: /task, /task list, /task add, /task cancel, /task log
- Runtime API: `serve --http --port 8080`，仅 127.0.0.1，需 API Key
- 端点：POST /v1/threads / POST /v1/threads/{id}/turns / GET /v1/threads/{id}/events
- Runtime API 的 turn 通过 `KeyedSerialExecutor` 调度：同 key 的通道创建、入队和空通道删除使用原子 compute，杜绝旧通道与新通道并存；底层调度拒绝会通知全部等待者，单个 turn 异常不会阻塞同通道后续任务
- Runtime API 为每个 thread 复用 `RuntimeSessionTurnRunner` / `AgentSessionRuntime`；除普通 turn 外，`POST /v1/threads/{id}/steer` 在当前工具批次后注入 Steering，`POST /v1/threads/{id}/follow-up` 在 Agent 原本准备结束时注入 Follow-up，两个操作都会写入 `queue.updated`
- Runtime API 另提供 `POST /v1/threads/{id}/queue/clear` 清空当前分支待处理队列，以及 `POST /v1/threads/{id}/cancel` 取消当前 turn；清空操作写入 `queue.updated(action=cleared)`，取消不伪造 turn 完成事件
- Runtime 队列快照按 thread/branch 写入 SQLite；会话创建时恢复 Steering / Follow-up，入队和 turn 结束时重写当前分支快照。分支切换只恢复目标分支队列，不复制父分支待处理输入
- Runtime thread 支持事件树分支：`GET/POST /v1/threads/{id}/branches` 列举或从当前可见事件创建分支，`POST /v1/threads/{id}/branches/{branchId}/activate` 切换活动分支；事件和 checkpoint 都保存 `branch_id`，恢复时按 parent_branch_id / fork_event_id lineage 截取，切换后关闭旧 session 并从目标分支重建
- CLI `/session status|tree|fork|use|new|clear|use-thread` 直接操作同一持久事件树；`/branch` 是兼容别名并只提示一次迁移。Tree 切换只重建模型上下文，不修改工作区文件
- thread 上下文从 SQLite 恢复最新压缩检查点，并完整追加检查点覆盖事件之后的已完成 turn；没有检查点时恢复全部已完成 turn，不再固定保留最近 20 轮
- 历史默认达到 32,000 token 时生成持久化检查点，`DEVCLI_RUNTIME_CHECKPOINT_TRIGGER_TOKENS` / `devcli.runtime.checkpoint.trigger.tokens` 可调整，最小 4,000；检查点保存压缩消息、覆盖事件、摘要、token 变化和 `CompactBoundaryMetadata` 运行态快照
- 检查点候选会移除动态 system prompt、reasoning 和图片正文；同时保存压缩 metadata 与消息树快照（稳定 `id`、`parentId`、role、index），当前默认从压缩边界生成线性 parent 链，为后续分支恢复保留协议字段；旧 SQLite 数据库启动时自动补充 `message_tree_json` 列。保存发生在 `turn.completed` 事件之后，失败只写入 `thread.checkpoint.failed`；最新记录损坏时按时间回退到更早可解析检查点
- `RunEvent` 统一表达 reasoning/content delta、工具调用、工具结果、turn 终态和 checkpoint 事件；`AgentExecutionEngine` 将模型 StreamListener 回调转换为事件，再通过适配器投影到既有 Renderer 或 Runtime sink
- `RunEvent` 另外提供 `session.state`（running / idle 等会话生命周期）和 `message.custom`（扩展消息类型、正文、字符串属性）；Runtime session turn 开始和结束会发布状态事件，自定义事件必须经过统一 JSON codec，不允许扩展直接拼接协议文本
- 模型能力由 `ModelCapabilityRegistry` 统一解析 Provider 别名、上下文窗口、输出上限、prompt cache、工具调用、视觉和 reasoning 能力；`LlmClient` 的上下文策略默认从注册表读取，Provider 客户端只保留实例级差异（例如 Anthropic 的配置化输出上限）
- Skill、Hook、MCP server 和 CLI command 的发现元数据统一通过 `ExtensionContract` / `ExtensionRegistry` 表达：稳定 id、kind、来源、启用状态、版本、能力和元数据；Main 启动后把命令、Skill、Hook 和 MCP server 注册进目录，`/skill reload` 会原子替换 Skill/Hook 目录，MCP enable/disable/restart 通过 `McpServerManager` 观察者同步 MCP 目录，CLI Skill/MCP 补全优先从统一目录读取。该目录契约不接管各自执行权限，Skill、Hook、MCP 和命令继续使用原有安全与策略管线
- CLI 活动输入不再使用独立 `PromptQueue` / `ActiveTurnCoordinator`；生产路径唯一使用 `AgentTurnInbox`，由 AgentExecutionEngine 按 Steering / Follow-up 时机注入。旧队列实现已删除，避免两套取消和容量语义并存
- 验证边界：plain renderer 的 `/help` 与 `/exit` 启动烟测已通过；非交互管道不能证明 JLine 补全、方向键、底部 dock 或 HITL 按键行为，未验证前不引入 TUI differential rendering 改造
- Runtime JSON 投影集中维护协议字段和转义，每个 payload 固定携带 `schema_version=2`；工具 arguments 优先保持 JSON 对象，无法解析时保留原文本；工具结果携带 status、error_code、retryable、elapsed_millis、presentation 和 image_count，不持久化图片正文
- Runtime runner 收到事件 sink 后可边执行边写入 SQLite/SSE；如果 Provider 没有产生 content delta，服务端才用最终输出补一个 `message.delta`，避免流式回答重复写入
- 每次交互、后台任务和无头 turn 都在执行线程绑定有效 `RunContext`，其中包含项目路径与取消令牌；已有调用方上下文会跨会话工作线程复用并在任务结束后恢复，只有无外层上下文时才创建临时上下文；预先创建的线程池不读取其他运行的取消状态，线程中断也进入取消语义
- CLI ReAct 通过 `AgentSessionRuntime.adoptOwned(...).runInCurrentContext(...)` 执行，保留输入监听线程创建的 RunContext 和取消令牌；Runtime API 与无头执行分别使用持久或临时 `AgentSessionRuntime`
- 每次执行引擎模型调用通过共享采样协调器注册稳定请求标识、独立取消令牌和请求代次；同标识的新请求原子替换旧请求并取消旧执行线程，作用域关闭时只清理自己的代次，避免旧请求结束时误删新请求
- `HeadlessAgentRunner` 统一管理无头 Agent、ToolRegistry 和 MemoryManager 生命周期；后台任务取消时同时取消对应 RunContext 并中断执行线程
- ToolResultSizeManager 的落盘项目路径来自执行该工具的 ToolRegistry 实例，不再通过静态活动路径跨运行共享

### Controlled Hook Lifecycle

- 生命周期事件：agent_start/end、turn_start/end、message_start/end、tool_execution_start/end；状态机幂等，并在异常、取消和预算出口闭合未结束层级
- `AgentExecutionEngine` 是统一触发点，因此 ReAct、Plan task 和 SubAgent 不维护独立 Hook 顺序
- 配置读取：`DEVCLI_HOOKS_FILE` / `devcli.hooks.file` 指定单文件，否则按用户级 `~/.devcli/hooks.json`、项目级 `.devcli/hooks.json` 合并，项目同 id 覆盖用户定义；上限 64 条
- Hook 动作使用 ToolRegistry 工具名与 JSON arguments，不提供旁路 shell 或 HTTP executor；参数支持 event、project、run_id、iteration、tool_name、tool_call_id、status 占位符
- READ_ONLY / LOCAL_CONTEXT Hook 强制收窄为 READ_ONLY scope；其余 ToolEffect 需要 `allowSideEffects=true`、启用 HitlToolRegistry，且 ApprovalPolicy 必须要求逐次审批；当前 Plan/SubAgent scope 仍可继续拒绝超出能力范围的动作
- `failureMode=warn` 只记录警告；`required` 转换为标准 Agent IOException 失败出口，包括 agent_end 阶段

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
规划后执行 / 计划审阅 / DAG 状态推进 / 失败重规划；冲突分波、并行调度和顺序输出归并委托给 `PlanTaskBatchExecutor`，结果摘要由 `PlanTaskExecutionResult` 统一生成，任务能力范围、隔离工作区、资源租约和 PatchSet 生命周期委托给 `PlanTaskWorkspaceExecutor`

### AgentOrchestrator.java
Plan 顶层流程 / 三角色生命周期 / 配置传播 / `run` 与 `resume` 入口。Planner 修复和 DAG 预处理委托 `PlanCoordinator`，评审门禁委托 `ReviewCoordinator`，checkpoint 恢复策略和 PatchSet 对账委托 `CheckpointCoordinator`，Worker 调度、重试、隔离工作区和归并委托 `StepExecutionCoordinator`；可变状态集中在 `OrchestrationRunState`，上下文和终态报告由 `OrchestrationNarrative` 生成

### AgentExecutionEngine.java
ReAct / Plan task / SubAgent 共用循环；统一取消和预算检查、LLM 调用、assistant/tool 消息协议、结构化工具错误记录与 IOException 出口；路径差异通过 Delegate 钩子注入

### SubAgent.java
可配置角色子代理 / 独立对话历史 / Worker 用工具、Planner/Reviewer 不用；执行循环委托给 AgentExecutionEngine

### Planner.java
LLM 生成计划 JSON / 简单任务最小计划 / 重编号 task_1..N / 依赖计算

### ExecutionGraph.java
Plan / Multi-Agent 共用 DAG 调度与校验；统一普通节点和最终集成节点的就绪规则、缺失依赖检测、环检测和拓扑排序

### ExecutionArtifact.java
Plan Task / Multi-Agent ExecutionStep / checkpoint 共用任务产物；统一状态、输出、摘要、修改资源、错误、尝试次数与执行时间

### ExecutionPlan.java
任务状态 / 进度可视化；可执行任务判定和拓扑排序委托给 ExecutionGraph

### AgentCheckpoint.java
checkpoint 协议版本 9；通过 RecoveryState 恢复共享 ExecutionArtifact、验收方式、验证器与适用节点、稳定子代理身份、步骤分配、单调消息游标、最小摘要、按步骤归属的有界 AttemptDigest、已消耗的在位重做次数和失败现场，保存 PatchSet 写前日志、文件权限与原文件备份，恢复时按文件内容和权限对账并保持原 Worker 绑定和原重做额度；旧协议缺失适用节点时迁移为 `FINAL`，缺失验证字段时转为人工确认，损坏身份拓扑或未来版本明确拒绝

### PreReviewVerifier.java
Reviewer 前 Java 硬验证；封装 Maven/javac 命令、扫描、超时、输出解码和失败摘要，无 Maven 时使用 javac 参数文件避免命令行过长

### ToolRegistry.java
14 个内置核心工具（含 `edit_file` 精确替换、`confirm_memory` 一次性敏感确认和 `grep_code` 实时精确文本搜索）+ MCP 动态工具 / executeTools() 并行入口 / ToolInvocation / ToolExecutionResult；`ToolExecutionPipeline` 按阶段执行取消、存在性、能力范围、Skill 权限、参数校验、HITL、审计、策略和结果治理；`ToolOutput` / `ToolExecutionResult` 携带 status、errorCode、retryable、imageParts 和 modifiedResources；内置 Provider 通过结构化执行器直接保留参数错误、策略拒绝、命令退出、超时和取消状态；HITL 作为管线中间件，不再覆写 executeTool；默认只注入内置核心工具和已激活 MCP 工具；ReAct、Plan 和 Multi-Agent turn 开始前会按当前用户输入预激活匹配到的 MCP 工具；`search_tools` 使用工具索引缓存，MCP 工具变更后自动失效，命中 MCP 工具后激活到后续工具定义；未知工具会返回 `search_tools` 引导和 query 示例

### Workspace Package
`WorkspaceBackend` 定义物化后端，`WorkspaceBackendFactory` 默认自动选择：项目根是 Git 仓库时使用原生 worktree，共享 Git 对象后叠加当前脏文件、删除文件、未跟踪及被忽略文件；常见 `.env`、凭据和密钥文件在三类后端中统一过滤，不进入 Worker 工作区或 PatchSet；非 Git 目录优先使用 `FileSystemCowWorkspaceBackend`。Linux 通过 GNU `cp --reflink=always` 强制文件系统 reflink；Windows 11 24H2 / Windows Server 2025 及以上版本只在 ReFS 上使用系统块克隆路径；其他平台、克隆失败、输出缺失或源目标哈希不一致时清理部分工作区并回退 `CopyWorkspaceBackend` 有界并行复制。实现不使用硬链接，避免直接写文件或外部命令修改共享 inode。worktree 和写时复制物化后删除排除目录与符号链接，worktree 创建、注销和 prune 均在项目级公平锁与跨进程 `FileLock` 内执行；复制完成等待和线程终止都有明确超时，线程中断会向调用方传播；`WorkspaceCleanupPolicy` 通过 TTL 和跨进程文件租约清理孤儿目录；`WorkspaceExecutionSession` 管理隔离 ToolRegistry 生命周期；`ProjectCommitCoordinator` 使用基于项目真实路径哈希命名的 JDK `FileLock` 串行化同项目跨进程提交；PatchSet 逐文件流式哈希，未变化文件不读取完整内容，并负责 `afterHash` 内容一致性、可执行标记、哈希冲突预检、路径与链接边界、原子应用和可观测回滚。文件锁默认位于 `~/.devcli/locks/project-commit/`，网络文件系统的锁语义取决于底层实现

### MCP Package
McpServerManager / McpClient / JsonRpcClient / StdioTransport / StreamableHttpTransport / McpSchemaSanitizer / resources/ / mention/ / notifications/

### 旧 TUI 兼容
旧 `tui/` 实现已经删除；`lanterna`、`tui` 和 `DEVCLI_TUI=true` 仅作为配置兼容值映射到 Inline

### LLM Clients
- AnthropicClient：默认 provider，Claude / Anthropic Messages 原生兼容端点
- OpenAiClient：OpenAI 官方或 Chat Completions 兼容端点；只有模型名包含 `deepseek` 时才回灌 `reasoning_content`，兼容网关 URL 不触发
- GLMClient：glm-5.1，glm-5v 开头切多模态接口
- DeepSeekClient：deepseek-v4-flash，回灌 thinking 历史里的 `reasoning_content`
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
# DEEPSEEK_BASE_URL=https://api.deepseek.com
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
# DEVCLI_LOG_DIR=~/.devcli/logs
# DEVCLI_TERMINAL_ENCODING=UTF-8
# DEVCLI_TERMINAL_FORCE_ANSI=false
# DEVCLI_LOG_MAX_HISTORY=7
# DEVCLI_LOG_MAX_FILE_SIZE=10MB
# DEVCLI_LOG_TOTAL_SIZE_CAP=100MB
# DEVCLI_SNAPSHOT_ENABLED=true
# DEVCLI_SNAPSHOT_MAX=50
# DEVCLI_SNAPSHOT_EXCLUDES=.git,.devcli/snapshots,target,node_modules,dist,.idea,*.class,*.jar
# DEVCLI_SNAPSHOT_DIR=/Users/yourname/.devcli/snapshots
# DEVCLI_RESOURCE_LEASE_CLEANUP_INTERVAL_SECONDS=60
# DEVCLI_TUI=true
```

---

## Runtime Reliability And Memory Lifecycle

模型调用统一通过 `LlmException` 表达错误，`LlmErrorCode` 区分认证、限流、过载、超时、网络、参数、上下文超限、内容过滤、服务端和响应格式错误。Anthropic 与 OpenAI-compatible 基类复用同一 `LlmRetryExecutor`；限流、过载、超时、网络和 5xx 使用指数退避与 jitter，其他错误立即返回。流式 listener 已收到任何 reasoning/content delta 后，当前调用转为不可重试，避免重复输出和重复工具调用。OpenAI-compatible 工具调用聚合同时支持标准增量片段、累积快照和完整字段重复发送：相同或回退快照忽略，扩展快照替换，普通片段继续追加，避免生成重复工具名或拼接多个完整 JSON。SubAgent 返回错误时保留 `code` 与 `retryable`，Orchestrator 优先读取标准重试标记，旧文本规则只用于兼容。

`ConversationHistoryCompactor` 在摘要尺寸治理后、重建 history 前调用 `CompactionSemanticGuard`。守卫从待压缩原消息中提取必须、禁止、默认值、命令、版本、端口、目录、验收和配置赋值等关键约束；结构化声明按主题对账并只保留最新值，否定约束必须在包含同一语义锚点的摘要分段中保留否定极性；缺失约束直接以提取式恢复段补回，并在摘要上限内优先保留。

`MemoryOrganizer` 通过 `/memory organize` 生成结构化整理计划，通过 `/memory organize apply` 应用低风险合并。库存最多携带 100 条可召回记忆，每条正文限制 300 字符并编码为 JSON 数据；解析失败最多修复一次。模型只能提出 KEEP、MERGE、REVIEW、REJECT 候选，程序重新校验来源标识、类型、主题、审核状态、覆盖范围和计划置信度。只有同主题、同类型、全部 UNREVIEWED、覆盖该主题全部可召回条目且计划置信度不低于 0.9 的 MERGE 可以自动应用；其余候选只在本次运行报告中标记为需要人工复核，或由策略拒绝；当前不持久化复核队列。

`MemoryEntry` schema 5 持久化 `revision`、一等字段 `MemoryKind`、`expiresAt/expiry_mode`、`recallCount/lastRecalledAt`、`validatedUseCount/lastValidatedAt` 和结构化 `MemoryEvidence`。`MemoryKind` 的 FACT/PREFERENCE/PROCEDURE/LESSON/DECISION 与存储用途 `MemoryType` 分离，旧条目从 metadata 兼容迁移。证据字段包括 confidence、sourceQuote、reasoning、reviewState、conflictsWith；HIGH/MEDIUM 需要非空真实快照原文，否则降级。自动晋升还必须为 HIGH 且来源引用能在任务快照中解析，落库状态为 `CURATED`；人工确认后为 `REVIEWED`。来源正文、任务标识、捕获时间和 SHA-256 随记忆固化，即使原会话删除仍可审计。初始 TTL 固定；普通 Prompt 召回只记录观测，只有用户确认或同值重复显式保存等强验证信号才按累计次数延长 TTL。REJECTED 条目保留审计，但从关键词检索、语义检索和 Prompt 注入中排除。LongTermMemory 在检索、读取、计数和类型筛选前把到期条目软归档，保留 SQLite 事实与向量索引供审计和恢复，不做检索期物理删除。显式 subject 内容变化以及配置赋值、默认值、当前值、设置值和正反使用声明等可解析冲突会记录结构化 conflictsWith，同时保留 `conflict_detected/conflict_with` 兼容 metadata；旧条软删除，新条 revision 递增；相同主题同值的可确定改写自动去重。

`ToolInvocationFingerprint` 对 JSON 对象字段排序，统一查询字段大小写、Unicode NFKC 等价字符、冗余空白和路径分隔符；正则 pattern 保持大小写敏感，避免相似但不等价调用共享缓存。AgentBudget 使用该指纹判断语义重复；ToolRegistry 只缓存成功、无图片的 READ_ONLY 结果，默认 128 条、30 秒。项目路径切换或任何非只读工具进入执行阶段时清空缓存，禁止把副作用前的陈旧读取跨状态复用。

## Benchmark Evaluation

评测入口位于 `src/test/java/com/devcli/benchmark/`，默认不进入快速回归。RAG benchmark 支持 CodeSearchNet Java 公共 test split，并统一计算 Recall@5、MRR@5、nDCG@5；长文档型 definition 查询直接走 semantic route，短符号和调用链查询继续使用 keyword、semantic、graph、RRF 与可选 rerank。

Agent benchmark 对同一组隐藏检查任务比较单 Agent 与 Planner/Worker/Reviewer，任务成功要求 LLM 流程完成且隐藏检查全部通过。Memory benchmark 统计写入策略准确率、低价值拦截率、Recall@5 和注入命中率。Compression benchmark 在 230k token 生产阈值下执行多次真实摘要，再通过分层事实问答统计保真率。

公开集合扩展由 `PublicBenchmarkCatalog` 读取固定配置并校验原始文件 SHA-256 与官方 harness。`PublicBenchmarkReadinessIT` 验证 SWE-bench Lite、LongMemEval、LongBench 和 RULER 数据入口；`RulerDatasetGenerationIT` 直接调用固定版本官方生成器，避免 Windows shell 对换行模板的破坏；`PublicLongContextBenchmarkIT` 生成 LongMemEval hypothesis、复用 LongBench 官方 prompt/数字指标和 RULER string match；`SweBenchLiteAgentBenchmarkIT` 生成官方 predictions JSONL，并可通过 Linux Docker harness 执行 resolved 评测。代理指标与官方指标在报告中分字段保存。

公开 benchmark 原始报告默认写入 `target/benchmark-reports/`。旧的自建任务聚合器和结果文件已经退役；评测方法、固定版本、复现命令和结果边界见 `docs/benchmark-evaluation.md`。

## Test Coverage Summary

常规测试覆盖偏向：解析、计划结构、RAG 核心、Multi-Agent 编排、HITL 策略、策略层拦截、MCP 协议、资源输入层、长上下文策略与 Skill 加载。

常规测试不覆盖真实 LLM、真实 Embedding API、真实 MCP server 和终端完整手工体验；对应真实链路通过显式启用的 benchmark 或手工验收执行。

完整测试类列表：CliCommandParserTest / MainBrowserCommandTest / PlanReviewInputParserTest / MainInputNormalizationTest / ExecutionPlanTest / MemoryEntryTest / ConversationMemoryTest / LongTermMemoryTest / MemoryRetrieverTest / MemoryManagerTest / ExplicitMemoryHintsTest / ContextProfileTest / PlanExecuteAgentTest / AgentMemoryHintTest / AgentRoleTest / AgentMessageTest / AgentOrchestratorTest / EmbeddingClientTest / SearchResultTest / NetworkPolicyTest / HtmlExtractorTest / WebFetcherTest / SearchProviderFactoryTest / ZhipuSearchProviderTest / VectorStoreTest / CodeChunkerTest / CodeAnalyzerTest / CodeIndexTest / ApprovalPolicyTest / ApprovalResultTest / HitlToolRegistryTest / TerminalHitlHandlerTest / ToolRegistryTest / BrowserSessionTest / BrowserConnectivityCheckTest / SensitivePagePolicyTest / BrowserGuardTest / McpSchemaSanitizerTest / McpConfigLoaderTest / JsonRpcClientTest / McpToolBridgeTest / McpResourceCacheTest / AtMentionParserTest / AtMentionExpanderTest / AtMentionCompleterTest / NotificationRouterTest / PathGuardTest / CommandGuardTest / AuditLogTest / SkillFrontmatterParserTest / SkillRegistryTest / SkillStateStoreTest / SkillBuiltinExtractorTest / SkillContextBufferTest / SkillIndexFormatterTest / LoadSkillToolTest / SkillCommandHandlerTest
