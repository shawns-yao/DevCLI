# AGENTS.md

## 删除审批

新增核心工具 `delete_files`，接收 `paths` 数组，仅删除明确的项目相对普通文件，不递归、不展开通配符、保留目录。单次最多 500 个文件、合计 20MB、单文件 5MB；复用路径保护、委派写范围、租约、版本闸门和 PatchSet。审批绑定预检文件哈希，内容变化整批拒绝；不进入回收站，成功后恢复依赖已有快照或备份。

HITL 开启时，未获路径授权或达到批量阈值的删除必须逐次确认，不复用工具级全部批准。阈值默认 50，可通过 `DEVCLI_DELETE_APPROVAL_THRESHOLD` / `-Ddevcli.delete.approval.threshold` 配置为 1–500。可识别的 Shell 删除也强制逐次确认，但不宣称解析全部 Shell 语义或统计目标数；关闭 HITL 不绕过文件工具硬策略。阈值按单次调用统计，不跨调用累计。

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
- 已交付至第 27 期（ReAct → Plan+DAG → Memory → RAG → Multi-Agent → HITL → 并行工具 → 多模型 → 联网 → MCP 核心 → MCP 高级 → 长上下文 → Chrome DevTools → CDP 会话复用 → Skill → TUI → LSP 诊断 → Side-Git 快照 → Prompt 分层 → Runtime API → 图片输入 → JLine 交互升级 → Agent 加固 → RAG 调用图扩展 → RAG 检索意图 → 滚动摘要裁剪；第 26 期无独立文档）
- 下一步：sampling / recovery 作为后续增强；OAuth 暂不纳入个人使用优先级
- Banner 版本：`v16.1.0`，Maven 产物：`devcli-1.0-SNAPSHOT.jar`

## 运行前提

- Java 17+ / Maven
- 默认 LLM provider 是 `anthropic`；至少一个 API Key：`ANTHROPIC_AUTH_TOKEN`（Anthropic Messages 兼容，可配 `ANTHROPIC_BASE_URL` / `ANTHROPIC_MODEL` / `ANTHROPIC_MAX_TOKENS`）/ `OPENAI_API_KEY` / `GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY`

## 常用命令

```bash
cp .env.example .env
mvn clean package -DskipTests   # 快速产出可手工验收 jar
java -jar target/devcli-1.0-SNAPSHOT.jar
java -jar target/devcli-1.0-SNAPSHOT.jar -p "任务"  # 无交互，stdout 为 UTF-8 JSON
mvn test -Pquick          # 常规回归
mvn test -Pphase16-smoke  # 终端交互兼容回归
mvn test -Dtest=XxxTest -DskipTests=false   # 针对性
mvn test -DskipTests=false                  # 全量回归
```

`pom.xml` 的 `<skipTests>` 默认是 `false`，所以裸跑 `mvn clean package` 会连全量测试和 SpotBugs 一起跑；只想拿 jar 就显式加 `-DskipTests`。`target/` 不随仓库分发，手工验收前必须自己构建一次。

Windows + Git Bash 下必须调用 `mvn.cmd`，不要用 `bin/mvn` 这个 shell 脚本：后者拼出的 POSIX 风格 classpath 交给 Windows JVM 会直接报 `找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher`。同时确保 `JAVA_HOME` 指向 JDK（本机为 `C:\Download\JDK\JDK-21`）。

## 架构概览

默认 ReAct 与显式 `/plan` 共享 `delegate_task` / `DelegationSession` 子任务执行原语和 `AgentExecutionEngine`。`/plan` 保留计划确认、依赖调度、验收与 checkpoint；步骤的工作区、硬检查、独立审查和提交统一进入委派路径。

| 路径 | 入口 | 触发 |
|------|------|------|
| ReAct | `Agent.java` | 默认模式 |
| Plan 编排 | `AgentOrchestrator.java` | `/plan` |

主 Agent 默认自己写入。写角色只能同步执行，执行期间主 Agent 等待；重叠写角色在创建模型或工作区前拒绝。后台仅允许只读角色，内置为 explorer/reviewer。隔离工作区、资源租约、硬检查和 PatchSet 继续复用，资源租约策略不变。

`DelegationState` 仍在 `~/.devcli/delegations/<projectHash>/` 保存报告、子任务历史和未提交补丁，可通过 `DEVCLI_DELEGATION_DIR` / `devcli.delegation.dir` 设置。`resume_report_id` 续接重新绑定当前规则和预算，角色、工具和写入范围只能保持或收窄；`delegate_control` 保留 status/wait/cancel。只读后台任务使用原有有界队列和共享预算，取消向 HTTP 与工具传递；写角色的后台请求直接拒绝。Java 或根级 `pom.xml` 修改仍须通过 `PreReviewVerifier` 后才能提交。

委派只继承冻结的 system 规则与 Skill 快照、显式 task/context，不复制父会话、长期记忆召回或兄弟消息。子 Agent 不能委派、遍历长期记忆、访问外部副作用工具或恢复其他运行的工具结果；其工具定义快照按 `READ_ONLY` / `ISOLATED_PROJECT` 能力范围生成，不向模型暴露无权调用的工具。父子共享 Token 和总轮数预算，重复动作/错误按各自循环检测。每批最多并行 10 个工具，子任务默认 32 轮/300 秒，支持 `DEVCLI_DELEGATE_MAX_ITERATIONS` / `DEVCLI_DELEGATE_TIMEOUT_SECONDS`。取消向下传递到工具与 Anthropic/OpenAI-compatible HTTP 请求；未提交的子工作区产物不应用，已越过提交检查点的原子补丁不因取消自动回滚。

委派角色来自 `src/main/resources/agents/*.md`（默认 explorer、worker、reviewer）、`~/.devcli/agents/*.md` 和项目 `.devcli/agents/*.md`，后者覆盖前者；项目绑定时冻结目录。每个文件用 frontmatter 声明 name、description、capability、allowed_tools，可选 provider/model，正文为提示词。能力只允许 READ_ONLY / ISOLATED_PROJECT，始终受父权限、Skill 和工具效果约束；角色名不授予写权限。planner 可作为自定义只读角色添加。角色文件属于受保护的 Agent 配置，不能由运行中的文件工具改写。角色描述进入 delegate_task 说明；模型配置仍可使用已有 `DEVCLI_DELEGATE_<ROLE>_PROVIDER/MODEL`，文件配置优先。详见 ADR 0018。

委派支持通过 `context` / `constraints` 显式传递选定记忆，不自动开放长期记忆。Runtime 在失败、预算退出、取消、Reviewer 拒绝和提交冲突时保留有界工具观察、失败尝试及未解决项；`knowledge_outcome` 与 `patch_status` 分开，成功工具观察不等于验收通过，清理失败也不把已提交补丁改报为未提交。报告最多 64 条，按未解决问题、已提交产物、工具证据、普通报告优先保留，同级按最近使用淘汰；上游输入在准入时固定快照。尚未新增报告到工作记忆的结构化晋升或依赖图保留。委派准入只保留一条语义判据：执行粒度自述为多轮循环，声明为单工具操作时拒绝。参数结构校验（inputs/scope/done_condition、deliverable、Worker 写入范围）由工具 schema 与语义校验层承担，以参数错误返回而非策略拒绝，使模型能区分「参数写错了」与「这个任务不该委派」。收益评估（作用面隔离、传递成本）降级为运行事件中的诊断分数，不参与放行也不回灌模型——把分数告诉模型只会诱导它修改声明措辞。单会话累计派出量有配额（默认 200，`DEVCLI_DELEGATE_MAX_PER_SESSION` / `-Ddevcli.delegate.max.per.session`），真实总量仍由 Token 与轮数预算控制。详见 `docs/adr/0009-委派准入只保留硬门槛.md` 与 `docs/adr/0010-委派程序侧准入对齐参照实现.md`。

委派报告进入主 Agent 上下文前统一标记 `report_security.content_trust=UNTRUSTED`；默认在 JSON 序列化前非破坏性净化模型文本与内联工具摘录中的运行时标签、行首角色前缀，并记录命中模式。可用 `DEVCLI_DELEGATION_REPORT_SANITIZATION_ENABLED` / `-Ddevcli.delegation.report.sanitization.enabled` 关闭形态净化，但不移除不可信标签。该机制不处理自然语言语义注入，报告只能作为核验线索，副作用仍须经过既有权限与执行链。当前不引入纯协调 Delegate Mode：主 Agent 保留直接执行能力，按需委派继续由 `DelegationPolicy` 决定。详见 `docs/adr/0008-委派报告信任边界与协调模式取舍.md`。

`/plan` 的规划和计划审阅属于入口工作流；默认委派角色不再包含 planner。主 Agent 维护工作计划，explorer 查事实，worker 同步修改，reviewer 独立审查。

