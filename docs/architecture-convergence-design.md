# DevCLI 架构收敛设计方案

> 状态：分阶段实施中 | 作者：架构评审 | 日期：2026-08-27
> 范围：在不削减对外能力的前提下，收敛 21 期迭代累积的实现复杂度，明确差异化卖点。
> 参考基准：Claude Code（能力清单参考，实现不照抄）、Codex 架构评审意见。
> 实施校正：本文“现状”保留评审时快照，不代表当前代码。当前 `AgentOrchestrator` 已拆至 943 行；Side-Git 自研对象 GC 已删除，裁剪后仅调用 JGit `autoGC`。

---

## 1. 背景与问题

现状（代码实测）：

- 主代码 604 文件 / 93,397 行，测试 259 文件 / 37,367 行；315 commits。
- 上帝类：`AgentOrchestrator` 2350 行 / 109 字段；`Main` 2333 行 / 105 方法。
- 概念密度过高：memory 包 40 个类（4 个 Policy、3 个 ConflictDetector、6 个 Curator 流水线类）；workspace 14 个类含三套后端 + AST 自动合并；runtime 27 个类含 994 行 `RuntimeThreadStore`。
- 78 个 `DEVCLI_*` 环境变量；checkpoint 协议演进到 v8；Side-Git 在 git 之上自研对象 GC。

核心判断：**工程实现质量在线，问题集中在"为低频/假想场景预付的实现复杂度"，以及部分机制长在了错误的抽象层**。本次收敛不是删功能，是换实现、归概念、定差异。

## 2. 目标架构（一句话）

**一个统一 Execution Runtime，上层 ReAct / 人审 Plan / Multi-Agent DAG 三种执行模式共享同一执行内核；底层共享结构化记忆、代码检索、增量上下文、worktree 隔离、证据链、回滚、并行调度、取消树与执行追踪。**

```
┌────────────────────────────────────────────────────────────┐
│  用户入口: Inline TUI / headless                            │
├────────────────────────────────────────────────────────────┤
│  模式层: ReAct          Plan(人审)       Multi-Agent DAG     │
│         Agent.java     PlanExecuteAgent  AgentOrchestrator  │
├────────────────────────────────────────────────────────────┤
│  统一执行内核: AgentExecutionEngine                          │
│   控制流 / LLM调用 / 工具协议 / 预算 / 取消 / 生命周期 Hook   │
├──────────────┬──────────────┬───────────────┬───────────────┤
│ Cancellation │ RunEvent     │ ToolExecution │ WorkingContext│
│ Tree         │ Trace 总线    │ Pipeline(9段) │ (增量压缩)    │
├──────────────┴──────────────┴───────────────┴───────────────┤
│  能力层(共享, 薄接口+最简实现):                               │
│  MemorySource  CodeRetriever  Workspace  Snapshot  Reviewer │
│  MCPRegistry   LSPDiagnostics Sandbox    Policy/HITL        │
├────────────────────────────────────────────────────────────┤
│  存储: MD 文件(事实源) + SQLite(仅索引) + git(状态/回滚)      │
└────────────────────────────────────────────────────────────┘
```

四条差异化主线（面试/产品叙事统一口径）：

1. **Multi-Agent DAG**：Planner 拆 DAG + writeSet 冲突分波。
2. **Evidence-based Reviewer**：只认真实工具证据与硬检查，不认 LLM 浮点分。
3. **Commit-on-Success 隔离**：worktree 执行、验证通过才合并，失败即丢弃。
4. **Readable Source + Indexed Retrieval**：记忆与代码检索统一哲学——人可读文件为事实源，ID 仅为稳定引用，hash 判鲜。

---

## 3. 设计原则

1. **文件即事实源**：记忆、规则、快照元数据优先落人可读文件；DB 只存可重建的索引。
2. **机制薄、协议强**：角色职责、验收点、证据格式做强；并发/恢复/存储做薄。
3. **失败即丢弃，不做乐观修补**：隔离产物验证失败直接弃用，不做基于旧上下文的自动重生成。
4. **硬信号才配做门禁**：退出码、LSP 诊断、真实工具调用可判定；LLM 意见只出裁决与问题清单。
5. **配置项必须答得出"谁在什么场景改"**，否则降为命名常量。
6. **每个抽象留薄接口**：单人实现最简版，多人化换实现不换调用方。

