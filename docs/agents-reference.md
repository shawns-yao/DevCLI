# AGENTS Reference: Detailed Feature Behavior

This document contains detailed feature behavior descriptions, configuration reading orders, and implementation notes that were previously in `AGENTS.md`. Consult this when working on specific modules.

For the primary entry point, see `/AGENTS.md`.

---

## Configuration Reading Orders

### Tool Call Governance

`delete_files` 增加为第 16 个核心工具，以 `paths` 数组明确指定普通文件；不递归、不删除目录或符号链接、不展开通配符。预检在 Schema 后、HITL 前完成，保存路径及哈希/权限快照；执行复用委派写范围、资源租约、版本闸门、项目提交锁和 PatchSet 整批冲突检查/失败回滚，并发布修改资源与关键工作记忆证据。单文件 5MB、总计 20MB、最多 500 个。成功删除不进入回收站，恢复依赖已有快照或备份。

HITL 开启时，单次清单数量达到 `DEVCLI_DELETE_APPROVAL_THRESHOLD` / `devcli.delete.approval.threshold`（默认 50，范围 1–500），或任务写路径授权未覆盖清单，均逐次确认；不复用工具/服务器全部批准。审批展示完整目标、数量、非递归说明和恢复限制；审批修改参数时拒绝本次执行，要求重新提交清单。可识别 Shell 删除也逐次确认且不声称统计目标；此检测不是完整 Shell 解析。原 `apply_patch` 审批不变，数量阈值不覆盖补丁、不跨调用累计；关闭 HITL 仍保留路径硬拒绝。

共享执行内核在可见工具超过 12 个且存在 `search_tools` 时，复用工具搜索排序，选择 5 个候选工具，并保留权限内的 `search_tools`、`read_tool_result`、`delegate_task`、`delegate_control` 及命名强制工具。搜索返回的结构化命中优先进入下一轮候选；候选只能来自当前可见定义，路由不是授权边界。排序目前基于名称、描述和 Schema 关键词，不保证自然语言语义召回率。显式并行 fork 的冻结定义保持不变，不进行逐任务路由，以维持已有缓存约定。

工具发现与执行同时检查能力范围、显式工具白名单和 Skill 权限。权限拒绝返回当前允许的工具示例；未知工具返回 `UNKNOWN_TOOL` 并提示搜索，不再把不存在的工具误报为快照失效。Schema、业务校验和审批继续走原管线。失败消息附带状态、错误码、retryable 和分类处理建议；不自动修参、不自动重放副作用操作。现有执行范围承担权限约束，尚未新增 diagnosis/repair 业务阶段状态机，也没有以任务关键词推断授权。

每个执行预算默认最多 20 次工具调用、连续失败 3 次；单工具上限默认等于总调用上限，避免同一读取工具访问不同文件时被额外限制为 5 次。系统属性分别为 `devcli.tool.budget.max.calls`、`devcli.tool.budget.max.per.tool`、`devcli.tool.budget.max.consecutive.failures`，只读取正整数系统属性，缺失或非法值使用默认值。父预算创建时冻结这些限制，子预算继承，不重新读取运行中变更的配置。父子循环共享总调用与单工具额度；不同工具的连续失败也会触发退出，成功结果重置连续失败计数。整批调用在执行前原子预留，余额不足时整批拒绝并按原调用 ID 配对结果；失败、缓存命中和委派本身均消耗调用额度。额度恰好用尽后仍允许模型生成最终答案，新的工具批次才被拒绝。各 Plan step 是否共享额度仍由既有预算装配决定，不代表整个 DAG 共用 20 次额度。

搜索命中的候选优先级仅保留到下一次模型请求，随后重新按任务输入排序；它不是持久工具授权，也不会长期占用候选名额。排序前一次性计算每个工具的得分，搜索索引使用单次可用工具快照，避免重复权限扫描。工具正常返回结构化失败时，审计按状态记录：SUCCESS 为 allow、REJECTED 为 deny，其余为 error；失败原因只记录状态与错误码，不新增工具正文落盘。

短期缓存按执行循环身份、步骤、能力范围和规范化参数隔离；并行工具显式继承循环身份，子循环使用独立身份。实时文件、代码、网络、记忆查询及 MCP 结果不缓存；其余既有可缓存只读工具沿用 TTL。非缓存工具前后失效缓存，失效代数及活动计数避免并行期间把旧结果重新写回。缓存不是通用资源版本证明，其他只读扩展若依赖外部可变状态，仍需提供可验证的版本协议后才能可靠复用。

交替循环保护比较真实只读调用参数与返回文本哈希：长度 2～4 的周期连续出现三次即停止，不接受模型自报的 observation_key 绕过。写入、本地状态操作、失败、图片和无法比较的富证据会清除观察窗口；文件分页元数据参与比较。改变参数或返回内容不会命中该检测，但仍受调用额度约束。已有连续同调用提醒继续保留，新熔断原因通过 `tool_loop_guard` 与失败指导事件输出。

### Default Agent Delegation

默认入口由主 Agent 使用 `delegate_task` 按需调用子 Agent；它不是 `/plan` 的替代命令，也不预先构造 DAG。复用执行内核、现有工具并行池、审批锁、隔离工作区和补丁提交服务，没有第二套调度框架。

委派建议先经过 `DelegationPolicy`，其判定只保留一条语义判据：`task_spec` 声明的执行粒度必须是多轮 Agent 循环，声明为单工具操作时拒绝、由主 Agent 直接完成。参数结构校验（`task_spec` 的 inputs、scope、done_condition，以及 deliverable 与 Worker 的 allowed_write_paths）由工具 schema 与语义校验层承担，以参数错误返回，不再表现为策略拒绝——模型据此能区分「参数写错了」与「这个任务不该委派」，前者它自己能修。程序还检查共享预算、显式工具权限和上游报告引用，在创建子模型和工作区之前拒绝不满足条件的请求；拒绝结果回传主 Agent，不自动改写成其他工具调用。单会话累计派出量有配额（默认 200，可通过 `devcli.delegate.max.per.session` / `DEVCLI_DELEGATE_MAX_PER_SESSION` 设置为 1～10000），达到上限后拒绝后续委派并提示由主 Agent 接手；真实总量仍由父子共享的 Token 与轮数预算控制，配额的作用是让「委派是有限资源」成为确定信号。子 Agent 不继承父会话历史，契约是唯一信息通道，因此工具说明要求显式传递任务、范围、完成条件与必要背景。

收益评估不再是准入条件，只写入 `delegation.policy` 事件供事后诊断。评分为启发式代理指标：契约基础收益 2，加 Worker 写入作用面隔离收益 0～2（不超过 3 个无通配符且带扩展名的具体路径得 2；仅满足数量少或全部具体路径得 1；其余得 0）；只读角色固定使用中性值 1，不借无效写入声明加分。成本为启动成本 1、context 与 task_spec 合计超过 8000 字符的传递成本 2，以及上游报告传递成本 1。长输入不增加隔离收益，也不证明父状态耦合；当前计费阈值并非完整 Token 成本估算。分数低于告警线（默认 2，可通过 `devcli.delegation.yield.advisory.threshold` / `DEVCLI_DELEGATION_YIELD_ADVISORY_THRESHOLD` 设置为 1～10）时事件标记 `yield_low=true` 并给出建议，但**不影响放行，也不回灌模型**——把分数告诉模型只会诱导它修改声明措辞来「过门槛」，而任务实质不变。评分不证明实际收益；执行粒度与输入封闭性仍由模型声明。`delegation.policy` 同时记录放行结论与诊断分数，不等于后续权限和预算检查已通过。现有并行池、资源租约及版本冲突检查继续生效。