`AgentOrchestrator` 装配计划工作流；`PlanCoordinator` 处理计划协议，`StepExecutionCoordinator` 只保留依赖调度、既有原位重做和执行产物投影，通过 `DelegationSession` 执行步骤；`ReviewCoordinator` 保留计划/最终验收；`CheckpointCoordinator` 与 `DelegationCheckpoint` 接入同一工作区提交和恢复生命周期。旧独立 Worker 循环、瞬态重试和逐步 Reviewer 重试路径已删除。

Plan 的任务适配由 `PlanTaskBatchExecutor` 负责，`/plan` 步骤分波由 `MultiAgentBatchExecutor` 负责；两者共用 `OrchestrationWaveExecutor` 的有界并发、异常归属、输出缓冲与稳定顺序归并。`/plan` 的执行子循环统一委派，计划确认、硬检查、最终审查及 checkpoint 由对应 Coordinator 承担。

计划 DAG 继续使用 `ExecutionGraph` 校验依赖、环和最终集成就绪条件。`ExecutionArtifact` 保存真实终态。Planner 输出结构化验收标准，确定性预检负责拒绝无效计划；独立计划 Reviewer 的语义意见进入用户确认。步骤执行统一委派，最终集成通过 `ReviewCoordinator` 复核验收点；真实硬检查失败继续阻断。

Agent、Plan、Worker 和 Reviewer 的流式输出状态机统一委托 `AgentStreamPresenter`；Memory/Compactor/Skill/MCP 恢复装配统一委托 `AgentRuntimeSupport`。旧内部 Renderer 包装类只保留兼容构造器，不再维护第二套状态逻辑。关键启动配置使用 `ConfigResolver`，显式非法值在加载时拒绝。

计划生成与语义审查保留既有 JSON 提取及有界协议修复；步骤类型、验收字段和 DAG 由确定性预检校验。空工作区合法，存在性检查应并入实现步骤。旧 Worker 的强制写工具修复、写后提前收尾及独立重试循环已删除；步骤采用统一委派，写入/命令步骤缺少真实成功证据时不得记为完成。

`SessionMemory` 对计划生成、审查和最终验收仍提供按角色收窄的执行投影；委派子任务只取得冻结规则、Skill 和显式任务上下文，不复制父会话。工具证据继续携带 agent、step、origin sequence 与 context_epoch，按逻辑序拒绝旧执行的迟到证据。

计划批次保留资源冲突分波及只读并发；`SingleWriterScheduling` 把每个写步骤放入独占波次。Worker 仅保存身份字符串与步骤绑定，用于原协议 checkpoint 恢复；不创建 Worker SubAgent、不维护 Worker 历史、fork 模板或实例锁。SubAgent 只承担计划生成与独立审查。

同步写角色仍经过委派写白名单、资源租约、`ContextVersionLedger` 和 PatchSet 版本闸门；同一时刻只有一个写角色。租约竞争处理保留，不能依靠独占调度绕过它。注册表和项目 fork 共用既有租约维护器。

编排任务的父 ToolRegistry 与隔离 fork 共享 `ContextVersionLedger`，按项目相对路径记录文件 generation、整文件指纹和 Java class/method 符号指纹。`write_file` / `edit_file` 在租约之后执行确定性版本校验；内容未变化时不推进 generation，也不污染 RAG dirty 集。PatchSet 在项目锁内应用前再次校验，覆盖 `execute_command` 等间接写入。检测到 `STALE_CONTEXT` 后，执行内核发出强类型 `context.refresh` 生命周期事件，自动刷新受影响资源并要求 Worker 基于新内容重新生成修改；刷新后重写同一资源时，PatchSet 基线只对已确认刷新且由该 scope 重写的文件重绑定。非 Java 和 JavaParser 失败的普通 file chunk 使用整文件指纹，`file#N` 分段证据暂不进入写闸门。

副作用执行协议：工具通过 `ToolEffect` 声明 READ_ONLY / LOCAL_CONTEXT / PROJECT_MUTATION / HOST_PROCESS / EXTERNAL_MUTATION，执行管线按 `ToolAccessScope` 强制能力范围；非隔离任务只能使用只读和本地上下文工具，隔离任务允许项目写入与受限命令，但禁止外部副作用。隔离任务的 `execute_command` 与 Pre-Review 默认进入 Docker，不可用时失败关闭；可显式设置 `DEVCLI_COMMAND_SANDBOX_MODE=HOST_WARN` / `-Ddevcli.command.sandbox.mode=HOST_WARN` 在主机运行第一阶段白名单命令，不做自动回退。`HOST_WARN` 仅允许 Maven 离线执行 `clean/validate/compile/test-compile/test/package/verify`、`javac` 和只读 Git 子命令，拒绝命令行指定的任意 Maven 插件、发布阶段、命令串、管道、重定向、网络工具和写入型 Git 操作，并输出风险提示。默认 Docker 镜像 `maven:3.9.9-eclipse-temurin-17` 必须提前拉取，容器禁网、只读根文件系统并限制能力与资源；可用 `DEVCLI_COMMAND_SANDBOX_USER` / `-Ddevcli.command.sandbox.user` 指定非 root UID:GID。每次运行分配唯一容器名，超时或取消先终止客户端进程树，再用有界 `docker rm -f` 确定性清理容器。MCP 服务端 readOnly 注解默认不可信，只有本地 `trustReadOnlyAnnotations` 或 `readOnlyTools` 才可授权只读，`deniedTools` 不注册，destructive/openWorld 始终视为外部副作用。Plan 副作用步骤使用 `WorkspaceExecutionSession`；工作区后端默认 `auto`，Git 项目使用原生 worktree 并叠加当前未提交、删除、未跟踪和被忽略文件，明确匹配的 `.env`、凭据文件和私钥文件统一过滤，不按普通源码文件名中的 `secret` / `credential` 字样误删，非 Git 目录优先使用文件系统级写时复制；Linux 只接受强制 reflink，Windows 11 24H2 / Windows Server 2025 及以上版本只在 ReFS 上启用系统块克隆，其他平台或失败场景回退有界复制，可通过 `DEVCLI_WORKSPACE_BACKEND=git|cow|copy|auto` 指定。worktree 物化后删除排除目录、符号链接和敏感文件，创建、注销及 prune 均在项目级锁内执行。批准后逐文件流式哈希生成 `PatchSet`，只读取变更文件内容；JVM 公平锁和跨进程文件锁共同串行化写前准备、全量冲突预检、应用和 checkpoint 终态。应用中途失败会回滚并报告未恢复路径；PatchSet 同时校验 afterHash，在 POSIX 文件系统保存完整 rwx 权限并把纯权限变化纳入冲突与恢复对账，其他平台兼容显式可执行标记。工作区创建前清理超过 TTL 且没有活动文件租约的孤儿目录，默认 24 小时，可通过 `DEVCLI_WORKSPACE_ORPHAN_TTL_HOURS` / `-Ddevcli.workspace.orphan.ttl.hours` 调整。worktree 后端单独计量叠加成本（额外复制的未提交/未跟踪字节数，默认 256MB，`DEVCLI_WORKSPACE_OVERLAY_BUDGET_BYTES` / `-Ddevcli.workspace.overlay.budget.bytes`），超预算时告警并列出最大来源；`workspace` 包保持 JDK-only（内存探针用最小 classpath 子进程），故用 `System.Logger`。不按体积自动切 CoW：CoW 子工作区不含 `.git`。写时复制设计见 `docs/filesystem-cow-workspace-design.md`。

非默认 Maven 仓库只能通过 `DEVCLI_COMMAND_SANDBOX_MAVEN_REPOSITORY` / `-Ddevcli.command.sandbox.maven.repository` 显式指定存在的绝对目录。Docker 仅对 Maven 命令只读挂载，`HOST_WARN` 显式传入 `maven.repo.local`；运行时不自动扫描或硬编码开发机路径。

Windows 下已通过 `HOST_WARN` 策略校验的命令使用 `cmd.exe` 执行，避免 PowerShell `-Command` 改写 Maven `-Dname=value` 参数；普通主机命令仍使用 PowerShell。重复调用指纹保留命令原文语义，带引号的纠正命令不会与未加引号的失败命令合并。

代码补丁在委派提交前由 `PreReviewVerifier` 执行 Java/Maven 硬检查；失败保留候选并拒绝提交。独立 Reviewer 按现有 `DelegationReviewGate` 信号触发；`/plan` 的最终集成仍通过 `ReviewCoordinator` 复核验收点。编辑后的 LSP 诊断继续沿用现有写入 hook，在下一次模型调用前注入，不增加第二套检查。

独立委派 Reviewer 返回结构化审查结果；`/plan` 最终审查沿用现有验收协议。真实硬检查失败继续阻断；成功执行硬检查后的 Reviewer 建议规则保持现有边界。工具成功只是观察，不自动等于用户验收通过。

Final integration 只做入口/API/默认参数/跨模块联动胶水；普通步骤失败比例达到 `50%` 时熔断，不让最终步骤强行修补。