---

## 4. 逐模块设计

### 4.1 执行内核与三种执行模式

**现状**：ReAct / Plan / Team 三路径已由 `AgentExecutionEngine`（512 行）统一承载控制流，但 Plan 与 Team 各有 BatchExecutor，双轨差异散落（"仍只属于 Team"的特例十余处）。

**目标**：

- 三模式显式化，共用同一内核：
  - **ReAct**：单 Agent 思考-工具循环，适合局部修改/查 bug/解释。
  - **Plan（人审）**：分析→计划→`Enter 执行 / Ctrl+O 展开 / ESC 取消 / I 补充`→执行；用户掌握终决权。
  - **Multi-Agent DAG**：Planner 拆 DAG 与验收点，Worker 波次并行，Reviewer 独立验收后 Final integration。
- 模式选择：默认 ReAct；`/plan` 进人审；`/team`（或 Planner 判断任务跨 ≥3 个独立写集时建议）进 DAG。**不做按任务内容的自动静默切换**。
- 统一 `PlanTaskBatchExecutor` 与 `MultiAgentBatchExecutor` 的波次执行到 `OrchestrationWaveExecutor` 单一实现，差异仅通过"是否有 Worker 池/Reviewer/角色记忆"的配置开关表达，删除双轨特例。

**代码处置**：

- 改：`AgentExecutionEngine` 保持唯一控制流出口；两个 BatchExecutor 合并为一个 `WaveBatchExecutor`。
- 改：`AgentOrchestrator` 从 2350 行/109 字段拆分——编排状态归 `OrchestrationState`、评审交互归 `ReviewCoordinator`、checkpoint 归 4.6 节新类；目标 <800 行 / <40 字段。

**最终效果**：三模式行为一致（取消、预算、流式、错误出口完全相同）；新增一种模式只需实现模式策略，不改内核；面试可画一张图讲完全部执行路径。

---

### 4.2 DAG 与并行调度

**现状**：`ExecutionGraph` 做依赖就绪与环校验；`OrchestrationWaveExecutor` 做有界并发；但文件冲突靠运行时租约拒绝 + 事后 STALE 检测， Planner 不输出写集。

**目标**：调度 = DAG 依赖图 **＋ 文件冲突图**，两层都在执行前算清。

- Planner 每个步骤强制声明 `readSet / writeSet`（与 `acceptance_criteria` 同级，缺失即协议错误，进入有界修复）。
- 构图后静态分析：无依赖边但 writeSet 相交的步骤自动补依赖边（串行化），并在计划展示中告知用户"因共享文件 X，步骤 C 已调整为 A 之后"。
- 波次执行：同一波次内 writeSet 两两不相交；只读步骤可任意并行。
- 运行时文件租约保留，仅作为 LLM 漏报 writeSet 时的兜底安全网，正常路径不再触发。
- `execute_command` 等无法静态分析写集的步骤：标记 `projectExclusive`，不参与并行（简单正确）。

**代码处置**：

- 新建 `WriteSetConflictGraph`：输入 ExecutionGraph + 每步 writeSet，输出补边后的图与波次。
- 改 `Planner` 提示词协议；改 `ExecutionGraph` 承载补边来源（ORIGIN_DEPENDENCY / CONFLICT_SERIALIZED）。
- 删：`ContextVersionLedger` 中 Java 符号指纹与 STALE_CONTEXT 自动刷新重生成链路（517 行 → 0，理由见 4.3）。

**最终效果**：并行冲突在计划阶段可见、可解释；运行时不再出现"跑完才发现冲突重跑 LLM"；共享文件步骤 100% 串行、独立步骤 100% 并行，无需用户理解机制。

---

### 4.3 隔离工作区与 Commit-on-Success

**现状**：三套后端（GitWorktree / FileSystemCoW reflink-ReFS / Copy）+ PatchSet 流式哈希 + JavaAstPatchMerger 三方合并 + 写前日志；合并时检测 STALE_CONTEXT 并要求 Worker 基于新内容整体重新生成。

**目标（本方案最关键的换层）**：隔离的目的不是乐观并行，而是**"验证通过才允许影响主项目"**。