| 角色 | 可用能力 | 上下文 |
| --- | --- | --- |
| explorer / planner / reviewer | 只读工具和本地 Skill 上下文 | system 规则快照、Skill 快照、显式 task/context、自己的工具结果 |
| worker | 以上能力，加隔离项目修改和受限命令 | 同上；不自动继承父会话或长期记忆 |
| 主 Agent | 保留原有工具及委派能力，负责最终验收 | 自己的会话、子任务有界报告和已归并文件清单 |

子 Agent 不能递归委派、控制其他子任务、直接读写长期记忆或执行外部副作用。`search_tools` 与最终执行管线同时过滤权限，不能通过猜测工具名绕过；MCP 继续使用已有信任策略。项目内代码可读取，不将任务描述中的文件范围冒充操作系统级访问隔离。`read_tool_result` 仅允许受限运行读取本运行生成的结果，主 Agent 可以恢复历史结果。子任务消息不混入父历史；可恢复记录只保存子任务自身历史，不保存父会话、system 消息、reasoning 或图片。

主 Agent 可通过现有 `context` 与 `constraints` 显式投影必要记忆及约束；这不是自动继承，也不授予子 Agent 长期记忆读取权限。报告中的 `summary` 仍是模型陈述，`evidence` 是 Runtime 捕获的最多 64 条近期工具观察，附调用标识、工具状态、错误码、各最多 512 字符的脱敏参数与输出摘录，以及已有 `result_ref`。`dead_ends` 保留最多 32 条失败尝试；失败不代表该方案永久不可行。`unresolved_mutations` 与 `open_questions` 标明未解决问题，不自动把模型总结填入 `facts_discovered`。

委派报告在 JSON 序列化前统一写入 `report_security.content_trust=UNTRUSTED`。默认净化模型文本及报告内工具摘录中的 `<system-reminder>`、`<previous_response>` 和行首角色前缀，只增加反斜杠，不删除正文；命中模式记录在 `report_security.matched_patterns`。可通过 `devcli.delegation.report.sanitization.enabled` / `DEVCLI_DELEGATION_REPORT_SANITIZATION_ENABLED` 关闭形态净化，但不移除不可信标签。该机制不识别自然语言包装的恶意建议，主 Agent 仍须核验实际文件和工具证据，任何副作用继续经过权限策略、HITL、工作区与 PatchSet 链路。详见 ADR 0008。

模型错误、预算或轮数退出、取消、未解决副作用失败、独立 Reviewer 拒绝和提交冲突均保留已有证据。`knowledge_outcome=PARTIAL` 只表示曾取得成功工具观察，不证明任务验收通过。`patch_status` 区分 `NOT_APPLICABLE`、`NOT_APPLIED`、`NO_CHANGES`、`APPLIED`、`COMMIT_UNKNOWN` 和 `ROLLBACK_INCOMPLETE`；清理失败不能把已确认提交的文件改报为未提交。未提交候选不进入 `modified_resources`，候选哈希在已生成的 `patches` 中保留。

`DelegationState` 由主 Agent 持有，不随用户回合重建。当前会话报告缓存最多 64 条、已知记录合计最多 64MB，优先保留未解决问题、已提交产物、工具证据，再保留普通报告；同等价值按最近写入或引用淘汰。项目私有 JSON 记录原子写入 `~/.devcli/delegations/<projectHash>/`，新 Agent 可凭原报告 ID 读取同项目记录；缓存淘汰同时尝试删除对应文件，不扫描或自动清理其他进程的全部历史。单记录最多 32MB，子任务历史正文最多 400000 字符，恢复补丁最多 500 个文件、20MB，单文件最多 5MB。上游引用准入时固定不可变快照；仍未实现依赖图保留及向工作记忆的独立结构化晋升。

`delegate_task` 提供 `resume_report_id`，须重新声明角色、任务契约和交付物；角色不变，工具白名单及写入 glob 条目只能保持或收窄。程序重新绑定当前规则、模型、预算和工作区，恢复自身历史中的工具调用配对；缺失结果标为未知，不自动重放副作用。Worker 未提交补丁只有 beforeHash 和文件权限匹配时才恢复至新隔离工作区，随后照常验证和归并。`resume_available` / `pending_patch_files` 表明恢复能力和候选数量；提交状态不明或回滚不完整时拒绝续接。历史与补丁分别在子循环退出、工作区清理前保存，不能保证进程被强制终止时保存正在进行的修改；图片、旧运行工具附件与非 UTF-8 二进制编辑不保证续接。

`run_in_background=true` 可立即返回 `status=running` 与报告 ID。后台复用有界 Worker 池（2 个线程、8 个排队位置）及现有取消原语；主 Agent 后续回合使用 `delegate_control(action=status|wait|cancel, report_id, wait_seconds)` 查询，等待默认 30 秒、最多 30 秒。完成通知仅携带 ID，报告仍是不可信数据。后台沿用启动回合的共享预算、系统和 Skill 快照、工具与写入范围及冻结的审批意图；后续用户输入不会扩大该子任务权限。取消请求只表示已发送，须查询终态。主 Agent 中止、清空历史、切换项目或关闭时取消后台任务；进程重启后不会自动启动旧后台任务，遗留 running 报告按 interrupted 返回。

Worker 完成正常工具循环后才归并 PatchSet；未解决的写入/命令失败、模型失败、预算退出、超时和提交前取消均保留未提交产物。修改涉及 `.java` 或根级 `pom.xml` 时复用 `PreReviewVerifier`：Maven 执行 test-compile，无 Maven 且存在 `src/main/java` 时使用 javac。检查经过现有命令沙箱与主机审批；不支持的布局、编译或环境失败、检查期间修改及提交重绑定造成候选变化均拒绝归并。`hard_check` 返回 NOT_REQUIRED/PASSED/FAILED/NOT_SUPPORTED。独立 Reviewer 仍按现有风险信号触发；编译通过不替代业务验收。版本冲突不覆盖主项目的并发修改；修改已开始原子提交后，取消不自动撤销已提交补丁，父任务可使用既有快照回滚。

所有新参数按系统属性 > 进程环境变量 > 默认值读取；不自动导出 `.env`：