计划保留既有原位重做与 checkpoint 协议 10。旧 checkpoint 继续由现有迁移器读取并转换为执行步骤；恢复后步骤接入委派。未验证补丁恢复到新工作区时统一调用 `WorkspaceExecutionSession.restorePatch`，登记新作用域的局部写入；提交继续在项目锁内记录写前日志、应用、持久化终态与清理日志。环境失败保留 deferred PatchSet，冲突、回滚不完整和高版本不兼容继续停止恢复，不另建恢复协议。

Side-Git 快照按 `devcli.snapshot.max` / `DEVCLI_SNAPSHOT_MAX` 保留最近快照；每次新建快照后重写 side-history，只保留最新 N 条；裁剪后调用 JGit `autoGC`，由其原生阈值决定是否后台维护，不再自研对象 GC，也无相关调优项。

副作用横向信息流：write_file/execute_command 等副作用工具的证据在 `SessionMemory.EvidenceJournal` 中按高重要性保留；普通读取优先压缩或淘汰，失败压缩成 AttemptDigest，使后续步骤持续看到本任务改过哪些文件和已经排除的方案。

职责边界：`agent_work_plan` 是工作意图的唯一来源，保存在 `SessionMemory.taskState`，由 `update_plan` 维护目标和步骤；in_progress 表示当前工作，首个 pending 表示下一步。ReAct 与 `/plan` 共用，压缩和恢复原样携带同一状态；旧 checkpoint 步骤在恢复时导入工作计划。`TaskLedger`、SessionMemory 的工具证据和恢复段是运行投影；`ExecutionArtifact` 仍是执行终态来源，模型计划、模型报告与实际执行证据分开。RollingSummary 只治理上下文，不创建第二份计划。

模型写工具为 `write_file` / `edit_file`，删除使用 `delete_files`。`edit_file` 默认唯一匹配，`replace_all=true` 批量替换；精确匹配失败时复用 SeekSequence 的完整行空白匹配并检查歧义。`glob` 通过 ProjectFileLister 按项目相对模式查找文件，遵循 Git 忽略规则；`search_code` 不再注册。工作计划、记忆读取和 MCP 调用分别使用 update_plan、read_memory 和 call_mcp_tool。

`ApplyPatchParser` / `ApplyPatchCompiler` 保留官方补丁解析与内存编译，产物可交给既有 PatchSet API。`ApplyPatchTool` 及专用审批路径已删除；工具目录、发现和执行管线无需按名称隐藏补丁工具。

工具归属由 `BuiltInToolPolicy.Policy.mainAgentOnly` 显式声明；委派准入、工具发现和执行共用该契约。归属与读写能力分别判断，只读模式的主 Agent 仍可维护工作计划；子 Agent 的角色白名单不能开放主计划和长期记忆入口。

模型端代码 RAG 已关闭：不注册 `search_code`，删除专用 RagToolProvider 及其检索器持有路径；模型代码定位使用 glob、grep_code 和 read_file。显式 `/index`、`/search` 与 RAG 检索组件保留，仍支持 keyword/semantic/graph、RRF、重排、失效事实和索引版本校验；这些组件能力不代表默认 Agent 会自动使用 RAG。
索引构建开始时记录 `base_epoch` 并标记 dirty 文件；增量构建把未变化代码块及 embedding 复制到 SQLite 影子表，只重新分块和嵌入 dirty 文件，并保守重算项目关系图。影子候选通过校验后，在事务内执行 `base_epoch + generation` CAS 和原子提升；旧构建不能覆盖新 epoch，未提升候选对检索不可见。检索结果标记 `CURRENT` / `STALE` / `DIRTY`；DIRTY 和 STALE 命中默认回读实时内容校验，变化内容重新分块，无法校验时保留警示。项目级 `WatchService` 会把外部文件变化标记为 DIRTY。

量化评测规范见 `docs/benchmark-evaluation.md`，覆盖 RAG、Agent、Memory 和 Context Compression / Long Context。正式结果只接受公开集合原始任务、固定版本、SHA-256、官方 harness/evaluator 和完整原始报告；公开集合与历史自建测试严格分离。当前 RAG 适配器输出二值相关性的 Recall@5、MRR@5、nDCG@5，不等同于 CodeSearchNet Challenge 使用人工分级相关性的官方 nDCG；CodeXGLUE 的 MRR 是另一套协议。SWE-bench Java 评测目标为 Multilingual Java 子集，正式 resolved 只取官方 Docker harness 报告。当前 LongMemEval、LongBench v1 和 RULER 入口直接调用模型，尚未接入项目记忆或压缩功能的开关对照，不得用于宣称对应功能收益。正式评测须记录官方指标、子任务、长度和样本量。旧的项目内 CLI、订单 Saga、Checkout、对抗记忆、压缩、并发和合成 RAG 结果只在该文档历史归档中保留，不得作为当前事实或简历数字。SWE-bench 三模式脚本用 `git add -N` + binary diff 纳入新增文件，并记录输入/输出/缓存 Token 和估算成本；其 Docker 退出码、patch 应用状态、日志信号与 `meta.expect` 检查仅构成本地诊断，即使使用官方镜像和 `eval.sh`，自定义 `resolved` 也不能替代尚未接入的官方评分报告。显式 `HOST_WARN` 仅用于受限主机检查，日志不记录本机仓库绝对路径。权限分类器（`auto` 模式）另有一条**组件级**定向诊断：`benchmarks/permission-classifier-evals/v1/cases.jsonl` 是带标签的自建对抗清单加合法请求对照，`benchmarks/src/main/java/com/devcli/eval/PermissionClassifierDriver.java` 直接调用 `PermissionClassifier.classify`，按逃逸（期望拒绝却放行）与误拒（期望放行却拒绝）分开统计、外部失败单列、并按 `default` / `isolated_only` 拆分可达性；它不经过权限求值链、不执行工具、不需要 Docker，度量的是提示词与模型的判定质量，**没有**补上 Agent 级安全评测缺口，样本为自建、不得写成公开 benchmark 成绩，「本批 0 次逃逸」不得表述为普遍安全保证（`docs/permission-classifier-evals.md`）。

长期记忆以主题 Markdown 为**唯一权威存储**：全局记忆位于 `~/.devcli/memory/global/`，项目记忆位于 `~/.devcli/memory/projects/<projectKey>/memory/`。每个作用域的 `MEMORY.md` 可从主题文件重建，不是第二权威。frontmatter 承载 `name` / `description` / `type` / `created_at` / `updated_at` / `expires_at` / `revision`，正文在 body。

项目记忆在绑定时解析 Git 主工作区根路径，关联工作树和仓库子目录共享作用域，嵌套独立仓库保持隔离；普通目录或 Git 元数据不可用时沿用请求路径。旧路径主题只在绑定该路径时复制到共享目录，源文件保留、同名冲突不覆盖，状态显示迁入、冲突与失败数量。每个旧来源有独立完成标记，删除或清空主题不重置标记；失败修复后重试。详见 `docs/adr/0016-Git工作树共享项目记忆.md`。

`LongTermMemory` 每个作用域最多扫描 200 个主题，MEMORY.md 最多注入前 200 行或 25000 字节，并受本轮索引 Token 预算约束。每轮只注入索引，不再调用相关性选择模型；需要正文时用 `read_memory(name, scope?, offset?, limit?)` 读取当前作用域中未过期的主题，项目同名主题优先。该入口不接受任意路径，也不放宽 read_file 的项目围栏。

过期记忆仍保留用于审计，但不进入索引、候选或检索。同作用域同主题的自动冲突保留原文，用户显式保存才覆盖并增加修订号。Curator、晋升/确认队列、Evidence 审核态、旁路计数和旧关键词打分器已移除。详见 `docs/adr/0007-长期记忆对齐-workbuddy.md`。

MCP 动态工具：`mcp__{server}__{tool}`（+ resources 虚拟工具）

Windows 原生隔离后端通过 `DEVCLI_COMMAND_SANDBOX_MODE=WINDOWS_NATIVE` 显式启用，仅覆盖要求沙箱的命令；默认仍为 Docker，普通主 Agent 主机命令不自动转入沙箱。原生启动器使用每次执行独立 AppContainer、空网络能力、显式目录 ACL 与 Job Object kill-on-close，复用已有工作区和 PatchSet，不放宽审批，启动失败不回退主机。配置 `DEVCLI_WINDOWS_SANDBOX_LAUNCHER` 指向可信启动器，运行时目录通过 `DEVCLI_WINDOWS_SANDBOX_READ_ROOTS` 显式只读授权。本地 stdio MCP 可独立配置 `sandbox: "WINDOWS_NATIVE"`，项目只读、禁止配置环境变量，不覆盖远程 MCP。异常强杀后的残留身份和 ACL 自动回收尚未实现；使用方式及边界见 `docs/windows-native-sandbox.md`、ADR 0014。