```
Main Workspace (branch: main)
 ├─ worker/<taskId-A>  worktree   Worker A 执行 → commit → Reviewer
 ├─ worker/<taskId-B>  worktree   Worker B 执行 → commit → Reviewer
Reviewer PASS + 硬检查通过
      → git merge --no-ff worker/<taskId>   (冲突 → MergeConflict 打回/报告人工)
Reviewer FAIL / 取消 / 崩溃
      → 删除 worktree，主项目零污染
```

- Worker 在自己 worktree 内直接 commit（任务内可多次 commit），产物是分支不是 PatchSet。
- 合并用 git 原生三方合并；**冲突即失败结果**，交回 Planner 重规划或人工，不做 AST 自动合并、不做 context.refresh 重生成。
- 非 git 项目：Copy 后端 + 目录 diff 生成 patch，合并走文件级应用（冲突同样拒绝）。

**代码处置**：

- 保留：`GitWorktreeBackend`、`CopyWorkspaceBackend`、`IsolatedWorkspace`、`WorkspaceBackend` 接口。
- 新建 `WorkspaceMerger`：封装 merge / 冲突判定 / worktree 注销。
- 删：`PatchSet`、`JavaAstPatchMerger`、`FileSystemCowWorkspaceBackend`（reflink/ReFS 块克隆调优）、`WorkspaceCleanupPolicy`（201 行内联为 30 行 LRU）、`WriteGateResult`、`ProjectCommitCoordinator`（并入 Merger）。
- workspace 包 14 类 → 7 类。

**最终效果**：回滚 = 删分支；合并语义 = git 语义（开发者都懂）；消除"三重指纹+写前日志+自动重生成"整套派生复杂度；worktree 在 `git worktree list` 真实可见，成为可演示资产。

---

### 4.4 回滚：代码状态回滚，不做聊天状态回滚

**现状**：Side-Git 自研 side-history + 松散对象 GC（Snapshot 包 8 类 / ~900 行）；与 git 自身能力重叠。

**目标**：区分三个层级，各用最合适的工具。

| 层级 | 触发 | 实现 |
|---|---|---|
| 任务级 | 任务开始前 | git 项目 `git stash create -u` 得不可达 commit SHA（不动 HEAD/工作区）；非 git 项目 content-addressed 硬链接快照 |
| 步骤级 | Worker 失败 | 丢弃该 worker worktree（4.3），主项目无感 |
| 合并级 | 合并后发现问题 | `git revert -m 1 <merge commit>`；非 git 反向应用 patch |

- 快照保留最近 N 个（默认 20），启动时 LRU 删除——**删文件即可，无自研 GC**。
- 用户命令：`/snapshot list`、`/snapshot restore <id>`。

**代码处置**：

- 重写 `SnapshotService`（98 行框架 → ~200 行完整实现）。
- 删：`SideGitManager`(441)、`SideGitObjectGc`(131)、`SnapshotGcPolicy`、`SnapshotConfig`、`TurnSnapshot`。
- snapshot 包 8 类 → 2 类。

**最终效果**：任何时刻可回到任务前状态；零自研对象管理；"Agent 改坏代码"从事故变成一次命令。

---

### 4.5 崩溃恢复：轻 checkpoint，砍掉 step 级 resume

**现状**：`AgentCheckpoint` 1024 行、协议 v8、AttemptDigest、写前日志、跨版本迁移，追求崩溃后续跑到具体步骤。

**目标**：个人 CLI 的真实需求是"别留烂摊子"，不是"无缝续跑"。

- `TaskCheckpoint`（新，替代 AgentCheckpoint）只存：goal、DAG 拓扑与每步状态、已完成 step id、baseCommit、worker worktree 列表、时间戳。**单个 JSON，无协议版本号**（字段缺失走默认值，天然向前兼容）。
- 崩溃后重启检测到未完成任务：
  - `[r] 回滚重来`：删 worktree、主项目 reset 到 baseCommit、重新执行；
  - `[k] 保留现场`：worktree 保留，退出由用户手动处理。
- 不做 step 级续跑、不做协议迁移、不做 WAL。

**代码处置**：删 `AgentCheckpoint` 及 v8 迁移逻辑，新建 `TaskCheckpoint`(~200 行) + `RecoveryPrompt`。

