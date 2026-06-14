# Phase 27: Rolling Summary Pruning

> 范围：本文是第 27 期改造设计，用于补齐 `ConversationHistoryCompactor` 的滚动摘要裁剪能力。本文只描述方案，不代表当前代码已完成实现。
>
> 落地状态（更新）：27-A/B/C 已实现——摘要改为结构化、`RollingSummary` 提供 parse/render、`SummaryGarbageCollector` 程序化裁剪。**段结构最终采用 Claude Code `/compact` 九段模板（非本文的六段）**：主要请求与意图 / 关键技术概念 / 文件和代码 / 踩过的坑和修复 / 问题解决过程 / 逐条用户消息 / 待办任务 / 当前在做什么 / 下一步。TaskLedger（27-D）已实现 MVP（见下方 27-D 落地状态：仅 `PlanExecuteAgent` 闭环）。Eval（27-E）未做。

## 1. 目标

当前 `ConversationHistoryCompactor` 已经把压缩点收敛到真实 `conversationHistory`，并通过 Map-Reduce（分片归并摘要）+ incremental summary（增量滚动摘要）避免反复把旧摘要重新套娃压缩。

Phase 27 要解决的是另一个问题：rolling summary（滚动摘要）会持续变大。增量摘要只解决“不要重复压旧内容”，但不解决“旧内容什么时候该被丢弃”。如果每次都要求 LLM 保留已有摘要里的全部事实，长期运行后摘要会变成新的上下文垃圾堆。

成功标准：

- 摘要能保留 active context（活跃上下文）和 open issue（未解决问题）。
- completed milestone（已完成里程碑）可以被压缩成低成本状态，不再保留过程细节。
- superseded fact（已覆盖事实）只保留最终值，不让旧值反复污染模型判断。
- 当前 plan progress（计划进度）不依赖摘要文本承载，而由结构化 TaskLedger（任务账本）承载。
- 多次压缩后 summary token（摘要 token）有上限，不随会话轮数线性增长。

## 2. 当前状态

当前链路：

```text
Agent / PlanExecuteAgent
  -> maybeCompactHistory()
  -> ConversationHistoryCompactor.compactIfNeeded()
  -> history 头部写回 "[已压缩的历史对话摘要]"
  -> 保留尾部最近 messages
```

当前 `ConversationHistoryCompactor` 有三类摘要策略：

- SUMMARY_PROMPT（普通摘要）：短历史直接压缩成 1-3 段中文。
- MAP_PROMPT + REDUCE_PROMPT（Map-Reduce 摘要）：长历史先分片，再归并。
- INCREMENTAL_PROMPT（增量摘要）：如果 history 头部已有上轮摘要，只把“上轮摘要之后到 splitIdx 之前”的新增消息送入 LLM，再把新增信息整合进已有摘要。

当前增量摘要 prompt 的核心约束是“已有摘要里的所有事实一条都不能丢”和“只在结尾追加新增内容，必要时修改被覆盖字段”。这能降低 repeated summarization（重复摘要）导致的信息失真，但也会导致旧事实难以退出摘要。

当前 `WorkingMemory` 已经有三段当前会话工作状态：

- recentToolResults（最近工具结果）：保留工具证据和精确实体。
- taskState（任务状态）：轻量 KV，例如当前任务编号、最近错误。
- volatileFacts（临时事实）：当前会话短期事实。

问题是 `taskState` 只是 KV，不适合承接跨几十轮 ReAct 的复杂计划状态。

## 3. 问题

### 3.1 摘要会膨胀

增量摘要如果每轮都做 append（追加），摘要长度会持续增长。即使每次新增内容不多，几十轮后也会超过合理预算。

### 3.2 旧事实会污染当前判断

复杂任务中经常出现“先 A 后 B 最终 C”的决策变化。如果旧摘要里长期保留 A 和 B，即使标注了最终 C，模型仍可能在后续推理中被旧值干扰。

典型例子：

- 第 5 轮决定 `chunk_size=256`
- 第 9 轮改成 `chunk_size=512`
- 第 15 轮最终改成 `chunk_size=384`

生产级摘要不应该保留三段等权历史，而应该把 active fact（活跃事实）写成“最终值是 384”，把旧值放入 superseded fact（已覆盖事实）或直接丢弃。

### 3.3 已完成步骤不应继续占用主摘要

复杂重构任务中，已完成步骤的详细过程通常没有必要继续占用上下文。模型真正需要的是：

- 哪一步 done（完成）。
- 改了哪些文件。
- 跑了哪些测试。
- 是否留下 open issue（未解决问题）。