模型调用可靠性链路：Anthropic 与全部 OpenAI-compatible Provider 统一抛出 `LlmException`，错误码覆盖认证、限流、过载、超时、网络、参数、上下文超限、内容过滤、服务端和响应格式错误；只对限流、过载、超时、网络和 5xx 做指数退避有界重试，流式内容开始输出后禁止重试。OpenAI-compatible 默认按标准协商 HTTP/2 与 HTTP/1.1，仅在异常兼容网关上通过 `DEVCLI_LLM_HTTP_PROTOCOL=HTTP_1_1` / `-Ddevcli.llm.http.protocol=HTTP_1_1` 显式降级，非法值失败关闭。工具调用流同时兼容标准增量片段、累积快照和完整字段重复发送，避免工具名或完整 JSON 参数重复拼接。SubAgent 错误消息保留标准错误码和 `retryable` 标记，Orchestrator 不再依赖具体网络错误文案判断瞬时故障。默认 3 次、500ms 初始退避、8s 上限、0.2 jitter，可通过 `DEVCLI_LLM_RETRY_*` 或对应系统属性调整。

工具调用可靠性链路：LLM 先按 reasoning 说明目标、工具选择和参数来源；工具定义使用 JSON Schema 强约束类型、必填项、枚举值和未知字段；`ToolRegistry` 通过 `ToolExecutionPipeline` 分阶段执行取消、工具存在性、能力范围、Skill 权限、参数校验、HITL、审计、策略和结果尺寸治理；并行工具线程显式继承能力范围、资源租约和 Skill buffer 快照，项目 fork 复制 `SkillContextBuffer`，不共享可变状态；工具结果使用 `ToolStatus`、`ToolErrorCode` 和 retryable 结构化表达；内置 Provider 可通过结构化执行器直接返回状态，参数错误、策略拒绝、命令非零退出、超时和取消不再先压成普通文本；ReAct、Plan、SubAgent 的重复错误熔断不再依赖结果文本关键词；执行前通过 `json-schema-validator` + 本地兜底校验内置工具和 MCP 工具参数，失败以 `工具参数校验失败` 回传模型修正；模型工具定义在执行循环内固定；MCP 不预激活到工具列表，`search_tools` 使用目录缓存返回当前 schema，固定 `call_mcp_tool` 执行目标并重新经过完整管线；未知工具会提示先调用 `search_tools`；危险工具继续走 HITL / Policy / AuditLog；工具参数通过稳定语义指纹参与停滞判断，JSON 字段顺序、查询大小写、Unicode 等价字符和冗余空白不会绕过重复检测；正则 pattern 保持大小写敏感，避免错误缓存命中；成功且无图片的 READ_ONLY 工具结果按会话短期缓存，任何非只读工具执行和项目路径切换都会清空缓存；MCP 工具结果被截断或落盘预览时会标记折叠分类；工具结果进入 SessionMemory，最终回答必须用工具证据闭环。

工具契约是显式声明，不按工具名推断：`ToolEffect` 只表达资源域（READ_ONLY / LOCAL_CONTEXT / PROJECT_MUTATION / HOST_PROCESS / EXTERNAL_MUTATION），另有正交的破坏性等级 `Destructiveness`（NONE / BENIGN / STRUCTURAL）、幂等性 `Idempotency`（IDEMPOTENT / NON_IDEMPOTENT）和 `cacheable` 缓存资格。内置工具的唯一声明点是 `BuiltInToolPolicy`（effect + approval + audited + 契约三维）；MCP 工具由 `mcpTool` 保守推导，本地只读授权只代表访问等级、服务端 annotations 默认不可信，因此只读授权工具声明为 `BENIGN` + 非幂等 + 不可缓存，其余为 `STRUCTURAL` + 非幂等 + 不可缓存。未声明契约的第三方工具按 effect 取保守默认（只读/本地上下文为 NONE + 幂等 + 可缓存，与历史行为一致），未知工具按外部实质变更 + 非幂等处理。

契约驱动两个执行决策：`isSerializedSideEffect` 只对「READ_ONLY 或 LOCAL_CONTEXT 且声明 IDEMPOTENT」放行同批并行，非幂等只读工具（首读即改状态、游标推进这类）与副作用工具一样串行，且无快照的直接执行路径仍从注册表取回真实契约、不按名字重新推导；`RESULT_CACHE` 阶段用 `cacheable` 声明替代按名字硬编码的黑名单，依赖外部可变状态的内置只读工具（文件、网络、记忆、浏览器态）显式声明 `cacheable=false`。`Tool` record 的 8 参兼容构造器按 `BuiltInToolPolicy` 推导契约，`toolDestructiveness` / `toolIdempotency` / `toolCacheable` 供审计与测试读取。审批展示层 `ApprovalPolicy` 的等级与风险说明同样由声明推导，未在本地声明契约的工具不再被描述成「安全只读」。改动契约维度时同步 `BuiltInToolPolicy`、MCP 推导、`ToolContractTest` 和本文档。

执行内核补强：`AgentExecutionEngine` 按原始 `tool_call_id` 对结果去重、拒绝未知结果、补齐缺失结果并恢复原始顺序；并行危险调用的审批输入通过共享公平锁串行化，项目 fork 复用同一审批仲裁；工具声明 `COOPERATIVE` 或 `INTERRUPT_ONLY` 取消能力；重复提醒与硬熔断分别记录结构化动作。取消或模型调用失败时，已经流出的 reasoning / content 由 `RunEventStreamListener` 交回并写进历史（只补文本、不补 tool_call，避免留下没有配对结果的调用），否则内容只在终端显示过、重放或压缩后就消失。

## 仓库结构

```
src/main/java/com/devcli/
├── agent/       Agent.java, PlanExecuteAgent.java, SubAgent.java, AgentOrchestrator.java, PlanCoordinator.java, ReviewCoordinator.java, CheckpointCoordinator.java, StepExecutionCoordinator.java, OrchestrationRunState.java, OrchestrationNarrative.java
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
├── trace/       RunEventTraceSink, TraceContext, TraceQuery, TraceRecorder
├── event/       RunEvent, RunEventSink, RunEventStreamListener
├── session/     SessionTree, SessionTreeService
├── config/      ConfigResolver, DevCliConfig
├── snapshot/    SideGitManager, SnapshotService
├── workspace/   IsolatedWorkspace, WorkspaceExecutionSession, PatchSet, WorkspaceBackend, ProjectCommitCoordinator
├── tool/        ToolRegistry + patch/ (ApplyPatchParser, SeekSequence, ApplyPatchCompiler, ApplyPatchTool)
├── mcp/         McpClient, McpServerManager, transport/, resources/, mention/
├── hitl/        HitlToolRegistry, ApprovalPolicy, TerminalHitlHandler
├── hook/        HookConfigLoader, HookDefinition, HookDispatcher, HookEvent, HookLifecycle
├── skill/       SkillRegistry, SkillPathMatcher, SkillContextBuffer, SkillIndexFormatter
├── extension/   ExtensionContract, ExtensionRegistry
├── web/         SearchProvider, WebFetcher, HtmlExtractor, NetworkPolicy
├── policy/      PathGuard, CommandGuard, AuditLog
├── util/        AnsiStyle, JiebaSegmenterFactory, TerminalMarkdownRenderer, VectorMath
├── concurrent/  CancellationContext, CancellationToken, RunContext
└── render/      Renderer, InlineRenderer, PlainRenderer, RendererFactory
```

该树是 `src/main/java/com/devcli/` 下的完整包清单（29 个）；`src/test/java/com/devcli/` 按同构包名组织，另有 `benchmark/` 与 `eval/` 两个测试专用包。旧 `tui/` 空目录已随 `TuiBootstrap` 删除一并清理，交互渲染器只保留 Inline 与 Plain。

Runtime API 只绑定 `127.0.0.1`，请求线程与 Agent turn 执行线程隔离；turn 执行池默认 2 线程 / 64 队列，过载返回 `429 runtime_busy`；`KeyedSerialExecutor` 保证同一 thread 串行。CLI、Runtime API 和后台任务通过 `RunCoordinator` 写入同一 `RunStore`，后台任务不再维护独立状态表；旧 `tasks.db` 只读导入 `runtime.db`。CLI `/session` 与 Runtime branch 共用持久事件树，切换分支只重建 Agent 历史，不恢复工作区；`/branch` 仅为兼容别名。检查点和会话投影是事件日志的可重建缓存。模型 reasoning/content delta、模型上下文、工具调用、工具结果和 turn/checkpoint 生命周期统一使用强类型 `RunEvent`；`AgentExecutionEngine` 是领域事件出口，Runtime API 使用 schema v2 JSON 投影。交互、后台任务和无头 turn 使用运行级 `RunContext` 隔离项目路径、取消令牌和资源生命周期。

执行状态同样使用强类型 `RunEvent`，由执行内核输出 `THINKING`、`TOOL_EXECUTING`、`TOOL_RESULTS_PAIRED` 以及完成、取消、预算退出、迭代上限和失败终态；失败终态同时输出 `failure.guidance` 的分类、建议与动作列表，供 CLI 与 Runtime 投影统一消费。