**最终效果**：崩溃后用户 5 秒内做出选择，工作区永远干净；checkpoint 相关代码从 1000+ 行降到 200 行，且不再需要第 9 次协议改版。

---

### 4.6 Reviewer：证据门禁

**第一阶段实施前现状**：三层浮点评分（任一 <0.6 或 functional<1.0 驳回）；Pre-Review 硬检查（test-compile/ javac，强制 Docker）；TOOL 证据要求本轮真实成功调用。

**目标**：

- **门禁只认硬信号**（全部确定性）：
  1. Pre-Review：编译/测试退出码 0；
  2. 每条 `verification_method=TOOL` 验收点，其 verifier 在本轮真实成功调用（工具调用记录可查）；
  3. LSP 无 error 级诊断（接入 4.10）；
  4. LLM 裁决 ∈ `{approve, changes_requested}` 二值 + issues 列表（文件/行/问题/建议），changes_requested 即打回。
- 删除三个浮点分与 0.6/1.0 阈值；Reviewer 最多 2 轮固定，不可配。
- 独立性靠**上下文隔离**（无工具、只给 diff + 验收点），独立模型仅一个 env 可选；默认同模型可接受，但补"同模型 vs 异模型"消融实验写进评测报告。
- Final integration 只做胶水；普通步骤失败 50% 熔断保留。

**代码处置**：改 `TeamReviewerProtocol`；删评分阈值逻辑；`PreReviewVerifier` 保留并对接 4.9 分级沙箱。

**最终效果**："通过"有可审计的证据链；不存在 0.55/0.7 摇摆的 flaky 门禁；面试可现场展示某 Worker 因编译失败被自动打回的完整 trace。

---

### 4.7 记忆系统：MD 为源，ID 为引

**现状**：memory 包 40 类；SQLite 为事实源，正文入库；schema 版本/TTL/recallCount/scope 排序；Curator 流水线默认关闭。

**目标（Readable Source + Indexed Retrieval）**：

```
~/.devcli/memory/
├── user.md            # 人可读、可手改、可 git 管理
├── project.md         # frontmatter: id/scope/tags/updated/hash
├── decisions.md
├── conventions.md
└── inbox.md           # 自动沉淀待审
```

- **MD 文件是唯一事实源**；SQLite 只存索引：`MEM-id → 文件 + ## section + hash + embedding + 元数据`，不存正文；索引删空可从目录全量重建。
- 召回两层：启动注入当前 scope 高分少量（≤200 行等价预算）；其余按需检索，命中 MEM-id 后**实时读 MD 对应 section 注入**。
- 自动记忆默认开启：任务后主模型（复用主 client，空系统提示做抽取）把候选 append 到 `inbox.md`；下次启动 `[a]收编 [d]丢弃 [e]编辑`，同步交互，无队列/票据/重放。
- 手改文件 → hash 变 → 增量重建对应索引。

**代码处置**：

- 新建：`MarkdownMemorySource`、`MemoryChunker`（按 section 切）、`MemoryIndex`（合并现 `MemoryVectorStore` + `SqliteLongTermMemoryStore`）。
- 保留瘦化：`SessionMemory`（任务内工作记忆，与长期记忆正交）、`MemoryRetriever`(274→~120)、`RuleContext`（DEVCLI.md 规则）；`TaskLedger` 并入 `SessionMemory.WorkState`。
- 删（25+ 类）：4 个 Policy、3 个 ConflictDetector、Curator 群（IsolatedMemoryCurator/MemoryCurator/MemoryPromotionPipeline/MemoryPromotionQueue/MemoryConfirmationStore/MemoryWriteProtocol）、MemoryOrganizer/FactDeduper/StructuredClaim/IntentClassifier/SubjectExtractor/QueryTokenizer 等。
- memory 包 40 类 → 约 12 类。

**最终效果**：用户随时打开目录就知道 AI 记了什么，可直接改删；自动记忆默认工作；无 schema 迁移；记忆机制同时成为"用户数据主权"的差异点。

---

### 4.8 上下文压缩：结构化 Working Context，增量演进

**现状**：microcompact + Map-Reduce 首摘 + 六段 RollingSummary + 预摘要缓存 + SemanticGuard + Summary GC，`ConversationHistoryCompactor` 1226 行。