- `devcli.delegate.<role>.provider/model` / `DEVCLI_DELEGATE_<ROLE>_PROVIDER/MODEL`：四种角色独立配置，未设置复用本轮主模型；显式无效配置失败，不静默换模型。凭据仍由现有 `DevCliConfig` 读取。
- `devcli.delegate.max.iterations` / `DEVCLI_DELEGATE_MAX_ITERATIONS`：每个子循环默认 32 轮，范围 `[1,100]`。
- `devcli.delegate.timeout.seconds` / `DEVCLI_DELEGATE_TIMEOUT_SECONDS`：每次委派默认 300 秒，范围 `[1,3600]`，从工具批次提交时开始计算，包含排队。只延长委派调用，不改变同批普通工具的时限。
- `devcli.delegation.report.sanitization.enabled` / `DEVCLI_DELEGATION_REPORT_SANITIZATION_ENABLED`：委派报告指令形态净化，默认开启；关闭后仍保留 `content_trust=UNTRUSTED`。
- `devcli.delegation.dir` / `DEVCLI_DELEGATION_DIR`：委派记录根目录，默认 `~/.devcli/delegations/`，其下按当前项目路径散列隔离。
- 父子共享启动回合的 `devcli.react.token.budget` 和 `devcli.react.hard.max.iterations`；子上下文摘要调用同样计入 Token 和总调用轮数。同一回合重新委派不能重置总预算，下一用户回合按现有 Agent 规则创建新预算；后台仍使用原预算。每个循环独立检测重复工具和重复错误。并行中的模型响应可能让 Token 使用超出阈值，后续调用会停止；不是按最坏响应预扣费的硬成本配额。

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
| 全局长期记忆 | `~/.devcli/memory/global/*.md`（`MEMORY.md` 为可重建索引） | `DEVCLI_MEMORY_DIR` / `-Ddevcli.memory.dir` |
| 项目长期记忆 | `~/.devcli/memory/projects/<projectKey>/memory/*.md` | 同上 |
| RAG 索引 | `~/.devcli/rag/codebase.db` | `-Ddevcli.rag.dir` |
| 审计日志 | `~/.devcli/audit/audit-YYYY-MM-DD.jsonl` | `DEVCLI_AUDIT_DIR` / `-Ddevcli.audit.dir` |
| Side-Git 快照 | `~/.devcli/snapshots/<project_hash>/<worktree_hash>/.git` | `DEVCLI_SNAPSHOT_DIR` / `-Ddevcli.snapshot.dir` |
| 压缩边界快照 | `~/.devcli/compaction-boundaries/boundary-<project_session_sha256>.json` | 由 `CompactionContext` 按项目/会话选择 |
| Runtime 与后台任务 | `~/.devcli/runtime/runtime.db` | 旧 `~/.devcli/tasks/tasks.db` 仅只读导入 |

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

- `ContextProfile` **不分模式档位**：所有参数都是当前模型上下文窗口的简单函数，全模型走同一套行为，只有 window 大小造成触发时机与容量差异
- GLM-5.1: 200k / DeepSeek V4: 1M / StepFun: 256k / Kimi K2.6: 256k
- 压缩触发阈值 = `min(window × 0.90, window − 输出预留)`；输出预留 = `min(max(模型输出上限, 20000), window / 2)`。可用 `devcli.context.compression.trigger.tokens` 或环境变量 `DEVCLI_CONTEXT_COMPRESSION_TRIGGER_TOKENS` 覆盖，且只向下收紧（取与默认值的较小者），非法值抛错
- 其余按 window 派生：短期记忆预算 `window × 0.45`（下限 4000）；记忆注入上限 `clamp(window/200, 500, 5000)`；Skill 索引 / 正文预算 `clamp(window/200, 256, 2048)` / `clamp(window/50, 1024, 8192)`；压缩后原文尾部预算 `clamp(window × 8%, 4000, 32000)`
- 压缩对**所有** window 都生效，没有按窗口大小跳过自动摘要的分支；`search_code` 未传 `top_k` 时固定默认 5（clamp 1..30），不做档位自适应
- MCP resource 索引按 `window >= 32000` 注入（与模式档位无关），只含 URI / 名称 / 描述 / mimeType，不含正文
- prompt caching：能力声明 + cached usage 解析

### Memory System

- 记忆按生命周期分三层：`conversationHistory + RollingSummary` 治理当前线程窗口，`SessionMemory` 保存当前任务共享的 WorkState 与 EvidenceJournal，`LongTermMemory` 保存跨任务稳定事实
- `SessionMemory` 统一接收幂等 SessionEvent；ReAct、Plan 和 Team 使用 beginTask/completeTask/endTask 轮换任务投影，全部 Prompt 区块共享一个硬 Token 预算；Multi-Agent 保存真实 agentId、stepId、sequence 和 context epoch，并拒绝迟到事件
- `ConversationHistoryCompactor` 是唯一消息窗口治理点；六段 `RollingSummary` 不保存待办、当前工作或下一步，旧九段摘要只兼容读取；`CompactionSummaryCache` 只保存可复用预摘要，默认 30 分钟过期。摘要边界同时保存投影指纹、事件范围和稳定快照引用，checkpoint/resume 会校验后再恢复
- `RuleContext` 加载规则文件和 `/rule add` 强约束，支持 `/rule list`、`/rule remove`；旧 pinned facts 仅列为待分类候选。敏感 `save_memory` 直接拒绝，要求用户脱敏后重试
- 压缩边界 `<compact_boundary>` 记录已加载 Skill、RAG epoch、MCP 工具快照和压缩后恢复入口状态；RAG epoch 合并当前会话已命中证据与当前项目全局索引版本，MCP 工具快照包含 server 工具数量、schema 指纹和生命周期版本
- 压缩上下文由 `CompactionContext` 绑定项目、会话、epoch、历史序号和 Runtime 来源事件范围；消息事实优先按稳定消息指纹关联真实事件 ID，`history:index` 只保留兼容回退。摘要和降级截断均经过 `CompactionSemanticGuard`，事实补回后重新校验状态与最终预算
- 普通用户消息与任务收尾不写长期记忆；`/save` 是用户显式入口，模型调用 `save_memory` 必须逐次人工确认，完整显示待存内容，自动分类、全部批准、关闭审批或无审批处理器均不能放行。存在活动项目时默认写项目作用域，全局偏好需明确选择 `global`
- 自动审批的可信意图来自独立保存的用户原文，在 CLI 展开附件前采集；记忆召回、Skill、工具结果、摘要和恢复的模型窗口不构成授权。Runtime 只从原始用户回合恢复意图
- 可信意图在 12000 字符预算内完整保留，不再逐条截断；超出预算、原文历史被淘汰或仅有压缩 checkpoint 时，`auto` 不调用分类器，也不复用批量批准，改为单次人工确认，无审批通道则拒绝。该状态不会因后续短消息自动解除；清空会话后重新建立意图上下文。此机制避免残缺授权依据，不代表模型判定质量已经通过评测
- 后台预摘要生产与正式压缩共用原始消息的切分逻辑，只摘要可淘汰前缀；已有六段摘要走增量协议，周期重建不复用预摘要。预摘要缓存同时校验消息内容（含图片分片）与模型身份；同一输入的确定性失败进入短暂指纹冷却，避免重复消耗模型调用。后台模型调用计入会话 Token 统计，ReAct 同时检查并计入当前回合预算
- 长期记忆只保存跨会话稳定事实。每条是主题 Markdown，类型只允许 `user|feedback|project|reference`；可选 `valid_days=1..3650`，过期后保留审计但排除于索引与召回
- 项目键在绑定时使用 Git 主工作区根路径，关联工作树和仓库子目录共享，嵌套独立仓库保持隔离；普通目录与 Git 元数据不可用时沿用请求绝对路径。旧路径主题复制到共享目录，源文件保留，同名冲突保留共享版本并在状态中显示；完成标记防止删除或清空后再次导入，迁移失败修复后可重试。不会遍历并合并尚未绑定的旧工作树目录
- 每个作用域的 `MEMORY.md` 按本轮记忆预算（`ContextProfile.memoryContextTokens()`，500–5000 tokens）裁剪后注入，截断时附提示；索引自身上限（200 行 / 25000 字节）比预算大一个数量级，不裁剪会让预算配置对索引失效
- 索引与选中全文**共享同一个预算**：索引先裁剪，剩余额度才给全文；全文按整条取舍，放不下的整条跳过，只有真正注入的条目才计入「本会话已注入」
- LLM 相关性选择器默认开启，候选只包含未过期主题；发送给模型的清单最多 4096 tokens，选择结果白名单也只包含这批实际发送的候选，命中后才读全文。项目同名主题覆盖全局版本，两份原文仍保留
- 同作用域自动冲突不覆盖原文；用户显式保存才更新并增加修订号。`/memory organize apply` 只从权威主题文件重建索引
- `/memory clear` 默认只清**当前写入作用域**（有活动项目清项目，否则清全局）；`/memory clear global` 与 `/memory clear project` 显式指定。全局目录跨项目共享，项目会话里的默认清空不得连带抹掉它，结果会提示如何显式清另一作用域
- 旧版 `records/` 记忆卡在启动时幂等复制到全局作用域，全部处理成功后写一次性完成标记，后续构造不再重复解析；源文件保留，旧格式缺少可靠项目归属，不自动归入项目
- RAG 检索审计按 JSONL 保存各召回通道、RRF、rerank、最终结果和降级状态，不保存代码正文；普通 CLI 会话归档默认关闭，启用后 ReAct 保存脱敏模型消息，Plan / Team 保存顶层输入输出，并按配置期限清理
- 用户显式要求忽略记忆（如“别管记忆”“忽略记忆”）时，本会话不注入长期记忆、通用 SessionMemory 和角色裁剪后的 SessionMemory
- 反馈类长期记忆按 `feedback` 类型落盘，不混入 `user` 或 `project`