会话事实与模型窗口分离：CLI、`-p` 和 Runtime API 在执行期间向 `runtime.db` 的追加事件表写入事实；`tool.result.recorded` 保存内容审查后、尺寸裁剪前的返回结果、图片及生产者产物引用。工具生产者的输出配额和不完整标记继续有效。`model.message` 保存消息事实，`model.context` 保存初始化或请求前的窗口快照；`ModelContextView` 从事件生成模型请求，`ConversationReplay` 负责持久事件重放，压缩不删除旧事件。恢复包括未完成回合，缺失结果只生成状态未知的工具配对，不重放副作用；用户授权历史单独从真实输入事件恢复。子 Agent 使用带身份的 `agent.event`，不并入父窗口。旧日志缺失的正文无法补回。详见 `docs/adr/0022-会话事件日志与上下文视图分离.md`。

Execution Trace 经 `RunEventTraceSink` 写入 `~/.devcli/traces/trace-yyyy-MM-dd.jsonl`，runId 复用 `RunContext.runId()`。trace 只保留结构诊断字段：跳过流式正文、ModelContext、ModelMessage 和 ToolResultRecorded；子 Agent 仅记录身份及相同的结构字段，不复制正文。`/trace` 默认看最近一次运行，`/trace list` 看运行聚合，`/trace <runId>` 看指定运行。trace 和可选 SessionArchive 都不参与会话恢复。

受控 Hook 生命周期：`AgentExecutionEngine` 在 ReAct、Plan task 和 SubAgent 共用 agent/turn/message/tool execution 四层幂等生命周期。Hook 配置从 `~/.devcli/hooks.json` 与项目 `.devcli/hooks.json` 按 id 合并，或由 `DEVCLI_HOOKS_FILE` / `-Ddevcli.hooks.file` 指定；最多 64 条。Hook 只能调用 ToolRegistry 已注册工具，不直接开放 shell/HTTP 执行器；READ_ONLY / LOCAL_CONTEXT 强制在只读能力范围执行，其他 ToolEffect 必须显式 `allowSideEffects`、使用已启用的 `HitlToolRegistry`，且目标工具必须有逐次审批策略，否则拒绝。所有调用继续经过参数校验、HITL、策略、审计和当前工作区能力范围，不允许 Hook 提升 Plan/SubAgent 的权限。`warn` 失败只记录警告，`required` 失败进入 Agent 统一失败出口；异常和取消出口都会按 message → turn → agent 顺序闭合生命周期。模板位于 `Config/hooks.example.json`。

统一扩展目录：Skill、Hook、MCP server 与 CLI command 共用 `extension/` 的 `ExtensionContract` / `ExtensionRegistry`，`Kind` 区分四类扩展，`Source` 区分 BUILTIN / USER / PROJECT / RUNTIME。`Main` 在启动装配时注册（含 `syncHookExtensions` 按 hook 配置刷新），`DevCliCompleter` 从这里取 `/mcp`、`/skill` 的补全候选，避免各模块各自维护一份发现逻辑。该契约只统一发现与生命周期元数据，不替代各模块已有的权限、HITL 和执行管线；`replaceKind` 用于整类替换，`register` 对重复 id 拒绝而不是覆盖。

启动与 inline 渲染当前约定：

- 交互渲染器只保留 Inline 与 Plain。`DEVCLI_RENDERER=lanterna|tui` 和旧 `DEVCLI_TUI=true` 在兼容期映射到 Inline；旧 `TuiBootstrap` 已删除。

- 开屏 Banner 使用无右边框的简洁布局，避免 CJK/ANSI 字宽导致右侧竖线错位；Phase 22 后默认是 π 主题彩色 logo + Qoder 风格首屏，只展示模型、MCP、Skill、ReAct 状态和三条 getting-started tips，不再把 MCP server 明细刷成启动日志。
- inline 模式使用 JLine 4 的 LineReader 编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`。
- 默认 CLI 启动路径应先 `Renderer.start()` 并初始化底部 dock；inline 首屏不要在 `readLine` 前裸写 stdout，而是通过 `InlineRenderer.installStartupScreen(...)` 挂到 `LineReader.CALLBACK_INIT`，首次进入输入时用 `printAbove` 一次性显示完整 Banner + tips，避免 logo 被 LineReader 首次重绘滚出可视区域。
- `BottomStatusBar` 现在是 JLine `Status` 托管的底部 dock：由 JLine 维护滚动区域和状态行位置，不再手写 `\n` / `moveUp` / `CLEAR_TO_EOS` 清屏。输入期会把 LineReader 光标定位到 dock 上方一行，让 `*` 输入行和 Status 同处底部区域；dock 保留两类信息：上层模式 + MCP/Skill 摘要，下层 Auto Model / model / phase / ctx 百分比与 token / cost / elapsed / cwd。
- 普通任务提交后，`Main` 会把本轮原始用户 prompt 以暗色整行块写回 transcript：输入态左提示仍是 `* `，提交回显左提示改为 `>`；单行输入只占一行，不额外追加空白行。随后再展开 MCP resource / 本地 `@path` 并进入 Agent；不要只依赖 JLine 提交行残留，否则 activity 重绘或 dock 刷新可能让用户提示词从可见历史里消失。
- ReAct LLM 调用期间，inline renderer 使用固定高度 live thinking 区动态显示 `Thinking...` 和灰色竖线 reasoning 预览；该区域只能清理自己刚打印的几行，不能用独立 JLine `Display.update()` / `CLEAR_TO_EOS` 向上覆盖 transcript。content 或 tool call 开始前先清掉 live 区，再把完整 reasoning 引用块落到正文区，正文回答用低调标记起始，不再刷强标题。
- 交互期输出应优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner`、`AgentOrchestrator` 都支持把输出流接到 inline renderer，避免直接争抢 stdout。`CodeIndex` 的索引进度通过 `ProgressListener` 注入，`/index` 应绑定到当前 renderer 输出流。
- Phase 22 开始，`InlineRenderer` 可绑定当前 `LineReader`；当 `LineReader.isReading()` 为 true 时，`Renderer.stream()` 的完整行输出优先通过 `LineReader#printAbove` 显示在输入行上方，未绑定 / 非读取态 / 测试路径回退到原 `PrintStream`。
- ReAct 正常结束后不再把 `📊 Token: ...` 打进正文区；token/cost/elapsed 会保留在底部强状态行，phase 回到 `idle`。
- 默认 CLI 启动路径应尽早建立 `Terminal -> LineReader -> Renderer`，启动 Banner、模型加载、MCP 启动、Skill summary、ReAct 提示和退出提示都应走 `Renderer.stream()`；除 fatal bootstrap / runtime API 外，不要在交互主路径新增裸 `System.out.println`。
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