**目标**：压缩产物从"叙事摘要"改为固定七字段的 **Working Context**：

```
Current Goal | Current Plan | Completed | Failed Attempts(含排除方案)
Key Evidence | Changed Files | Unresolved Issues
```

- step 完成/失败只发事件**增量改字段**（delta），不重新摘要全历史；仅在 delta 累积超限时做一次规整。
- `TaskLedger` 的进度投影与 Working Context 合并为同一结构，消除"摘要 vs 工作状态"两份事实。
- 压缩后恢复消息、compact_boundary（Skill/MCP/RAG epoch 快照）保留——这是长任务正确性的关键。
- microcompact（大消息截断、tool_result 落盘）保留为第 0 层。

**代码处置**：`RollingSummary` → `WorkingContext`；SummaryGarbageCollector/LifecycleReducer/CompactionSummaryCache/CompactionSemanticGuard 四个辅助类并入 Compactor；Compactor 目标 <800 行。

**最终效果**：长任务/长 plan 压缩后不丢进度与失败教训；每轮压缩成本下降（增量 vs 全量）；多 Agent 按角色裁剪同一结构，注入一致性可测。

---

### 4.9 代码检索：内容驱动检索，ID 稳定引用

**现状**：keyword+semantic+bounded graph → RRF → CrossEncoder rerank；chunk 存正文副本，配套 CURRENT/STALE/DIRTY 标记与回读校验、negativeFact 失效。

**目标**：

- 检索是**内容驱动**（Query → Symbol/Text/Semantic → RRF → Rerank → 候选），ID 不是检索入口。
- chunk **不存正文**，只存稳定坐标：

```
CODE-182: repo + commit + file + symbol + lineRange + contentHash + embedding
```

- 返回候选时带 3 行预览；正文一律 `read_file` 实时读取——永远新鲜，无需 STALE 回读校验。
- hash 与当前文件不符 → 直接标记 dirty 并重嵌入，不做"凑合注入"。
- 两层职责分清：向量 chunk 管模糊召回，JavaParser 符号图管精确定位（定义/引用/调用链）。
- CODE-id 同时供 Reviewer 引用证据："根据 CODE-182（@8f91ac）"，代码演进后可判证据是否 stale。

**代码处置**：改 `CodeChunker/VectorStore/CodeRetriever` 存储模型；删 STALE/DIRTY 回读分支与 negativeFact 清理链路的一半（保留"符号已删除"提示）；`grep_code` 保留为实时精确检索。

**最终效果**：检索结果永远与当前代码一致；证据可追溯到 commit/行号；省掉正文副本与失鲜补偿机制。

---

### 4.10 LSP 诊断

**现状**：Phase 17 已集成 LSP（`lsp/` 包）。

**目标**：保留并提升为一等硬信号——诊断结果同时流向 (a) TUI 编辑后提示，(b) Reviewer 门禁（4.6），(c) Execution Trace（4.12）。诊断 error 数为 0 是 Worker 合并前置条件之一。

**最终效果**：不依赖 Docker 也能获得编译级正确性信号（与 4.11 沙箱解耦），Windows 默认可用。

---

### 4.11 沙箱、HITL 与策略：分级而非全有/全无

**第一阶段实施前现状**：隔离任务 execute_command 与 Pre-Review 强制 Docker、不可用即失败、禁回退主机；Windows 用户无 Docker Desktop 时 `/plan` 整条不可用。

**目标（ToolEffect 五级 × 三档执行）**：

| 级别 | 示例 | 默认执行方式 |
|---|---|---|
| READ_ONLY | read_file/grep_code/web_search | 直接执行 |
| LOCAL_CONTEXT | 会话内工具 | 直接执行 |
| PROJECT_MUTATION | write_file（worktree 内） | 隔离区直接执行 |
| HOST_PROCESS（项目内、无网） | mvn test、git status | HITL 批准后主机执行；配置 `sandbox=required` 时走 Docker |
| EXTERNAL_MUTATION/联网/项目外 | rm、curl、发布 | 逐次 HITL + Docker 沙箱；无 Docker 则拒绝并给出安装指引 |