### Plan Multi-Agent

- 唯一公开入口为 `/plan`，固定进入 Planner/Worker/Reviewer 链路；旧 `/plan --team` 与 `/team` 仅保留解析兼容，不再出现在帮助和补全中。串行或并行由 DAG 就绪状态与资源冲突决定。
- `MultiAgentBatchExecutor` 委托 `OrchestrationWaveExecutor` 完成有界并发、异常归属、输出隔离和稳定顺序归并；`PlanExecuteAgent` 与 STANDARD profile 仅保留内部兼容，不进入 CLI 路由。
- 三角色：Planner / Worker(默认 2 个) / Reviewer
- 流程：规划与确定性预检 → Reviewer 计划语义建议 → 用户确认 → 按依赖分配 Worker → Pre-Review 硬检查 / 产物 Reviewer 建议 → 归并
- 计划 Reviewer 使用独立、无工具上下文，逐项输出需求覆盖、节点引用、验收标准评审和结构化问题；critical/high 标准必须给出反例输入及预期失败信号。输出前后允许少量说明文本，只提取包含 `approved` 的完整合法 JSON 对象；无法提取时进行一次协议修复。拒绝、协议损坏和模型故障均作为可见建议，不阻断已通过确定性预检的计划。可选的独立 Provider / 模型通过 `DEVCLI_TEAM_REVIEWER_PROVIDER` / `DEVCLI_TEAM_REVIEWER_MODEL` 配置。
- 每条验收标准必须包含判定信号、`TOOL|HUMAN` 验证方式、验证器和 `applies_to`。目标只能是有效 DAG 节点或 `FINAL`；普通节点只接收自己的标准，Final integration 接收全部标准。缺失字段、重复 ID、未知节点、未知工具或具有项目写入副作用的自动验证器会在执行前拒绝。
- Planner 输出先做协议与结构校验：支持从前后说明中提取完整 JSON；步骤类型限制为固定枚举，纯读取或检查语义不能声明为 `FILE_WRITE`。解析失败、DAG 无效或阻塞性空工作区纯检查步骤会触发有界修复。修复前清空 Planner 历史，并把原始任务、失败原因、无效输出预览和固定 schema 放入新请求。默认修复 2 次，可通过 `devcli.team.planner.repair.max.attempts` / `DEVCLI_TEAM_PLANNER_REPAIR_MAX_ATTEMPTS` 调整到 `[0, 3]`。
- Planner 不调用工具；空工作区属于合法状态。目录和文件存在性检查不能成为阻塞实现的独立步骤，必要检查并入首个实现步骤并采用“若不存在则创建”语义。
- SubAgent 按单次执行保存结构化工具证据。Worker 最终 content 为空但存在 `ToolStatus.SUCCESS` 时，由 Orchestrator 合成有界证据摘要进入 Pre-Review / Reviewer；无论 content 是否为空，只要没有成功工具证据，独立 Worker 协议守卫都会追加一次强制执行上下文，阻止文字方案或伪代码冒充执行结果，并要求文件任务调用 `write_file`、分析任务调用读取工具，并在该请求首轮设置命名 `LlmClient.ToolChoice`：FILE_WRITE / INTEGRATION 选择 `write_file`，COMMAND 选择 `execute_command`，其他类型选择 `list_dir`。Anthropic Messages 请求映射为 `tool_choice: {"type":"tool","name":"..."}`，OpenAI-compatible 请求映射为命名 function choice。FILE_WRITE / INTEGRATION 步骤出现成功 `write_file` 批次后直接以结构化证据结束当前 Worker 执行；强制修复中的指定工具也采用同一完成策略，避免再发起无必要的 LLM 收尾请求。Provider 忽略命名工具选择时，`AgentExecutionEngine` 追加一次严格 JSON 工具信封请求；SubAgent 只接受单一完整 JSON 对象、目标工具名及对象参数，拒绝 reasoning、Markdown、代码围栏、尾随文本和未知字段，解析后仍通过原有工具参数校验、能力范围、HITL 与策略管线。工具失败时才恢复 AUTO 进入下一轮纠正，最终仍无成功证据才返回“执行结果为空”。
- SubAgent IOException 返回 ERROR 类型
- Planner 共享主 ToolRegistry；副作用 Worker 使用 `WorkspaceExecutionSession` 创建隔离 ToolRegistry，Pre-Review 与 Reviewer 在同一隔离目录读取真实产物，MemoryManager 继续共享角色裁剪视图。
- Plan `Task`、Multi-Agent `ExecutionStep` 和 checkpoint 共用 `ExecutionArtifact`，统一保存 state、output、summary、modifiedResources、error、attempt、startedAt、finishedAt。
- checkpoint 协议版本 10 通过 `RecoveryState` 恢复共享 artifact、验收方式、验证器、适用节点、pending PatchSet 写前日志及文件权限、待验证 PatchSet 附件、稳定子代理身份、步骤分配、消息游标、有界且按步骤归属的 AttemptDigest、已消耗的在位重做次数和重做失败现场；旧协议缺失适用节点时迁移为 `FINAL`，缺失验证字段时迁移为人工验收。Pre-Review 环境失败不合并未验证代码，PatchSet 以哈希和权限元数据加私有内容旁路保存；resume 将它及下游节点重置到待执行状态，在新隔离工作区恢复并重新验证。恢复保持原步骤绑定和原重做额度，只注入 schema 兼容的最近摘要及当前步骤失败尝试，不恢复完整私有对话。
- Plan Worker 每次尝试都通过隔离 ToolRegistry 的 `runWithResourceLease(stepId, ...)` 绑定资源租约上下文，并在 finally 中释放；并行工具线程显式继承步骤租约归属。ToolRegistry 统一托管 `ResourceLeaseMaintenance`，project fork 共享同一个定时线程；最后一个注册关闭后停止。默认每 60 秒清理过期租约，可通过 `devcli.resource.lease.cleanup.interval.seconds` / `DEVCLI_RESOURCE_LEASE_CLEANUP_INTERVAL_SECONDS` 调整。
- Plan 副作用步骤在隔离工作区执行；`ToolEffect` / `ToolAccessScope` 在工具管线中强制限制非隔离任务只能使用只读能力。隔离命令和 Pre-Review 默认通过受限 Docker 执行，无网络且失败关闭；每次执行使用唯一容器名，超时或取消时终止客户端进程树并有界执行 `docker rm -f`；显式 `HOST_WARN` 只允许 Maven 离线执行 `clean/validate/compile/test-compile/test/package/verify`、`javac` 与只读 Git，拒绝命令行指定的任意 Maven 插件和发布阶段，并输出主机风险提示，不自动回退。非默认 Maven 仓库需通过 `DEVCLI_COMMAND_SANDBOX_MAVEN_REPOSITORY` 或系统属性显式指定存在的绝对目录，Docker 仅对 Maven 命令只读挂载，`HOST_WARN` 注入 `maven.repo.local`。PatchSet 逐文件流式哈希，只保留变更内容；JVM 公平锁与跨进程文件锁共同串行提交，锁缓存按活跃使用者计数退役。应用前保存 before/after 哈希和原文件备份；备份限制为当前所有者访问，孤儿日志按 TTL 清理，恢复时提升完成、继续待执行或回滚，失败回滚会报告具体路径。
- 主机模式推荐名称为 `HOST_RESTRICTED`，上述 `HOST_WARN` 保持兼容，二者策略相同。命令白名单不隔离 Maven 项目插件、构建脚本或 javac 注解处理器，只适合可信项目，不是 Docker 的安全替代。
- 命令工具的实际主机后端（普通 FULL 和显式主机模式）在最终分发前逐次人工确认，不复用任务授权或全部批准；没有处理器或关闭 HITL 时失败关闭。后端白名单先于弹窗；审批只接受原参数单次批准，后续取消或期限耗尽不会启动进程。请求冻结命令、目录与后端；`host_command_approval` 审计以调用标识和独立审批标识关联，不记录完整命令或工作目录。提示展示脱敏后的完整命令并转义控制字符，回车默认拒绝。Docker 工具路径不新增此审批。直接调用命令服务的 Pre-Review 和外部 MCP 进程未纳入，不能作为全进程安全边界。
- Windows 的已验证主机白名单命令使用 `cmd.exe` 执行，保留 Maven `-Dname=value` 参数；普通主机命令仍使用 PowerShell。命令指纹保留引号差异，纠正后的命令不会与先前失败调用误合并。
- 写租约在空闲超时之外增加 600000ms 绝对期限，`DEVCLI_TEAM_LEASE_MAX_LIFETIME_MS` / `devcli.team.lease.max.lifetime.ms` 可调。单调时钟避免墙钟跳变，续租保留首次获取时间，到期重新竞争；清理使用条件删除，不移除并发更新后的租约。
- 任务授权由 Runtime 绑定项目和唯一作用域；fork 与并行调用共享撤销标记，原授权作用域结束后不再免审批。审计关联执行轮次、步骤、授权、调用与逐次审批，并区分真实审批来源；缺失身份不补造，用户请求、Agent 身份及最终 generation 仍未贯通。
- 授权分两层，两层各只有一个载体：跨会话的持久授权写在 `~/.devcli/config.json` 的 `permissions.allow` 规则里，`/grant` 给出的是只作用于下一轮任务的例外。`permissions.baseline` 与 `TaskGrant.merge` 已删除——前者与规则层完全重叠，后者的并集语义还造成授权只能放宽、不能收紧。`/grant status` 列出例外、规则层与放行缓存，`/grant off` 同时清空例外与 `approvedAll`；状态栏常驻显示规则层摘要。非法规则在启动时显式拒绝并整体降级为无规则，不静默部分生效；配置文件位于受保护路径名单内，工具写路径无法改写。
- 规则层使用 `hard_deny` / `soft_deny` / `allow` / `environment`。策略硬边界与 `hard_deny` 始终确定性拒绝；`auto` 将其余三类注入分类器，非 `auto` 按 `hard_deny` → `soft_deny` → `allow` 求值。旧键 `deny` / `ask` 不再读取，覆盖告警启发式及其字面量前缀计算已删除。规则语法、资源槽和命令拆分语义保持不变；任一规则非法仍整组拒绝。详见 `docs/adr/0013`。
- `auto` 同时处理默认未决动作以及命中 `allow` / `soft_deny` / 任务授权的动作；删除、回滚、浏览器和 MCP 逐次审批仍由人工确认。分类器只接收真实用户消息与 Assistant 的结构化工具调用，排除 Assistant 自述、工具结果、系统注入、插件内容和委派报告；四类规则分别注入系统提示词的固定位置。分类器缺失、超时、协议错误或预算不足均失败关闭，连续失败达到阈值后回落 `default`。详见 `docs/adr/0012` 与 `docs/adr/0013`。
- `PreReviewVerifier` 独立负责 Maven/javac 选择、Java 文件扫描、超时、进程输出解码和失败摘要。Maven 多模块根目录即使没有根级 Java 源码也执行硬检查；Maven 项目固定使用沙箱镜像内置的 `mvn`，复用显式只读本地仓库，不通过 Wrapper 在禁网临时容器中重复下载 Maven 本体。仅按依赖解析、仓库写入、工具链、超时、取消和沙箱故障的精确信号识别环境失败，普通编译错误中的 `cannot access` 仍归为代码失败。编译、测试等确定性失败继续阻断；只有 Pre-Review 硬检查实际执行并通过时，产物 Reviewer 的拒绝、协议错误或 LLM 故障才记录为建议，不触发 Worker 重做。未执行硬检查时 Reviewer 失败关闭。Reviewer 默认 5 轮，可配置为 `[1, 8]`；最后一轮禁用工具并强制输出裁决 JSON。
- `Planner.replan()` 不是 Agent 循环，没有工具调用权，因此失败后重规划只读取 ExecutionArtifact 的最小结构化产物事实，不读取完整任务 result 作为主要依据。