工具输出、尝试过程、失败中间态不应该永久留在摘要正文里。

### 3.4 摘要不应该拥有任务状态

当前 plan progress（计划进度）如果只写在自然语言摘要里，会出现两个问题：

- 压缩时可能丢掉或改写当前执行位置。
- 模型需要从文本里重新推断任务状态，容易产生幻觉。

任务状态应该是结构化状态，而不是摘要里的自然语言段落。

## 4. 设计原则

Phase 27 的核心原则是：summary（摘要）只承载背景和结论，不承载当前执行状态。

另一个硬边界是：rollingSummary（滚动摘要）是 projection（投影视图），不是 source of truth（事实源）。它可以引用 WorkingMemory / TaskLedger 里的状态，但不能独立维护另一份任务状态或工具结果。否则 summary 和 WorkingMemory 一旦不一致，LLM 会被两套状态源误导。

上下文分层：

```text
conversationHistory（对话历史）
  真实 LLM messages，由 ConversationHistoryCompactor 治理窗口。

rollingSummary（滚动摘要）
  压缩后的背景、决策、未解决问题，只保留对继续推理有价值的信息。
  有损、可裁剪、不是任务状态源。

WorkingMemory（工作记忆）
  当前会话运行态，保留最近工具证据、临时事实、任务账本投影。
  不是历史压缩器。

TaskLedger（任务账本）
  结构化计划进度，记录当前任务执行到哪一步。
  是当前任务状态的 source of truth。

LongTermMemory（长期记忆）
  用户明确保存的跨会话稳定事实，不自动写入。

StickyMemory（强约束记忆）
  每轮全量注入的强约束规则。
```

## 5. 摘要结构

滚动摘要从自由文本升级为 structured summary（结构化摘要），建议固定为六段。注意：这六段不是 WorkingMemory 的替代品，只是历史压缩后的投影视图。

```markdown
## Stable Decisions
- 已经确认且未被覆盖的决策。

## Active Context
- 当前仍影响后续推理的目标、约束、关键实体；不保存当前 step 状态。

## Open Issues
- 仍未解决的问题、风险、待确认事项；不保存普通 next action。

## Completed Milestones
- 对后续决策仍有影响的已完成里程碑；不保存执行流水账。

## Superseded Facts
- 被覆盖的旧决策，只在必要时短期保留，后续 GC 可删除。

## Evidence Index
- 关键证据引用，例如 tool_call_id、文件路径、测试命令；不内联工具结果。
```

这些段落的职责：

- Stable Decisions（稳定决策）：已经定下来的设计选择。
- Active Context（活跃上下文）：模型下一轮必须知道的背景事实，不包含当前任务执行到哪一步。
- Open Issues（未解决问题）：不能丢的风险、阻塞和待确认事项，不包含普通待办。
- Completed Milestones（已完成里程碑）：只保留对后续判断有影响的完成结论，不保留过程噪音。
- Superseded Facts（已覆盖事实）：给模型知道“旧值已废弃”，避免误用。
- Evidence Index（证据索引）：保留证据定位，不把工具输出塞进摘要。

明确禁止放入 rollingSummary 的内容：

- 当前执行到哪个 step。这属于 TaskLedger。
- 最近工具调用的完整参数和返回。这属于 WorkingMemory.recentToolResults。
- 大段 build log / `ls -R` / 文件内容。这属于工具结果治理或落盘预览。
- 可由 TaskLedger 推导出的 done step 列表。summary 只保留 completed milestone（完成里程碑）。

## 6. Fact Lifecycle

每条 fact（事实）进入摘要前都应该有生命周期字段。生产级实现不要求全部暴露给 LLM，但内部结构要有这些概念。

建议字段：

```text
content: 事实内容
scope: current_turn | current_task | current_session | cross_session
status: active | done | superseded | obsolete
source: user | tool_call_id | assistant_decision | test_output
confidence: high | medium | low
expiresAfter: turns(N) | task_end | session_end | never
```

字段含义：

- scope（作用域）：事实在哪个范围内有效。
- status（状态）：事实当前是否仍有效。
- source（来源）：事实来自用户、工具、模型决策还是测试输出。
- confidence（置信度）：事实是否可靠。
- expiresAfter（过期条件）：事实什么时候可以被裁剪。

裁剪规则：