- Docker 从"硬前提"变为"可选强沙箱档"；默认路径在 Windows 裸机可用。
- HITL 拦截顺序不变：HitlToolRegistry → ToolRegistry → PathGuard/CommandGuard；destructive/openWorld MCP 工具逐次审批不缓存。
- MCP readOnly 注解默认不可信的策略保留。

**最终效果**：安全性按操作风险分级而非一刀切；个人用户开箱即用，团队/高风险场景可一键拉到 Docker 强隔离。

**第一阶段实现（2026-08-27）**：新增 `DOCKER | HOST_WARN` 显式模式，默认仍为 `DOCKER` 且不自动回退。`HOST_WARN` 先只覆盖 Java 项目闭环：Maven 自动追加离线参数且只接受 `clean/validate/compile/test-compile/test/package/verify`，另允许 `javac` 和只读 Git；任意 Maven 插件、发布阶段、命令串、管道、重定向、网络工具、写入型 Git 与其他运行时继续拒绝。工具结果和 Pre-Review 均展示风险提示。完整三档执行、通用语言生态白名单和高风险逐次 Docker 档仍未完成。

---

### 4.12 中断：Cancellation Tree

**现状**：`CancellationToken`/`CancellationContext`/`RunContext` 已有取消基础；工具声明 COOPERATIVE/INTERRUPT_ONLY。

**目标**：取消按树传播，一次 Ctrl+C 有序释放全部资源。

```
Run.cancel()
 ├─ Planner cancel
 ├─ Worker-A cancel ├─ 在途 LLM call (流式立即断)
 │                  └─ 在途 tool call
 ├─ Worker-B cancel
 └─ Reviewer cancel
顺序: stop streaming → 释放文件租约/worktree(按策略保留或删)
     → 写 TaskCheckpoint → 终端回到干净 idle
```

- 父 token 持有子 token 集合；所有阻塞点（LLM stream、命令等待、锁等待）必须响应取消。
- 取消不是 kill 线程：协作式，每一层有 finally 清理。

**最终效果**：任何层级 Ctrl+C 后无残留进程、无锁泄漏、无孤儿 worktree、终端立即可用。

---

### 4.13 诊断：统一 Execution Trace

**现状**：`RunEvent` 强类型事件体系完整（TurnStarted/ReasoningDelta/ToolCalls/ToolResults/CheckpointCreated/FailureGuidance…），但落库靠 SQLite RunStore，查看途径分散。

**目标**：一次运行 = 一棵可导出、可查询的 Trace 树。

- 每个节点统一字段：`runId / parentId / agentId / stepId / toolId / start / end / tokenUsage / retryCount / errorType / evidenceIds`。
- 存储：运行期内存 + append-only `~/.devcli/traces/<runId>.jsonl`（无需 SQLite）。
- 新增 `/trace [runId]`：终端渲染树（折叠长输出）；`/trace export <runId>` 导出。
- 故障自查从"翻聊天记录"变为看树：`Task-7 → Worker-2 → mvn test → timeout 120s → retry 2 → cancelled`。

**代码处置**：

- 复用 `RunEvent` 总线，新增 jsonl sink 与 trace 聚合器。
- 删：`RuntimeApiServer`(410 HTTP)、`RuntimeThreadStore`(994)、`KeyedSerialExecutor`(213)、`SqliteRunStore`(446)、`DurableTaskManager`；RunStore 改内存+jsonl。
- runtime 包 27 类 → 约 15 类。

**最终效果**：每次运行自带"飞行记录仪"；卡死/失败/高成本任务一屏定位；trace 文件本身就是面试与 bug report 的硬证据。

---

### 4.14 工具执行管线与 MCP

**现状**：九阶段管线（取消→存在性→能力范围→Skill 权限→参数校验→HITL→审计→策略→尺寸治理）、JSON Schema 校验、tool_call_id 去重配对、MCP 动态发现/重连/进度聚合。

**目标**：**整体保留**——这是项目工程质量最高的部分。仅做：

- 工具结果尺寸治理与 4.9 坐标化对齐（代码类结果带 CODE-id 预览而非长正文）。
- MCP 启动等待/重连参数从 env 收回常量（见 4.16）。
- 13 个内置工具清单不变。