### HITL System

- 危险工具：write_file(中) / edit_file(中) / apply_patch(中) / execute_command(高) / create_project(中) / revert_turn(高)
- 审批选项：y(批准) / a(全部放行) / n(拒绝) / s(跳过) / m(修改参数)
- fail-safe：连续 5 次无效输入判为 REJECTED
- 并发：requestApproval 整体 synchronized

### HITL Enhancement (Policy Layer)

- `PathGuard`：路径限定在项目根内（绝对路径外逃 / `..` 穿越 / 符号链接逃逸）
- Agent 配置保护：共享写入口与 PatchSet 预检、写入前拒绝 `.devcli/hooks.json` / `mcp.json` / `config.json` 和自定义 Hook 文件；审批和任务授权不可覆盖。核对默认用户配置、物理别名和隔离工作区的主项目对应路径；快照恢复跳过配置并提示。普通 `.devcli` 文件不受封禁。此处仅为应用层保护，主机命令、外部 MCP 服务及配置引用脚本仍可能绕过，未新增操作系统沙箱或网络出口控制。
- `CommandGuard`：fast-fail 黑名单（sudo/rm -rf/mkfs/dd/fork bomb/curl|sh 等）
- `ResourceLimit`：write_file / edit_file / apply_patch 5MB / execute_command 60s + 8KB 输出
- `AuditLog`：JSONL 字段 timestamp/tool/args/outcome/reason/approver/durationMs
- 拦截顺序：HitlToolRegistry → ToolRegistry → 策略层。用户无法批准策略拒绝的请求