- 记忆按生命周期区分当前窗口、任务运行状态与长期主题；工作计划作为 SessionMemory 中单独的 agent_work_plan 状态保存，执行证据不从计划状态推断。
- `SessionMemory` 通过 `accept(SessionEvent)` 统一接收工具结果、用户确认和步骤变化；内部 `WorkState` 按键覆盖或按步骤状态机推进，`EvidenceJournal` 按 CRITICAL / FAILURE / MILESTONE / ORDINARY / REGENERABLE 分级。事件按 agent、step、类型和逻辑 sequence 幂等，旧 origin 证据按 `context_epoch` 拒绝。ReAct、Plan 和 Team 都使用 `beginTask/completeTask/endTask` 明确任务边界
- SessionMemory Prompt 使用单一硬 Token 预算：先保留任务状态、修改文件和失败摘要，再按 importance/sequence 注入关键事件和工具证据；关键原文超限时折叠成规范化引用。Multi-Agent 共享单一实例，真实 agentId、stepId 和单调 sequence 会参与证据归属与迟到计划/步骤事件拒绝；`ExecutionArtifact` 仍是任务终态唯一来源
- `CompactionSummaryCache` 只缓存压缩预摘要，不是记忆；`RuleContext` 加载 `DEVCLI.md` 和 `/rule add` 强约束，支持 `/rule list`、`/rule remove`，旧 `pinned_facts.json` 只列为待分类迁移候选，不会静默当成规则。稳定事实使用 `/save` 写入 `LongTermMemory`，旧 `/save --pin` 仅保留废弃提示
- 长期记忆只由 `/save` 与 `save_memory` 显式写入；普通消息和任务收尾不自动提取。存在活动项目时默认写项目作用域；只有明确跨项目偏好才写全局作用域。未知作用域拒绝，不回落为全局
- `save_memory` 支持 `name` / `description` / `type` / `scope` / `valid_days`；`type` 只允许 `user|feedback|project|reference`，`valid_days` 为 1–3650。过期文件仍可审计，但不进入索引、候选或检索
- 同作用域同主题的自动冲突保留原文；用户显式写入才覆盖并增加 `revision`。全局与项目同名时两份原文均保留，召回使用项目版本并显示作用域冲突
- 敏感内容命中时直接拒绝写入并要求脱敏后重试，不创建持久化确认票据
- 用户显式要求忽略记忆（如“别管记忆”“忽略记忆”）时，本会话不注入长期记忆、通用 SessionMemory 和角色裁剪后的 SessionMemory
- `/memory organize` 显示过期与作用域冲突统计；`/memory organize apply` 从权威主题文件重建所有索引。`/memory export` 导出包含作用域、类型、修订和过期状态的 `memory-audit.md`
- 本轮只注入按 ContextProfile 预算裁剪的长期记忆索引；主题正文经 read_memory 按需分页读取。选择器及其模型调用已删除，旧 relevanceSelection 开关不再有效。
- `/memory clear` 默认只清**当前写入作用域**（有活动项目清项目，否则清全局），`/memory clear global` / `/memory clear project` 显式指定；全局目录跨项目共享，项目会话里的默认清空不得连带抹掉它，未知作用域拒绝并给出可用取值
- 启动时将旧版 `records/` 记忆卡复制到全局作用域并保留旧文件；旧格式没有可靠项目归属，不做项目归属猜测。全部卡片处理成功后写一次性完成标记，后续构造不再重复解析旧源卡片——**迁移因此是一次性的**：落标记后新加入的旧卡片不再补迁，清空全局记忆也不会重置标记（标记在记忆根目录，产物在 `global/`）
- `ConversationHistoryCompactor` 是唯一治理 LLM messages 窗口的压缩点；压缩前先走第 0 层 `microcompact`（触发后的确定性淘汰：只回收内容指纹重复、或同一 `path` 已被更晚 `write_file` / `edit_file` 覆盖的 `read_file` 结果，逐条落盘并替换为 `<microcompact_boundary>` 引用；不按工具名单或保留最近 N 项保护，不删消息、保 tool_call 配对；折叠后 Token 不减少或落盘失败时原地保留；折叠引用按结构识别，要求起始标记 + 闭合 + `type=tool_result`，生成与消费共用 `MicrocompactBoundary`，正文提到该标记不误判），扛不住再摘要。首次摘要按摘要模型 Token 窗口选择单次调用或 Map-Reduce，完整请求能放入窗口时不再按固定 60k 字符强制分片；后续固定保留六段，模型只提出受限生命周期操作，程序负责覆盖、完成迁移和删除；旧九段摘要可解析，但待办、当前工作和下一步会被丢弃。默认每 5 次成功压缩执行生命周期 GC，不再二次压缩旧摘要。摘要写回 history 前必须经过 `CompactionSemanticGuard`；该守卫只保护工作状态约束，不承担未来随机事实检索
- `CompactionSummaryCache` 维护当前进程内会话预摘要，自动压缩时优先复用覆盖同一消息指纹且未过期的预摘要；已有预摘要覆盖当前历史前缀时，只用旧摘要和新增消息生成完整替代摘要；预摘要默认 30 分钟过期，不写长期记忆
- 摘要分片不再使用 60,000 字符上限：首次 Map、增量更新和 Reduce 都按完整请求 Token 预算计算；增量逐批携带上批更新后的摘要，归并按实际预算分组。默认原文尾部随窗口按 8% 滚动（夹在 4K～32K），并受 history 触发阈值的一半约束；显式尾部预算不变。Provider 上下文超限时收紧请求预算重分片，不丢最旧轮次；空分片或增量协议无效不提交压缩历史。详情见 `docs/context-compaction-design.md`。
- RAG 每次检索保存不含代码正文的分阶段审计记录，覆盖 keyword / semantic / graph 候选、RRF 融合、rerank、最终选择和降级状态；普通 CLI 会话归档默认关闭，启用后 ReAct 保存脱敏模型消息，Plan / Team 保存顶层输入输出，`/history clear` 同时删除归档
- 压缩成功后会插入 `[压缩后恢复上下文]` 消息：恢复段按最近读写文件、未完成子任务状态、关键工具结果引用、RAG 证据 epoch 和 MCP 工具状态分节；恢复内容经统一预算与行级去重后注入，Multi-Agent 会按 Planner / Worker / Reviewer 角色裁剪；SkillContextBuffer 追加已加载 Skill 与 allowedTools 状态
- 压缩边界 `<compact_boundary>` 会记录已加载 Skill、RAG epoch、MCP 工具快照和压缩后恢复入口状态；RAG epoch 合并当前会话已命中证据与当前项目全局索引版本，MCP 工具快照按 server 记录工具数量、schema 指纹和生命周期版本
- `McpServerManager` 会记录本进程 MCP 连接事件：STARTING / READY / ERROR / DISABLED / RECONNECTING / TOOLS_CHANGED，事件携带 server、状态、生命周期版本、工具数量和消息；启动失败后会后台自动重连，默认最多 3 次
- MCP 工具发现缓存记录 server、生命周期版本、工具数量、工具名、schema 指纹和发现时间；disable 不清除上一轮发现元数据
- MCP `tools/call` 会携带 `_meta.progressToken`，同 token 的 `notifications/progress` 会汇总进工具结果文本
- MCP 工具结果进入尺寸治理后会标记折叠分类：截断输出为 `INLINE_TRUNCATED`，落盘预览为 `PERSISTED_PREVIEW`
- 工具结果尺寸治理分两层：工具层按单条结果分档（20000 字符内原文放行、20000～100000 截断、超过 100000 落盘给 5000 字符预览，同轮聚合额度 100000 字符由并行线程共享），agent 层在回灌前按整批 100000 字符 / 25000 Token 二次分配；两层阈值均为常量，不提供配置项
- 结构化滚动摘要超过字符上限时执行确定性生命周期 GC；稳定决策和未解决事项不因压缩次数删除，仍超限时保留并告警，不交给 LLM 二次改写
- 历史结构化 RAG 证据中的 negativeFact 携带 oldSymbolVersion 时，SessionMemory 仍可清理失效证据；默认工具列表不再产生 search_code 结果。
- `TaskLedger` 继续投影真实计划执行进度与产物；模型可维护的目标、步骤和下一步统一由 update_plan / agent_work_plan 提供，压缩和恢复不另建计划来源。
- prompt cache：system 只含会话级稳定规则。Project Context、MCP 资源索引、记忆索引、Skill 索引和工作记忆追加到当轮消息。模型工具定义在执行循环内冻结，不再按轮检索裁剪；MCP 搜索返回当前 schema，通过固定 call_mcp_tool 调用，目标仍走参数、权限、审批与审计管线。无工具收尾使用 ToolChoice.NONE 保留定义前缀。真实缓存收益尚未做 API A/B 验证。

### HITL + 策略层