**最终效果**：能力不退化；管线成为"LLM 工具调用可靠性"的讲述素材（错误码分类退避、去重、停滞指纹熔断）。

---

### 4.15 Skill / Hook / Web / 浏览器 / 图片输入

- **Skill**：保留（索引段注入、allowedTools 白名单、context:inline|fork）；与 WorkingContext 恢复段对齐。
- **Hook**：需求真实（Claude Code 同证），但改为薄壳：`~/.devcli/hooks.json` 声明 PreToolUse/PostToolUse/Stop 挂载点 → **执行 shell 命令**，stdin 喂 JSON、exit code 0 放行/2 阻断/其他告警。解禁 shell；删除四层生命周期类与 64 条合并上限的复杂实现（574 行 → ~120）。
- **Web/浏览器**：保留"已知 URL 先 fetch、SPA  fallback CDP、快照优先于截图、网络策略"策略，不动。
- **图片输入**：Phase 21 能力保留，不动。

---

### 4.16 配置收敛

**现状**：78 个 `DEVCLI_*`，多数同时提供 env + `-D` 双通道。

**目标**：分两级。

- **用户级（保留 env，约 15 个）**：API key/base_url/model、renderer、并发度、sandbox 档位、workspace 后端、MCP server 配置、代理。
- **调优级（收回 `Tuning` 命名常量）**：重试次数/退避/jitter、各类 TTL/GC 阈值/轮次上限；需要时 `-D` 临时覆盖，不写文档、不承诺稳定。
- 砍 system property 双通道；新增配置项必须在 PR 说明"谁在什么场景改"。

**最终效果**：`.env.example` 从 14KB 降到 3KB 量级；组合爆炸与文档负担消失。

---

### 4.17 TUI / 交互

**现状**：JLine 4 Inline 渲染、底部 dock、live thinking、高亮/补全/历史，设计成熟。

**目标**：保留全部交互资产，仅对接新结构——dock 状态增加 Trace 摘要；计划审阅界面展示 writeSet 冲突补边；启动提示新增 inbox 待审记忆数；`/help` 命令清单随 `/trace`、`/snapshot`、`/memory` 更新。`Main` 2333 行/105 方法拆分为命令解析（CliCommandParser 为主）+ 各命令 handler，目标 <1000 行。

**最终效果**：用户可感知的新能力全部在现有终端框架内呈现，无交互倒退。

---

### 4.18 评测体系

**现状**：接入 CodeSearchNet Java / SWE-bench Lite / LongMemEval / LongBench / RULER；旧自建 Saga 只在 `docs/benchmark-evaluation.md` 历史归档中保留，不再作为正式结果。

**目标**：

- 公开 benchmark 先完成单样本官方链路验证，再按固定版本、原始任务和配对条件扩展运行；自建 Saga 不再进入正式统计。
- RAG 指标（Recall@5/MRR/nDCG）随 4.9 存储模型变更重跑一次对比。
- SWE-bench 坚持官方 Docker harness，resolved 才算数。

**最终效果**：简历上的每个数字都经得起追问；评测报告成为差异化最硬的一块。

---

## 5. 核心数据模型

```java
// 记忆: 文件为源, 索引可重建
record MemoryIndexEntry(String memId, String file, String section,
                        String scope, String hash, float[] embedding) {}

// 代码证据: 稳定坐标, 不存正文
record CodeEvidence(String codeId, String repo, String commit, String file,
                    String symbol, int[] lineRange, String contentHash) {}

// 轻检查点: 单 JSON, 无协议版本
record TaskCheckpoint(String goal, List<StepState> steps, String baseCommit,
                      List<String> worktrees, long createdAt) {}

// Trace: 事件即节点, jsonl 为事实源
record TraceNode(String runId, String parentId, String kind, String agentId,
                 String stepId, long start, long end, Tokens tokens,
                 int retry, String errorType, List<String> evidenceIds) {}

// 调度: DAG + 冲突图
record StepIO(String stepId, Set<String> readSet, Set<String> writeSet,
              boolean projectExclusive) {}
```

## 6. 分期实施计划

每期独立 PR、测试全绿、行为可验收、简历可写一行。