- `current_turn` 事实默认不进摘要，除非被模型标记为 active。
- `current_task` 事实在任务结束后进入 GC 候选。
- `current_session` 事实可以保留到会话结束，但不写入长期记忆。
- `cross_session` 事实只能由用户明确 `/save` 或明确要求保存，不能自动迁移。
- `superseded` 事实只保留短时间，后续只保留最终值。
- `obsolete` 事实直接裁剪。

## 7. Update Operations

增量摘要不应该只有“追加”。它应该支持四类操作：

- append（追加）：新增一个之前不存在的 active fact。
- update（更新）：修改已有 fact 的最终值。
- supersede（覆盖）：把旧 fact 标记为已覆盖，并写入最终值。
- prune（裁剪）：删除 obsolete（过时）或低价值内容。

LLM prompt 可以要求输出结构化更新结果，例如：

```json
{
  "append": [],
  "update": [],
  "supersede": [],
  "prune": []
}
```

第一阶段可以先不引入 JSON parser（JSON 解析器），只改 prompt，让 LLM 按固定 Markdown 段落重写摘要。第二阶段再引入结构化解析。

## 8. Summary GC

SummaryGarbageCollector（摘要垃圾回收器）负责控制摘要不会无限增长。

触发条件：

- summary token（摘要 token）超过预算，例如 `maxSummaryTokens=4000`。
- compaction count（压缩次数）超过阈值，例如连续压缩 3 次。
- Completed Milestones 或 Superseded Facts 段落超过阈值。
- 当前 task boundary（任务边界）发生变化，例如一个计划阶段结束。

GC 动作：

- 删除 obsolete facts（过时事实）。
- 把 completed milestones 合并成一行，例如“已完成 A/B/C，验证命令 X 通过”。
- 对 superseded facts 只保留最终值，删除旧值推导过程。
- Evidence Index 只保留 source reference（来源引用），不保留大段工具输出。
- 不自动写入 LongTermMemory，除非用户明确保存。

GC 后摘要目标：

```text
Active Context + Stable Decisions + Open Issues <= 主要预算
Completed Milestones <= 少量预算
Superseded Facts <= 极少预算或空
Evidence Index <= 引用预算，不放正文
```

## 9. TaskLedger

TaskLedger（任务账本）用于承接“第 5 轮定了 Plan，第 15 轮压缩，第 20 轮还知道执行到哪一步”的问题。

它应该放在 `WorkingMemory` 旁边，或作为 `WorkingMemory` 的结构化 sub-store（子存储）。摘要只引用当前任务概况，不拥有任务状态。

建议结构：

```text
planId
stepId
description
status: pending | running | done | failed | skipped
dependencies
filesTouched
testsRun
lastError
nextAction
updatedAtTurn
```

渲染进 prompt 时只输出紧凑视图：

```markdown
## Task Ledger
- plan: refactor-memory-2026-05-18
- current: step-3 running, update ConversationHistoryCompactor prompt
- done: step-1 inspect current compactor; step-2 write Phase 27 design
- next: add tests for summary pruning
- last_error: none
```

TaskLedger 和现有 `plan.Task` / `runtime.task.DurableTask` 的关系：

- `plan.Task` 是 Plan-and-Execute 的任务模型，可以借鉴 status / dependencies。
- `DurableTask` 是 runtime API 的后台任务持久化模型，不应该直接混用到 ReAct 会话状态。
- TaskLedger 是当前会话执行账本，优先解决长 ReAct 循环中的状态连续性。

## 10. 与 WorkingMemory 的边界

WorkingMemory 不能删除。它解决的是 rollingSummary 解决不了的“当前运行态”和“工具证据”问题。

原因：

- rollingSummary 是 lossy compression（有损压缩），会泛化工具输出；WorkingMemory.recentToolResults 要保留近期工具参数、结果、错误、路径、行号和测试输出。
- rollingSummary 是历史背景投影；WorkingMemory 是当前会话运行态。
- rollingSummary 可以引用 Evidence Index，但不能保存原始 evidence（证据）。
- TaskLedger 的紧凑视图可以通过 WorkingMemory 注入，但 TaskLedger 才是任务状态源。

WorkingMemory 继续保留三类内容：

- recentToolResults（最近工具结果）：保留最近证据，FIFO 淘汰。
- volatileFacts（临时事实）：保留当前会话临时判断。
- taskState（任务状态）：短期 KV，后续可被 TaskLedger 替代或兼容。

Phase 27 后建议演进为：

```text
WorkingMemory
  ├── recentToolResults
  ├── volatileFacts
  └── taskLedger
```

其中 `taskState` 可以保留为兼容层，但新的长任务状态写入 `taskLedger`。

边界表：