### Parallel Tool Execution

- `executeTools()` 固定线程池并行，默认最多 10 个并发，返回结果保持原始顺序；上限由 `devcli.tool.batch.max.parallel` / `DEVCLI_TOOL_BATCH_MAX_PARALLEL` 控制，取值 1～64，非整数或越界在加载时直接拒绝，不静默回落
- 并发宽度只作用于只读工具：副作用工具在工作区级公平锁内串行，调高上限不会放宽写入互斥
- 并行执行线程显式继承调用方的 `ToolAccessScope`、资源租约步骤和 SkillContextBuffer 快照，避免 ThreadLocal 回退到 FULL 或丢失修改文件归属
- `search_tools` 缓存键包含当前能力范围，不能从 FULL 缓存泄露副作用工具到只读任务
- Agent/PlanExecuteAgent/SubAgent 三条路径都走 executeTools()

### Tool Result Size Management

- 两层串联：工具层在执行时按单条结果分档，agent 层在回灌上下文前按整批额度二次分配；两层都可能把结果换成落盘引用加预览
- 工具层分档阈值（当前为常量，不可配置）：单条 20000 字符以内原文放行；20000～100000 字符截断到 20000 并附可检索提示；超过 100000 字符完整落盘，回传 5000 字符预览加文件路径
- 工具层同轮聚合额度 100000 字符，由并行线程共享；超额度后单条截断目标收紧到 10000 字符
- agent 层单批额度 100000 字符 / 25000 Token，按条均分；仍超限的条目转为落盘预览引用并二分截断前缀
- 阈值量级对齐 Claude Code（单条 50000 字符内直接进入、整批 200000 字符），但放行线定在 20000，为后续对话与压缩留出余量
- 被截断或落盘的结果会标记折叠分类，MCP 工具结果同样适用

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
- `SessionMemory` 的恢复段不复用完整 system prompt 视图，而是按最近读写文件、未完成子任务状态、关键工具结果引用、RAG 证据 epoch 输出短结构化上下文；代码与图片归约元数据会随内容进入摘要输入，图片身份以字节 SHA-256 为准
- Agent / PlanExecuteAgent / SubAgent 会在恢复段追加 MCP 工具状态和本地 SkillContextBuffer 的已加载 Skill、context、allowedTools 与内容摘要
- 恢复段通过 `PostCompactRestoreContext` 做统一预算控制和行级去重；SubAgent 恢复区使用 Planner / Worker / Reviewer 角色视图裁剪，Planner 不携带工具证据，Reviewer 不携带会话临时事件
- RAG 证据从 `search_code` 的工具结果强类型旁路载荷进入 `SessionMemory`；尺寸治理、只读结果缓存和批量执行结果都会保留该载荷。展示文本不再嵌入结构化 JSON；旧 JSON 与旧展示文本只用于历史兼容，typed negativeFact 仍会即时清理旧 symbolVersion。

### MicroCompact

- Microcompact 在 LLM 摘要前执行，不删除消息，保持 assistant tool_call 与 tool result 配对。
- 折叠引用按结构识别：要求内容以 `<microcompact_boundary>` 起始、存在闭合标记且携带 `type=tool_result`；正文中偶然出现该字符串不算折叠引用。标记常量、判定和字段提取集中在 `MicrocompactBoundary`，生成侧与消费侧共用，避免同一语义两套判定。
- 淘汰只按两条可证明判据：内容指纹与更早的工具结果相同（`duplicate`），或 `read_file` 的同一 `path` 在更晚位置被 `write_file` / `edit_file` 写过（`superseded`）。不按尺寸、工具类型、保留最近 N 项或工具保护名单判定，`DEVCLI_CONTEXT_MICROCOMPACT_KEEP_RECENT_TOOL_RESULTS` 与 `DEVCLI_CONTEXT_MICROCOMPACT_EXCLUDE_TOOLS` 已废弃，仅保留编译兼容。
- 被回收的完整结果落盘到 `.devcli/microcompact_tool_outputs/<session>/<toolCallId>.txt`，并写同名 `.sha256` 边车；消息中保留 `<microcompact_boundary>`、toolCallId、toolName、originalChars、reason、sourceToolCallId 和 storedPath。折叠后 Token 不减少或落盘失败时原地保留。
- `SessionMemory` 压缩后恢复区遇到 microcompact 工具引用时，只输出 toolCallId / originalChars / storedPath，并按 storedPath 或 toolCallId 去重。

### Terminal Renderer