| 期 | 内容 | 周期 | 验收 |
|---|---|---|---|
| P1 | Execution Trace（jsonl sink + `/trace`）+ Cancellation Tree 补全 | 2d | 导出一次多 Agent 运行树；Ctrl+C 后无锁/进程/worktree 残留 |
| P2 | 记忆 MD 化（MarkdownMemorySource + 索引，删 25 类）；inbox 默认开 | 2d | 手改 md 后索引更新；`/memory` 全流程 |
| P3 | WorkingContext 增量压缩，合并 TaskLedger | 2d | 长任务压缩后进度/失败教训不丢；单测锁定七字段 |
| P4 | 代码检索坐标化（chunk 不存正文、CODE-id、dirty 重嵌入） | 2d | RAG 指标重跑不劣化；改文件后检索结果即时新鲜 |
| P5 | Reviewer 二值化 + LSP 进门禁 + 沙箱分级 | 2d | 无 Docker Windows 可跑 `/plan`；编译失败自动打回 |
| P6 | worktree 分支模型 + WorkspaceMerger，删 PatchSet/AST merger/CoW | 4d | PASS 才 merge、FAIL 即删分支；注入冲突正确打回 |
| P7 | Snapshot 重写 + TaskCheckpoint（砍 resume/side-git/v8） | 2d | 回滚命令可用；崩溃后 r/k 选择流程 |
| P8 | runtime 瘦身（删 HTTP/ThreadStore/DurableTask）、Hook 薄壳、配置收回、Main 拆分 | 2d | `mvn test -Pquick` 全绿；env 清单 ≤15 |
| P9 | 评测补强（5 次重复 + 三项工程指标） | 2d | 报告出区间数据 |

估算总账：删除 6000–8000 行，新增约 2000 行，净瘦 5000+；主代码类数减少约 60；`AgentOrchestrator` 字段 109 → <40。

每期遵循仓库硬规则：改行为同步 AGENTS.md/README；改命令入口联动 Main+Parser+测试；改工具/模型/Memory/MCP 按 AGENTS.md 联动清单执行。

## 7. 最终效果

**代码层面**

- 同一能力只有一套实现：执行一个内核、回滚一种语义（git）、记忆一个事实源（MD）、检索一种新鲜度保证（实时读）。
- 新人（或面试官能 follow 的）理解路径：AGENTS.md 篇幅预计缩减 40%，"谁不是谁"的划界声明大幅消失。

**用户层面**

- Windows 裸机开箱可用（Docker 可选）；
- Agent 改坏任何东西都能一条命令/一次崩溃选择回到干净状态；
- 用户能直接打开 memory 目录审计 AI 的记忆；
- 每次运行有可导出的执行树，故障一屏定位。

**求职/面试资产（每个都有实物）**

1. 三模式统一内核——一张架构图讲完；
2. DAG+writeSet 冲突调度——计划中可见补边解释；
3. Commit-on-Success——`git worktree list`/分支历史现场演示；
4. 证据门禁 Reviewer——编译失败自动打回的 trace；
5. MD 源+ID 索引记忆——目录可打开、索引可重建；
6. Execution Trace——jsonl 导出物；
7. 带区间的 benchmark 报告。

简历描述（定稿一句）：

> 自研 Java Agent CLI 运行时：统一 ReAct/人审 Plan/Multi-Agent DAG 三模式内核；基于 git worktree 的 Commit-on-Success 隔离与证据制 Reviewer；可读文件为源、ID 为引的记忆与代码检索；结构化增量上下文；Cancellation Tree 与全链路 Execution Trace。

## 8. 明确不做（YAGNI）与多人化预留

- **不做**：step 级崩溃续跑、记忆自动晋升队列、Side-Git 自研 GC、AST 自动合并、块克隆工作区、HTTP Runtime 服务端、LLM 浮点评分门禁、78 个配置旋钮。
- **预留薄接口**（单人版最简实现，多人化换实现）：`SnapshotStore` / `LockService` / `RunRepository` / `ApprovalGate` / `WorkspaceBackend`。
- **多人化路径**（不在本次范围）：形态 A（团队共享 git，Worker 产物自动开 PR）优先；形态 B（托管服务）时 RunEvent 换 WebSocket+Postgres、沙箱换 microVM、HITL 异步化——语义层全部复用，机制层替换。设计依据见 `docs/multi-user-session-isolation-design.md`。