| 内容 | 归属 | 是否进入 rollingSummary |
|------|------|-------------------------|
| 当前 step / nextAction / lastError | TaskLedger | 否，只允许摘要引用“见 TaskLedger” |
| 最近工具参数和完整返回 | WorkingMemory.recentToolResults | 否 |
| 工具证据定位，如文件路径、测试命令、tool_call_id | Evidence Index | 是，只存引用 |
| 稳定设计决策 | RollingSummary.Stable Decisions | 是 |
| 用户明确长期偏好 | LongTermMemory / StickyMemory | 只在检索或全量注入时出现，不由 summary 写入 |
| 已完成执行流水账 | TaskLedger | 否 |
| 对后续仍有影响的完成结论 | Completed Milestones | 是，压缩成一行 |

区分垃圾信息和硬事实的规则：

- 大段 `ls -R`、完整 build log、重复 stdout 默认是 low-value evidence（低价值证据），只进 recentToolResults，不进 rollingSummary。
- 文件路径、函数名、错误码、测试结论、用户明确约束是 hard fact（硬事实），可以进入 Active Context 或 Evidence Index；如果有原始工具返回，则原文仍以 WorkingMemory 为准。
- 模型自己的推测默认是 low confidence（低置信度），不能和 tool evidence（工具证据）等权。
- 用户明确纠正的信息优先级最高，应 supersede 旧事实。

## 11. 实施计划

### 27-A Prompt-only 改造

只修改 `ConversationHistoryCompactor.INCREMENTAL_PROMPT`：

- 从“保留已有摘要所有事实”改成“保留仍然活跃的事实”。
- 要求输出固定六段 Markdown：Stable Decisions / Active Context / Open Issues / Completed Milestones / Superseded Facts / Evidence Index。
- 明确允许裁剪 completed / superseded / obsolete 内容。
- 要求旧值被覆盖时只保留最终值。
- 明确禁止输出当前 step、完整工具结果和执行流水账，避免和 WorkingMemory / TaskLedger 重复。

优点：改动小，风险低。

缺点：仍依赖 LLM 按格式输出，无法强约束 token 上限。

### 27-B RollingSummary parser / renderer

新增：

- `RollingSummary`（滚动摘要模型）
- `RollingSummaryParser`（摘要解析器）
- `RollingSummaryRenderer`（摘要渲染器）

职责：

- 把 Markdown 段落解析为结构化对象。
- 对缺失段落 graceful degradation（优雅降级）。
- 渲染时保证段落顺序稳定，方便 prompt cache（提示词缓存）命中。

### 27-C SummaryGarbageCollector

新增：

- `SummaryGarbageCollector`（摘要垃圾回收器）
- `SummaryPrunePolicy`（摘要裁剪策略）

职责：

- 按 token budget（token 预算）裁剪摘要。
- 优先保留 Active Context / Stable Decisions / Open Issues。
- 压缩 Completed Milestones。
- 删除 Superseded Facts 里的过期旧值。

### 27-D TaskLedger

新增：

- `TaskLedger`
- `TaskLedgerEntry`
- `TaskLedgerRenderer`

接入：

- `MemoryManager` 提供 `setTaskStep(...)` / `completeTaskStep(...)` / `failTaskStep(...)`。
- `Agent` / `PlanExecuteAgent` / `SubAgent` 在计划拆解、工具执行、错误恢复时更新 TaskLedger。
- `PromptAssembler` 在 working memory 段中注入紧凑 TaskLedger 视图。

> 落地状态（MVP，已实现）：`TaskLedger`（含内嵌 `Entry` 与 `render()`，未单独拆 `TaskLedgerRenderer`）挂在 `WorkingMemory`；`MemoryManager` 提供 `setTaskLedgerPlan` / `startTaskStep` / `completeTaskStep` / `failTaskStep` 门面；**仅 `PlanExecuteAgent` 接入**（计划创建时注册步骤，task 开始/完成/失败时更新）；经 `WorkingMemory.renderForPrompt()` → `buildWorkingMemorySection()` 注入 "## Working Memory" 段（非 `PromptAssembler` 直接注入）。TaskLedger 挂在 WorkingMemory、不进 conversationHistory，压缩不触碰它。`Agent`（ReAct）/ `SubAgent`（Multi-Agent）接入留后续。

### 27-E Eval 与回归

新增 QA Eval：