- 审批前完成工具参数、语义、路径与后端硬策略校验，审批后继续执行既有策略及审计
- 用户无法批准策略拒绝的请求
- `ApprovalGate` 做三值判定 `ALLOW / HITL / DENY`。硬边界和拒绝规则优先；独立逐次确认、项目信任、已授权范围和模式决定是否询问或交给分类器。越界与黑名单直接拒绝，不先弹审批
- 授权与策略分层且单向：`TaskGrant` 只表达用户已授权什么，永远只能收窄策略允许集，不能放宽；任何授权与自动审批都不能覆盖 `DENY`
- 所有模式统一按硬策略 → `hard_deny` → `soft_deny` → `allow` 求值；soft_deny 强制人工确认，allow 不再调用分类器。删除阈值、命令删除、回滚、浏览器和 MCP 逐次确认独立于放行规则及模式。auto 忽略过宽命令 allow；主机命令在所有模式下均只接受窄规则。Shell 复合语义不能由命令前缀放行。详见 ADR 0021，替代 ADR 0013 的规则顺序
- auto 与 acceptEdits 直接放行项目内 write_file / edit_file，参数、路径保护和能力检查仍生效。auto 只分类其余未决动作；连续拦截 3 次或累计拦截 20 次后切换 default，并询问当前动作。分类器故障仍拒绝，沿用连续失败阈值；两类计数分别保存，根注册表与 fork 共享，手动切换模式重置
- 分类器只接收真实用户消息与 Assistant 结构化工具调用，不接受工具结果、Assistant 自述或委派报告作为授权。过宽命令 allow 同时从分类器输入中删除；缺失、超时、协议错误和预算不足一律拒绝
- 交互启动首次询问当前项目是否可信，按真实项目路径在 `~/.devcli/project-permissions.json` 保存肯定或否定决定；不提交，保留到用户修改。信任只开放主机命令的规则/模式审批，不代表允许全部命令，也不继承 Skill 目录信任。未信任项目的主机命令逐次确认；`-p` 只读取已有决定，无审批通道时拒绝未授权动作
- 全局持久规则在 `~/.devcli/config.json`；命令审批中的 `[p] 总是允许此命令前缀` 将当前完整命令前缀写入该项目的 `permissions.allow`，保存在上述用户状态文件，不改写含凭据的配置。只在可信项目、没有强制确认原因时展示，解释器通配、Shell 复合命令和敏感命令不提供此选项。撤销时在 Agent 工具之外删除对应项目规则或修改 trusted；工具写入口保护该文件
- `/grant` 仅保留下一条任务的写入与网络例外：write [glob...]、net <域名>...、all（项目写入）、status、off。`/grant commands` 已删除；已确认计划的内部命令授权仍由原 TaskGrant 作用域承载，不能越过项目信任与硬策略
- 授权 glob 与委派写白名单共用 `WriteGlobSet`：项目相对路径、拒绝绝对路径与 `..`、`dir/**` 语义为「该目录及其所有后代」（Java glob 的 `**` 本身不跨多级目录，这里显式补齐）。改动这套语义会同时影响 `/grant` 与 `delegate_task` 的 `allowed_write_paths`
- 参数级资源槽的唯一声明点是 `policy.ToolResourceSlot`：`write_file` / `edit_file` → `path`、`delete_files` → `paths`、`create_project` → `name`、`execute_command` → `command`、`web_fetch` → `url`（取 host）。`ApprovalGate` 的判定分支与规则匹配共用这一份映射，避免出现两份会漂移的工具清单；`revert_turn`（批量回写整个工作区）和全部 MCP 工具一律回到人工审批，不做猜测
- `web_fetch` 是需要出口授权的只读工具：`requiresApproval=true` + `audited=true`，命中 `/grant net <域名>` 才自动放行（域名相等或其子域，不支持通配符）；未授权域名逐次确认，`dontAsk` 模式下收口为拒绝；两种情形都仍受 `NetworkPolicy` 的 scheme 白名单与 SSRF 拦截约束。`web_search` 不在该维度内：它的目的地由 provider 配置决定，工具参数里没有可信资源槽。需要确认的只读工具在审批框里标 `🟡 需确认`，不再显示「🟢 安全」
- 命中授权的自动放行必须可见且可归因：经 `HitlHandler.onTaskGrantAllow` 输出授权理由，不静默通过
- 带 destructive/openWorld annotations 的 MCP 工具必须逐次 HITL 审批，不复用 tool/server 级全部放行缓存
- 「全部放行」只在本任务内有效：`Main` 在每个用户 turn 开始前调用 `hitlHandler.clearApprovedAll()`，同一轮内的工具调用复用放行，跨用户消息必须重新确认；`/clear` 与切换权限模式继续各自清空
- 能力范围只能单调收窄：`ToolAccessScope.narrow` 取两个范围允许集合的交集（READ_ONLY ⊂ ISOLATED_PROJECT ⊂ FULL），嵌套的 `runWithToolAccess` 不得放宽外层约束；fork 在创建时把父线程生效范围记为 `scopeCeiling`，子注册表永远不能比创建者更宽，隔离内层声明 `ISOLATED_PROJECT` 也突破不了外层的 `READ_ONLY`
- 轮次级状态必须能被编排继承：能力范围与任务级授权使用 `InheritableThreadLocal`，`OrchestrationWaveExecutor` 的波次线程池在轮次内新建，因此继承本轮约束；长生命周期线程池都在轮次之外创建，不会残留某一轮的约束。`allowedToolNames` / `allowedWriteGlobs` / `delegationHandler` / `activeToolSnapshot` 保持普通 `ThreadLocal`，由并行批次显式捕获重放
- `/mode plan` 的只读能力上限作用于整轮：只注入 READ_ONLY / LOCAL_CONTEXT 工具，`write_file` / `execute_command` / 外部副作用工具既不暴露也不执行。与编排组合时直接拒绝该轮（编排必须写入隔离工作区，跑到中途只会收获一片 `CAPABILITY_DENIED`），并消费掉只读标记。模式在会话内持续生效，`/mode default` 恢复
- `/grant` 同样作用于整轮（含编排）：例外随轮次继承，只消除审批提示，不改变能力范围与策略边界。跨会话的持久授权不再由状态栏承载，改用 `permissions.allow` 规则；状态栏改为显示规则层摘要（`docs/adr/0006`）
- PathGuard 强制路径限定在项目根内
- 受保护路径由 `SensitivePathPolicy` 在**策略层**拦截，与 HITL 开关无关、也不可被审批放行：`.git` / `.ssh` 整棵目录、`.env` 与 `.env.*`（`allowTemplates=true` 时放行 `.env.example` / `.env.sample` / `.env.template` / `.env.dist`）、`credentials.json` / `service-account.json` / `.netrc` / `.npmrc` / `.pypirc`、`id_rsa*` / `id_ed25519*` 与 `.pem` / `.key` / `.p12` / `.pfx` / `.jks` / `.keystore` / `.ppk`。`write_file` / `edit_file` / `create_project` 走 `resolveSafeWritePath`；文件名单与隔离工作区物化共用一份实现（物化传 `allowTemplates=false`，保持既有过滤行为）。`target` / `node_modules` 仍是物化排除项，不是安全边界
- Agent 配置写保护覆盖 `.devcli/agents/`、`hooks.json`、`mcp.json`、`config.json` 与显式配置路径，复用共享路径和 PatchSet 提交检查；角色提示词不能自行修改能力定义。关闭审批、任务授权和全部批准不放宽保护。主机命令与外部 MCP 不属于完整文件沙箱。
- `CommandGuard` 只补两条与受保护路径呼应的辅助规则（删除 `.git` 目录、重定向写入凭据或私钥文件），因为 shell 能绕过文件层围栏；它仍不是主防线
- CommandGuard 是辅助黑名单，不是主防线
- `execute_command` 的模型调用与内部硬检查共用工具管线，移除后端的 reviewHostCommand 分支。参数和语义校验在审批前；后端硬策略不因信任而放宽。可信项目的窄 allow 可在 default / auto / dontAsk 下免确认，auto 可分类未决主机命令；这会执行项目构建插件，并非沙箱。timeout 为 1–86400 秒，审批、排队和执行均计入期限，取消传递到进程树。

### Plan 审阅交互

- `Enter` 执行 / `Ctrl+O` 展开 / `ESC` 取消 / `I` 补充重规划
- 方向键不应被误判为 ESC
- 涉及改动要连 raw mode 和回退路径一起看

### 并行工具

- 三条路径都走 `executeTools()`，不手写 for-loop
- 默认最多 10 个并发，结果保持原始顺序；并发上限可通过 `devcli.tool.batch.max.parallel` / `DEVCLI_TOOL_BATCH_MAX_PARALLEL` 调整为 1～64，非法值在加载时拒绝
- 并发宽度只作用于只读工具：副作用工具仍受工作区级串行锁约束，调高该值不会让同一工作区的写入并行
- 参数非法时不进入真实执行，返回可读校验错误给 LLM 纠偏

### Web + Browser

- 已知 URL 先 `web_fetch`，SPA/防爬墙 fallback 到 Chrome DevTools MCP
- `web_search` 与 `web_fetch` 共用同一 `NetworkPolicy` 实例，因此共享同一份 60 秒 / 30 次出口预算；`web_search` 在建连之前判定限流，参数非法不消耗预算
- `web_fetch` 的域名授权取 `URI.getHost()`：真实主机是 userinfo（`@`）之后的部分，大小写与末尾点归一化后匹配，端口不参与判定；`https://github.com@evil.test/` 与 `https://github.com.evil.test/` 都不算命中 `github.com`
- 浏览器读取优先 `take_snapshot`，不默认 `take_screenshot`
- 公开页面不要提前切 shared 模式

### Skill

- Skill frontmatter 支持 `allowedTools`、`context: inline|fork` 和 `paths`；`paths` 会按当前输入或任务文本中的项目相对路径条件激活，`context: fork` 会在加载结果和恢复段标记，提示优先放入 fork / 子任务上下文
- Skill 索引追加到当轮消息，上限 20 个 / 4KB；按使用频率优先、名称次序排序。目录顺序为 builtin、共享用户目录（`~/.agents/skills`、Codex skills）、`~/.devcli/skills`、项目目录，同名后者覆盖前者；项目目录仍须显式信任。
- `load_skill` → SkillContextBuffer → 下一轮 user message 前置注入，并记录本进程内使用频率
- `allowedTools` 为空表示不启用 Skill 工具限制；已加载 Skill 声明 `allowedTools` 时，后续工具调用必须命中当前 SkillContextBuffer 的白名单，`/clear` 清空该状态；压缩后恢复会保留已调用 Skill 的 context、allowedTools 和内容摘要

### 工具治理补充（2026-09-20）