- 活跃实现：InlineRenderer（默认）/ PlainRenderer
- 环境变量：`DEVCLI_RENDERER=inline|plain`
- `lanterna`、`tui` 和旧 `DEVCLI_TUI=true` 在兼容期输出迁移提示并映射到 Inline；Main 不再进入 TuiBootstrap
- `DEVCLI_NO_STATUSBAR=true`：禁用底部状态栏
- `NO_COLOR=1`：禁用 ANSI 颜色
- 当前开屏 Banner 是无右侧盒线边框的简洁布局，避免 ANSI/CJK 字宽导致竖线错位
- InlineRenderer 复用 JLine 4 的编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`；`/help`、补全和历史导航共用命令清单与 LineReader
- ReAct 且本轮不会弹出审批时，活动轮次继续由主 LineReader 接收输入：普通文本进入容量为 8 的 FIFO 队列，`/now <任务>` 先入队首部再取消当前轮次，`/cancel` 只取消当前轮次；模型结束信号唤醒输入后保留未提交草稿。判断依据是权限模式与规则集（`Main.neverPrompts`：`dontAsk` 把未决动作收口为拒绝，`bypassPermissions` 需规则集里没有显式 `ask` 规则），不再用「HITL 开关」——审批通道现在恒为可用，用开关判断会让这个条件恒为假、功能静默失效。Plan 与 Multi-Agent 保持原输入所有权，禁止队列读取器和审批读取器并发访问终端
- BottomStatusBar 是 JLine `Status` 托管的底部 dock：由 JLine 负责滚动区域和状态行位置，不再手写 `\n`、`moveUp`、`CLEAR_TO_EOS` 或绝对光标行号；dock 上层展示权限模式位与 MCP/Skill 摘要，下层展示 model、phase、ctx、token、cost、elapsed 与 cwd
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
- 检查点候选会移除动态 system prompt、reasoning 和图片正文；同时保存压缩 metadata、稳定快照引用/校验和与消息树快照（稳定 `id`、`parentId`、role、index），当前默认从压缩边界生成线性 parent 链，为后续分支恢复保留协议字段；旧 SQLite 数据库启动时自动补充 `message_tree_json` 列。保存发生在 `turn.completed` 事件之后，失败只写入 `thread.checkpoint.failed`；恢复时校验 projection hash、来源事件范围和快照 checksum，最新记录损坏时按时间回退到更早可解析检查点
- `RunEvent` 统一表达 reasoning/content delta、工具调用、工具结果、turn 终态和 checkpoint 事件；`AgentExecutionEngine` 将模型 StreamListener 回调转换为事件，再通过适配器投影到既有 Renderer 或 Runtime sink
- `RunEvent` 另外提供 `session.state`（running / idle 等会话生命周期）和 `message.custom`（扩展消息类型、正文、字符串属性）；Runtime session turn 开始和结束会发布状态事件，自定义事件必须经过统一 JSON codec，不允许扩展直接拼接协议文本
- 模型能力由 `ModelCapabilityRegistry` 统一解析 Provider 别名、上下文窗口、输出上限、prompt cache、工具调用、视觉和 reasoning 能力；`LlmClient` 的上下文策略默认从注册表读取，Provider 客户端只保留实例级差异（例如 Anthropic 的配置化输出上限）
- Skill、Hook、MCP server 和 CLI command 的发现元数据统一通过 `ExtensionContract` / `ExtensionRegistry` 表达：稳定 id、kind、来源、启用状态、版本、能力和元数据；Main 启动后把命令、Skill、Hook 和 MCP server 注册进目录，`/skill reload` 会原子替换 Skill/Hook 目录，MCP enable/disable/restart 通过 `McpServerManager` 观察者同步 MCP 目录，CLI Skill/MCP 补全优先从统一目录读取。该目录契约不接管各自执行权限，Skill、Hook、MCP 和命令继续使用原有安全与策略管线
- CLI 活动输入不再使用独立 `PromptQueue` / `ActiveTurnCoordinator`；生产路径唯一使用 `AgentTurnInbox`，由 AgentExecutionEngine 按 Steering / Follow-up 时机注入。旧队列实现已删除，避免两套取消和容量语义并存
- 验证边界：plain renderer 的 `/help` 与 `/exit` 启动烟测已通过；非交互管道不能证明 JLine 补全、方向键、底部 dock 或 HITL 按键行为，未验证前不引入 TUI differential rendering 改造
- Runtime JSON 投影集中维护协议字段和转义，每个 payload 固定携带 `schema_version=2`；工具 arguments 优先保持 JSON 对象，无法解析时保留原文本；工具结果携带 status、error_code、retryable、elapsed_millis、image_count、presentation 和结构化 `exit_code`，退出码来自 `CommandResultMetadata`，不依赖展示文本解析，也不持久化图片正文
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
- 历史 image payload 替换为文本占位，保留来源、尺寸和字节 SHA-256，避免旧截图消耗上下文

### Deterministic Content Reduction

- `DeterministicContentReducer` 在语义摘要前识别工具输出、代码、图片和普通文本，并把 `metadata` 注入摘要输入。
- 代码元数据包括文件路径、增删行统计与 diff 指纹、符号、编译位置，以及 `rag_epoch` / `index_epoch` / `symbol_version` / `classpath_epoch`；原文引用仍由工具结果或落盘附件提供。
- 图片元数据包括来源、MIME、尺寸和图片字节 SHA-256；base64 或本地文件均按解码后的字节计算，无法取得字节时不回退到描述文本哈希。OCR/视觉摘要由可选 provider 注入，未配置时只保留确定性元数据。

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
ReAct / Plan task / SubAgent 共用循环；统一取消和预算检查、LLM 调用、assistant/tool 消息协议、结构化工具错误记录与 IOException 出口；取消或失败时把已经流出的 reasoning / content 写回历史，只补文本、不补 tool_call，避免留下没有配对结果的调用；路径差异通过 Delegate 钩子注入

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
checkpoint 协议版本 10；通过 RecoveryState 恢复共享 ExecutionArtifact、验收方式、验证器与适用节点、稳定子代理身份、步骤分配、单调消息游标、最小摘要、按步骤归属的有界 AttemptDigest、已消耗的在位重做次数和失败现场，保存 PatchSet 写前日志、待验证 PatchSet 附件、文件权限与原文件备份，恢复时按文件内容和权限对账并保持原 Worker 绑定和原重做额度；旧协议缺失适用节点时迁移为 `FINAL`，缺失验证字段时转为人工确认，损坏身份拓扑或未来版本明确拒绝

### PreReviewVerifier.java
Reviewer 前 Java 硬验证；封装 Maven/javac 命令、扫描、超时、输出解码和失败摘要，无 Maven 时使用 javac 参数文件避免命令行过长

### ToolRegistry.java
15 个内置核心工具（含 `delete_files` 明确清单删除、`edit_file` 精确替换、`apply_patch` 官方结构化补丁、带作用域/类型/有效期的 `save_memory` 和 `grep_code` 实时精确文本搜索）+ MCP 动态工具 / executeTools() 并行入口 / ToolInvocation / ToolExecutionResult；`ToolExecutionPipeline` 按阶段执行取消、存在性、能力范围、Skill 权限、Schema 参数校验、HITL、语义/业务校验、审计、策略和结果治理；`ToolSemanticValidator` 负责本地可判定的参数组合、路径/资源状态、范围和协议语义，业务扩展可通过 `ToolRegistry.registerSemanticValidator` 注入规则；`ToolOutput` / `ToolExecutionResult` 携带 status、errorCode、retryable、elapsedMillis、imageParts、sideChannels 和 modifiedResources；命令退出码由 `CommandResultMetadata` 旁路保留并贯通 Runtime 事件与 SessionMemory，不从展示文本提取；内置 Provider 通过结构化执行器直接保留参数错误、语义拒绝、策略拒绝、命令退出、超时和取消状态；HITL 作为管线中间件，不再覆写 executeTool；默认只注入内置核心工具和已激活 MCP 工具；ReAct、Plan 和 Multi-Agent turn 开始前会按当前用户输入预激活匹配到的 MCP 工具；`search_tools` 使用工具索引缓存，MCP 工具变更后自动失效，命中 MCP 工具后激活到后续工具定义；未知工具会返回 `search_tools` 引导和 query 示例

`read_file` 同传行范围与字符范围时优先行范围并返回提示；`edit_file` 会按目标文件的 CRLF/LF 风格对齐匹配文本。

### tool/patch Package

`apply_patch` 的四段式实现：`ApplyPatchParser` 解析官方 `*** Begin Patch` 语法（Add / Delete / Update / `*** Move to:` / `*** End of File`，兼容 heredoc 包裹，`*** Environment ID:` 显式拒绝）→ `SeekSequence` 按 精确 → 忽略行尾空白 → 忽略两侧空白 → Unicode 标点归一化 四级降级定位上下文行 → `ApplyPatchCompiler` 在内存算出每个文件的最终内容并组装成 `PatchSet.FileChange` → `ApplyPatchTool` 复用既有写链路（写路径策略 → 委派写白名单 → 资源租约 → 版本闸门 → PatchSet 原子应用 → 版本账本登记）。

关键契约：不新增写入路径，PatchSet 负责冲突预检、项目级锁、原子替换与回滚；全部 hunk 在写盘前算完，任何一处失败整批不应用（官方实现逐个 hunk 直接写盘、失败只上报已提交部分）；行尾保留文件原有风格（官方默认归一化为 LF）；同一路径在一个补丁里出现两次直接拒绝；新增文件在 POSIX 上取进程默认建文件权限（避免继承临时文件的 `rw-------`）。

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

`LongTermMemory` 的当前契约见 ADR 0007：Markdown 主题是唯一权威，`MEMORY.md` 只是可重建索引。全局/项目目录物理隔离；frontmatter 保存类型、创建/更新时间、修订号和可选有效期。过期项不召回但不删除；冲突是从当前文件派生的视图，不另建状态机。旧版 `records/` 卡片会幂等复制到全局作用域且保留源文件。Curator、晋升/确认队列、Evidence 审核态和旁路计数已移除。

`ToolInvocationFingerprint` 对 JSON 对象字段排序，统一查询字段大小写、Unicode NFKC 等价字符、冗余空白和路径分隔符；正则 pattern 保持大小写敏感，避免相似但不等价调用共享缓存。AgentBudget 使用该指纹判断语义重复；ToolRegistry 只缓存成功、无图片的 READ_ONLY 结果，默认 128 条、30 秒。项目路径切换或任何非只读工具进入执行阶段时清空缓存，禁止把副作用前的陈旧读取跨状态复用。

## Benchmark Evaluation

评测方法与工程测试入口统一见 [量化评测规范](benchmark-evaluation.md)。`src/test/java/com/devcli/benchmark/` 默认被 Quick 排除，真实 `*IT` 还需显式选择并启用开关。当前 RAG 适配器使用 CodeSearchNet docstring/code 派生语料，比较 semantic 与完整检索，输出二值相关性的 Recall@5、MRR@5、nDCG@5；它不是使用人工分级标签的 CodeSearchNet Challenge 官方 nDCG，也未提供完整的五档消融。

旧隐藏任务、自建 Memory 和 Compression 评测已退役，仅保留历史归档。当前 `SweBenchDriver` 支持 solo/delegate/plan，但三模式 PowerShell 脚本直接运行 `eval.sh` 并解析日志，尚未接入 Multilingual 官方评分报告，自定义 `resolved` 不得作为官方成绩。正式 Java 评测须冻结 Multilingual Java 任务清单、各角色模型和预算，并读取官方 report 的 resolved 与逐测试 F2P/P2P。

`PublicBenchmarkCatalog` 校验已登记资产的 SHA-256；当前目录包含 Lite、LongMemEval Oracle、LongBench v1 和 RULER v1。`PublicBenchmarkReadinessIT` 检查这些入口，不能证明未登记资产已可用。`RulerDatasetGenerationIT` 调用固定版本生成器；`PublicLongContextBenchmarkIT` 直接把历史或长文本交给模型，尚未经过项目记忆和压缩模块，也未调用官方 judge/evaluator。其 LongBench 专用评分仅覆盖两个英文合成任务，其他任务回退为包含命中；字段名包含 official 不代表官方评分已接通。`SweBenchLiteAgentBenchmarkIT` 可输出 predictions 并调用 Lite Linux Docker harness，但不能替代 Multilingual 接入。

Java IT 报告默认写入 `target/benchmark-reports/`，三模式脚本写入 Instance 下的 runs 目录。正式记忆与压缩成绩须补齐真实功能开/关对照和官方评分；报告必须区分工程回归、模型直测与项目功能收益，不能以代理指标或历史数字回填简历。

## Test Coverage Summary

常规测试覆盖偏向：解析、计划结构、RAG 核心、Multi-Agent 编排、HITL 策略、策略层拦截、MCP 协议、资源输入层、长上下文策略与 Skill 加载。

常规测试不覆盖真实 LLM、真实 Embedding API、真实 MCP server 和终端完整手工体验；对应真实链路通过显式启用的 benchmark 或手工验收执行。

完整测试类列表：CliCommandParserTest / MainBrowserCommandTest / PlanReviewInputParserTest / MainInputNormalizationTest / ExecutionPlanTest / MemoryEntryTest / ConversationMemoryTest / LongTermMemoryTest / MemoryRetrieverTest / MemoryManagerTest / ExplicitMemoryHintsTest / ContextProfileTest / PlanExecuteAgentTest / AgentMemoryHintTest / AgentRoleTest / AgentMessageTest / AgentOrchestratorTest / EmbeddingClientTest / SearchResultTest / NetworkPolicyTest / HtmlExtractorTest / WebFetcherTest / SearchProviderFactoryTest / ZhipuSearchProviderTest / VectorStoreTest / CodeChunkerTest / CodeAnalyzerTest / CodeIndexTest / ApprovalPolicyTest / ApprovalResultTest / HitlToolRegistryTest / TerminalHitlHandlerTest / ToolRegistryTest / BrowserSessionTest / BrowserConnectivityCheckTest / SensitivePagePolicyTest / BrowserGuardTest / McpSchemaSanitizerTest / McpConfigLoaderTest / JsonRpcClientTest / McpToolBridgeTest / McpResourceCacheTest / AtMentionParserTest / AtMentionExpanderTest / AtMentionCompleterTest / NotificationRouterTest / PathGuardTest / CommandGuardTest / AuditLogTest / SkillFrontmatterParserTest / SkillRegistryTest / SkillStateStoreTest / SkillBuiltinExtractorTest / SkillContextBufferTest / SkillIndexFormatterTest / LoadSkillToolTest / SkillCommandHandlerTest