- 多轮计划场景：第 5 轮创建 plan，第 15 轮压缩，第 20 轮确认 current step。
- 覆盖事实场景：A -> B -> C，最终只保留 C。
- 已完成任务场景：done steps 被压缩成一行，不丢测试结论。
- 噪声工具输出场景：超长 `ls -R` 不进入主摘要。
- 多次压缩场景：连续 5 次压缩后 summary token 不线性增长。

## 12. 测试建议

单元测试：

- `RollingSummaryParserTest`
  - 缺失段落能解析。
  - 段落顺序乱了能恢复。
  - 空摘要能降级为空对象。

- `SummaryGarbageCollectorTest`
  - completed milestones 被折叠。
  - superseded facts 被裁剪。
  - active/open facts 被保留。
  - summary token 超预算时仍保留关键实体。

- `TaskLedgerTest`
  - step 状态流转正确。
  - failed step 保留 lastError。
  - renderer 输出稳定顺序。

- `ConversationHistoryCompactorTest`
  - 增量摘要不会无限追加旧事实。
  - 覆盖决策只保留最终值。
  - 压缩后仍落在 user message 边界，不能破坏 tool_call 协议。

集成测试：

- 构造 20 轮 ReAct messages。
- 人为触发 5 次压缩。
- 断言：
  - current task step 存在于 TaskLedger / WorkingMemory 投影，不存在于 rollingSummary 正文。
  - old completed details 不存在。
  - final decision 存在。
  - obsolete decision 不存在或只出现在 Superseded Facts。
  - summary token 小于阈值。

## 13. 非目标

Phase 27 不做这些事：

- 不自动把摘要写入 LongTermMemory。
- 不让 LLM 单独拥有任务状态。
- 不把所有工具输出都塞进摘要。
- 不改变用户明确保存长期记忆的规则。
- 不删除原始 conversationHistory 尾部保留区。
- 不用摘要替代 WorkingMemory。

## 14. 面试回答口径

如果被问“增量摘要越来越多，旧摘要里无用内容怎么解决”，可以这样回答：

> 当前项目的增量摘要解决的是 repeated summarization（重复摘要）导致的事实失真：上轮摘要不再被反复重压，只把新增消息并入。但它还不能完整解决 summary bloat（摘要膨胀）。生产级方案要在摘要上引入 fact lifecycle（事实生命周期）、structured summary（结构化摘要）和 Summary GC（摘要垃圾回收）。已完成步骤会被折叠，已覆盖事实只保留最终值，真正的当前执行进度不放在摘要里，而是放到 TaskLedger（任务账本）这种结构化工作状态里。这样第 15 轮压缩后，第 20 轮模型不是从自然语言里猜执行到哪一步，而是直接看到当前 step、done steps、next action 和 last error。

如果被问“既然摘要能保留证据索引，是否可以删除 WorkingMemory”，可以这样回答：

> 不应该删除 WorkingMemory（工作记忆）。rollingSummary（滚动摘要）是有损的历史压缩，只能保存背景、稳定决策、未解决问题和证据引用；WorkingMemory 保存的是当前会话运行态，尤其是 recentToolResults（最近工具结果）里的工具参数、原始返回、错误、路径、行号和测试输出。Evidence Index（证据索引）只是目录，不是证据正文。生产级设计里 rollingSummary 是 projection（投影视图），WorkingMemory / TaskLedger 才是当前任务和工具证据的 source of truth（事实源）。

## 15. 生产级改进

如果作为商业级 Agent 产品继续做，Phase 27 之后还需要三类增强：

### 15.1 可观测性

- 记录每次压缩前后的 token usage（token 使用量）。
- 记录 prune ratio（裁剪比例）。
- 记录 fact retention（事实保留率）。
- 记录 hallucination recovery（幻觉恢复）案例：模型误用旧事实后是否能被 TaskLedger / Evidence Index 拉回。

### 15.2 策略可配置

不同任务类型的摘要策略不同：

- code refactor（代码重构）：更重视文件路径、测试结果、当前 step。
- bug investigation（缺陷排查）：更重视假设、证据、失败路径。
- documentation（文档任务）：更重视用户口径、术语、交付范围。
- shell automation（命令自动化）：更重视命令、退出码、风险确认。

可以通过 `ContextProfile` 或新的 `SummaryProfile` 配置不同预算。

### 15.3 人工确认边界

当 GC 准备删除 high confidence（高置信度）且 source=user（用户来源）的事实时，应该保守处理：

- 默认不删用户强约束。
- 如果事实被用户后续明确覆盖，保留最终值。
- 如果冲突无法判断，放入 Open Issues，而不是直接删除。

这能避免为了压 token 把用户关键要求裁掉。