- `TaskGrant` 继续表达动作与资源清单；`ToolRegistry` 给每次授权创建唯一、绑定项目的运行作用域。并行工具和工作区 fork 共享同一可撤销作用域，父作用域正常或异常退出后继承授权失效；切换项目不能带走原授权。它控制免审批范围，不取代能力或策略检查，也不回滚已经执行的操作。
- 写租约同时使用空闲超时（默认 30000ms）和绝对期限（默认 600000ms），配置分别为 `devcli.team.lease.timeout.ms` / `DEVCLI_TEAM_LEASE_TIMEOUT_MS` 与 `devcli.team.lease.max.lifetime.ms` / `DEVCLI_TEAM_LEASE_MAX_LIFETIME_MS`。单调时钟计时，续租不移动首次获取时间；绝对期限内持续写入可以续租，到期必须重新竞争，不保证等待者公平性。后台清理按旧条目条件删除，避免删掉并发续租结果。
- 主机模式新增 `HOST_RESTRICTED`，保留 `HOST_WARN` 兼容名称；二者使用同一命令白名单，**不提供操作系统隔离**。Maven 项目插件、构建脚本及 javac 注解处理器仍可执行任意主机代码，仅用于可信项目。Docker 仍默认且失败关闭，不自动回退。
- 工具审计新增可选 `identity`：执行轮次 `taskId`、租约 `stepId`、授权作用域 `grantId`、`invocationId`、逐次审批 `approvalId` 与有界授权摘要。成功事件区分 `task_grant`、`hitl`、`hitl_reused`；能力拒绝也记录调用身份。未提供的身份保持空值，旧日志仍可读取；尚未贯通用户请求、Agent 身份与最终文件 generation，不宣称完整副作用因果链。

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

### 7. 重构决策与「最小改动」的边界

最小改动指的是**不扩大问题范围**，不是「diff 越小越好」。出现以下情况时，必要的结构调整与功能实现具有同等优先级，属于本次任务范围，不得用局部补丁绕过：

- 新行为放进现有位置会违反包边界、依赖方向或明确的职责所有权
- 同一业务规则、状态或转换需要在多处重复实现，形成重复事实源
- 目标模块已混合多个独立职责，本次修改还要增加新的状态、协议或副作用
- 直接方案必须引入特殊分支、临时开关、兼容补丁或万能 Options，而结构调整能消除它们
- 核心选择、校验或状态转换因 I/O、全局状态或大组件耦合而无法稳定测试
- 缺陷根因来自不清晰的状态所有权、资源生命周期或并发控制，只修表面分支会保留同类故障

以下**不**构成重构理由：个人审美与命名偏好；局部、直接、职责正确且容易测试的修改；只为假设中的未来需求预留扩展点；与本次任务无关且不阻碍正确实现的既有问题（可以在交付中指出，但不扩大本次范围）。

重构与行为变化尽量拆成可分别验证的阶段：先建立测试或明确基线，再重构，再实现功能。跨包、公共合同或数据迁移边界的重构，实施前先说明理由、替代方案与验证计划。

### 7.1 保持代码可读性，不过度抽象

只有一个实现且没有真实变化点时，不要为形式新增接口或设计模式。

### 8. 提交前必须过回归门禁

提交前必须跑 `mvn test -Pquick` 并全绿。**定向测试全绿不能代替回归门禁**：定向用例通常只覆盖改动直接触及的路径，而回归失败往往落在「改动波及但定向用例没覆盖」的地方——委派工具、结果缓存、副作用通道、预算熔断这类横切机制最容易漏。

判断某次失败是不是自己引入的，用临时 worktree 回到基线实测，不要凭印象：

```bash
git worktree add --detach /tmp/baseline <基线提交>
# 在 worktree 里跑同一批测试类，对比失败集合
git worktree remove --force /tmp/baseline && git worktree prune
```

失败分两类，处理方式不同：**测试陈旧**（新契约已落地、旧测试没跟上）改测试；**产品回归**（行为被误改）改产品。先看 `git show <提交> -- <文件>` 的改动，再看新写的测试是否已经适应新契约——新测试改了、旧测试没改，通常就是旧测试陈旧。

Windows 下 Maven 输出是 GBK，`grep` 会报 `Binary file (standard input) matches`；需要 `iconv -f GBK -t UTF-8 <日志> | grep ...` 才能读到 javac 的具体错误行。

门禁层级与「改动类型 → 最低门禁」映射见 [`docs/quality-gates.md`](docs/quality-gates.md)。注意 `-Pquick` 排除了 5 个依赖外部进程或网络的测试类，quick 全绿不等于全量全绿。

### 9. 架构决策写 ADR

新增依赖、公共 API、协议、持久化格式、包边界、安全模型，或引入新的架构模式、改变现有架构行为时，在 [`docs/adr/`](docs/adr/README.md) 按现有格式新增一篇。编号取当前最大 +1，**一经分配即冻结，不重排、不复用、不回填**。局部实现细节与单纯缺陷修复不写。

ADR 记录的是**当时的取舍**，不是架构合同。判断当前行为以源码、公共类型、测试和机械检查为准；发现记录与实现不一致时先核实变更历史，确认过时后在同一次变更里修正它。

包依赖方向由 `src/test/java/com/devcli/PackageBoundaryTest.java` 机械验证。**不得通过删除检查、放宽规则或添加忽略项来掩盖违规**，除非明确批准规则变更。

## 验证路径

| 场景 | 命令 |
|------|------|
| 命令解析 | `mvn test -Dtest=CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest` |
| DAG/Plan | `mvn test -Dtest=ExecutionPlanTest` |
| Multi-Agent | `mvn test -Dtest=AgentRoleTest,AgentMessageTest,AgentOrchestratorTest` |
| 终端交互 | `mvn test -Pphase16-smoke` |
| RAG | `mvn test -Dtest=CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest` |
| 常规回归 | `mvn test -Pquick` |

上表是命令查找表；**哪些是必须过的门禁、什么时候过**见 [`docs/quality-gates.md`](docs/quality-gates.md)。

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
| 扩展/Hook | ExtensionRegistry.java + HookDispatcher.java |
| Trace/观测 | TraceRecorder.java + RunEventTraceSink.java |
| 终端渲染 | render/Renderer.java + RendererFactory.java |

## 当前已知边界

以下在路线图但未交付：容器/VM 级完整系统沙箱、MCP sampling + server 自动重启；当前隔离命令已使用受限 Docker，但 Docker daemon 仍属于主机高权限基础设施。MCP OAuth 暂不纳入个人使用优先级

不要把 `ROADMAP.md` 中"将来要做"误读成"现在已有"。

## 持续维护约定

`-p` 通过 PrintCommandLauncher 复用 AgentSessionRuntime，加载已有 provider、权限配置和 RuleContext；stdout 只输出一个 UTF-8 JSON，诊断写 stderr，不读取 stdin。需要临时审批的动作拒绝；权限配置中的显式允许仍生效。退出码为完成 0、失败 1、参数错误 2、取消 130，运行完成不代表用户任务已经验收。该入口仅装配内置工具，不启动交互 CLI 的浏览器、MCP 和后台任务服务。

`model.usage` trace 记录 input_tokens、cached_input_tokens 和 cache_hit_ratio（缓存读取 Token / 全部输入 Token，输入为零时为 0）；`-p` 的 usage 返回整轮汇总。该指标用于观察实际缓存命中，不替代前缀改造的 A/B 收益验证。

公开配对实验包含 `PairedContextDriver`、`MemoryEvidenceDriver` 和 `MemoryReaderDriver`，但它们不是完整 Agent 多轮评测。`MemoryEvidenceDriver` 使用主题 Markdown 隔离目录；默认记忆召回只注入索引，主题通过 `read_memory` 按需读取。现有组件实验不能用于宣称完整长期记忆效果。

AgentDojo 接入状态更新（2026-08-31）：MCP 桥接与生产 `AgentSessionRuntime → ToolExecutionPipeline → 官方 evaluator` 已接通。`benchmarks/agentdojo/run-paired.ps1` 固定四题，启动前写入数据来源、模型、预算、顺序及哈希清单，批次与条件目录禁止复用。驱动必须保留 `search_tools` 和 `read_tool_result`；预激活仅做初筛，不能截断后续发现。显式短词/中文工具查询保留原意，整句预激活另行过滤噪声。`auto-approve` 仅诊断效用和审批开销，`terminal` 复用生产人工审批；baseline 只旁路 HITL，不得声称比较了完整治理开关。本批次不覆盖沙箱或 PatchSet，安全收益仍未完成；不同版本或旧诊断结果不合并。

MCP schema 清理保留 `anyOf` / `oneOf` 约束，不能把可空标量改为 object；2026-08-31 的 v7 日历公开题配对已验证合法 null 经真实工具链成功执行。受影响题目可通过 `-CaseId` 单独建立新批次，不与旧版本混算。

形成稳定协作规则时直接补进本文件，不要只留在聊天记录里。详细实现细节补到 `docs/agents-reference.md`。

效果报告统一给百分比并附分母：AgentDojo 主指标为官方 ASR、Utility 与 ASR 下降百分点，自动批准不可宣称安全收益。上下文新实验使用 `original-task-qa`：优先 Luna（不可用时记录实际回退模型）、SWE Java 原始 Issue、同一 Session、64000 阈值、compact-only、默认 20 轮，以真实达到阈值为准；禁止拼接源码包替代真实对话。`conversation.jsonl` 保存真实问答；官方质量与实际经历模型压缩的子集分开统计，未触发时不提供压缩保真成绩。

微压缩替换前比较原结果与恢复引用的 Token，只有净减少时才替换和落盘；短确认结果保持原文。公开测试使用冻结源码快照，运行中发现并修复的问题不追溯修改旧批次成绩。
