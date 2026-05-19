# conversationHistory 压缩器：真实 LLM 多轮压缩 QA Eval 诊断与优化

> 范围：`com.paicli.memory.ConversationHistoryCompactor`（生产路径，每次 ReAct 主循环调 LLM 前评估并压缩 `conversationHistory` 的那个压缩器）。不涉及 `ContextCompressor`（短期记忆 Map-Reduce，已废弃路径）。
>
> 诊断方法：真实 LLM（Kimi 协议 / 本地代理 / gemini-3-flash-preview）多轮压缩 QA Eval。Eval 入口：`src/test/java/com/paicli/benchmark/RealLlmCompressionRetentionIT.java`。

---

## 一、问题背景

### 1.1 压缩器的位置

PaiCLI 的 ReAct 主循环每次调 LLM 前先做一次 `compactIfNeeded` 检查：

```
Agent.run 主循环
  ├─ maybeCompactHistory()
  │    └─ ConversationHistoryCompactor.compactIfNeeded(history, triggerTokens)
  ├─ 调 LLM
  └─ 处理工具调用
```

`triggerTokens` 来自 `ContextProfile.compressionTriggerTokens()`，默认 `maxContextWindow × 90%`（Kimi 256k → 阈值 230k）。

### 1.2 第一版实现

第 12 期长上下文工程时引入。算法朴素：

1. 找出所有 user message 索引
2. 保留最近 `retainRecentRounds=3` 个 user 起算的尾部
3. 把 system 之后 / 保留尾部之前的全部消息，**整段拼成字符串送 LLM 出摘要**
4. 关键约束：分割点对齐 user 边界，避免切散 tool_call/tool_result

实现细节里有一个会出问题的安全垫：

```
sb 字符长度 > MAX_SUMMARY_INPUT_CHARS (60_000) → break
```

意图是防止"摘要请求自己撑爆 LLM window"，效果是**前 60k 字符之后的内容直接丢弃，不进 LLM 视野**。

### 1.3 已删除的兜底测试

早期曾有一条本地 deterministic eval（确定性评测）：

- QA 用 substring `contains` 字面匹配
- 压缩器是测试里另写的抽取式 compactor（按白名单关键词整句保留），不是生产的 LLM 摘要
- 关键词白名单和题目"碰巧"匹配，命中率会虚高

这类测试根本没在测生产压缩器，属于循环论证 + fixture 自洽，已从 benchmark 入口删除。简历指标只能使用 `RealLlmCompressionRetentionIT` 的真实 LLM 结果。

---

## 二、改造为真实 LLM 端到端 Eval

### 2.1 设计

`RealLlmCompressionRetentionIT`（benchmark 包，quick profile 自动跳过）：

1. **真实 LLM**：从 `.env` 读 KIMI_API_KEY + KIMI_BASE_URL，构造真实 `LlmClient`。端点不可达自动 skip，不阻塞 CI。
2. **生产真实阈值**：`window × 90%`（Kimi 230k）。不再像早期那样把 trigger 缩到 1/4 偷懒。
3. **直接调生产路径**：`new ConversationHistoryCompactor(llm, ...).compactIfNeeded(...)`，不用替身。
4. **多轮压缩**：连续追加对话直到再次撞 230k，逼出 5 次压缩。
5. **真实 QA**：每条事实跟一个问题，压缩完用同一个 LLM 看着压缩后的 history 回答；用关键词 hit 判定（不是自动 contains 把题做掉）。

### 2.2 18 题分 4 档难度

题目是"信号强度"的设计实验，不是"考摘要器它强项的水题"：

| 档次 | 出现方式 | 期望 retention |
|---|---|---|
| **EASY (5)** | 明确决策类、被反复引用 | 摘要器强项 |
| **MEDIUM (5)** | 单次提及但带语义锚点 | 中等 |
| **HARD_ENTITY (5)** | 埋在 tool_call args / tool_result 里的精确实体（文件名/路径/数字常量/错误码） | 摘要器弱项 |
| **HARD_OVERRIDE (3)** | 决策被多次覆盖（先 5 后 8 最后 10），只接受最新值 | 检验"识别最终值"能力 |

题目刻意不复用 fact 原文里的关键词，避免变成关键词检索题。

### 2.3 协议不变量硬断言（与 retention 正交）

不管 retention 多少，必须守住的 4 条：

1. tool_call 与 tool_result 在压缩前后必须配对完整
2. 保留区第一条必须是 user role
3. 多轮压缩 token 单调收敛（不上升）
4. system 永远在 history[0]

由 `ConversationHistoryCompactorTest` 的 11 条契约测试保证（mock LlmClient 替换 summarize）。

---

## 三、缺陷诊断（v0 实测 16.7%）

跑真实 LLM IT 的真实数据：

```
init=236k token | 5 次压缩 | 整体保留率 16.7% (3/18)
分档：EASY 40% | MEDIUM 20% | HARD_ENTITY 0% | HARD_OVERRIDE 0%

压缩曲线：236k → 6.2k → 6.2k → 6.2k → 6.2k → 6.2k
```

3 个设计缺陷叠加，每条都对应代码里一行具体的设计选择。

### 缺陷 1：摘要器只看到前 8% 内容

`MAX_SUMMARY_INPUT_CHARS = 60_000`。240k token 历史转字符串约 720k 字符，**只有前 60k（前 8%）会进 LLM 的 summarize prompt**，后面 92% 直接被 break 掉。

后段事实摘要器从未"见过"。这是为什么"路径降噪策略"、"调用链 3 跳"、"top_k=10 最终值"全军覆没——它们都在历史的中后段。

是 first-N 截断，不是 sliding window，不是 sampling。

### 缺陷 2：保留区按 user 数算，不按 token

`retainRecentRounds=3`：保留"最后 3 个 user 起算的尾部"，不管这中间有多少内容。

两个失败模式：
- **保留区太小**：纯文字对话每轮 1k，保留 ~3k → 摘要 ~5k → 下次循环只剩这点上下文 → 中段事实必丢
- **保留区太大**：工具结果塞 50k，保留 ~150k → "压完"还是 230k → 压缩失效（工具密集场景）

按 user 数算的设计在两个极端都坏。

### 缺陷 3：摘要套娃

每次 `compactIfNeeded` 调用都是从零开始：找 splitIdx，把 splitIdx 之前的全部喂给 LLM 摘要。

```
轮 1: oldMsgs = [user1, ..., user80]                  → 摘要 S1
轮 2: oldMsgs = [user("S1"), ...新增对话, ..., userN]  → 摘要 S2
       ↑ 老摘要又被当作普通消息再压一遍 = 摘要的摘要
轮 3: oldMsgs 又包含 S2 → 摘要的摘要的摘要
轮 5: 5 层套娃，每层都在丢信息
```

EASY 档（被反复引用的事实）在 5 次压缩后只剩 40%——前面引用没用，每次都把上一轮摘要再压一遍逐层稀释。

---

## 四、改造 v1：Map-Reduce 分片摘要（27.8%）

### 4.1 思路

去掉 first-N 截断，改成 Map-Reduce：

- **Map**：旧消息按 60_000 字符分片，每片送一次 LLM 出片摘要
- **Reduce**：多片摘要合并为最终摘要；片数 > 8 时先两两合并降阶
- 单片场景退化为单次摘要

整段历史都进 LLM 视野，不再 first-N 截断。

### 4.2 真实 LLM 数据

```
init=236k token | 5 次压缩 | 整体保留率 27.8% (5/18)
分档：EASY 0% | MEDIUM 40% | HARD_ENTITY 20% | HARD_OVERRIDE 67%
```

### 4.3 涨与不涨的解读

- **HARD_OVERRIDE 67%**：决策变更最终值识别（top_k 5→8→10、chunk_size 256→512→384）大幅改善——分片 LLM 看到了完整对话流才能识别"最新值"
- **HARD_ENTITY 20%**：精确实体也好转，但仍很差
- **EASY 反而归零**：分片摘要让 LLM 失去了全局 fact 重要性判断——每片各自出摘要时把 noise 闲聊（"测试覆盖率"、"先稳一稳"）当 fact，把真正的决策（"用 SymbolSolver 不引入"）当背景

Map-Reduce 解决了"摘要器看不见后段"，但**没解决摘要套娃**——每次压缩还是把上一轮摘要拉进来重压。

---

## 五、改造 PR-1：token 预算保留区 + 增量摘要（77.8%）

### 5.1 三个改动

1. **token 预算保留区**：`retainRecentRounds=3` → `retainRecentTokens=30_000`。从尾巴往前累计 token，到阈值时停在 user 边界。保留区大小可控，工具密集场景不撑大、普通对话不饿死。
2. **检测上轮摘要**：history 头部第一条 user 如果以 `[已压缩的历史对话摘要]` 开头，认定为上一轮压缩留下的摘要 base。
3. **增量摘要 (`summarizeIncremental`)**：检测到上轮摘要时，**只把"上轮摘要之后到 splitIdx 之前"的新消息送 LLM**，prompt 里把老摘要作为 base，让 LLM 把新内容并入而非全量重写。

不再套娃。

### 5.2 真实 LLM 数据

```
init=236k token | 5 次压缩 | 整体保留率 77.8% (14/18)
分档：EASY 60% | MEDIUM 100% | HARD_ENTITY 60% | HARD_OVERRIDE 100%

压缩曲线：236k → 4.4k → 4.6k → 4.6k → 4.6k → 4.7k
```

### 5.3 三版对比

| 维度 | v0 截断 | v1 Map-Reduce | **PR-1 增量** |
|---|---|---|---|
| 整体 | 16.7% | 27.8% | **77.8%** |
| EASY | 40% | 0% | 60% |
| MEDIUM | 20% | 40% | **100%** |
| HARD_ENTITY | 0% | 20% | 60% |
| HARD_OVERRIDE | 0% | 67% | **100%** |
| 压缩曲线 | 6k 持平 | 6k 持平 | **4.4k 持平** |
| 单次压缩耗时 | 9s | 84s | 98s |
| 总耗时 | 6 分 | 12 分 | 13 分 |

### 5.4 还在丢的 4 题

| 档 | 题 | 实际回答 | 归因 |
|---|---|---|---|
| EASY | "对依赖范围明确禁止的事？" | "严禁引入新依赖" | 摘要把 SymbolSolver 泛化掉了 |
| EASY | "Agent 跟用户对话用什么语言？" | "未提及" | 用户偏好类事实在多轮增量后稀释 |
| HARD_ENTITY | "RAG 模块单元测试跑了多久？" | "未提及" | tool_call 输出里的数字常量 47.3s 丢失 |
| HARD_ENTITY | "异常发生在哪个具体的位置？" | "未提及" | stack trace 里的行号 217 丢失 |

模式：**没有显式 pin、靠摘要泛化能力的精确实体仍然容易被改写丢失**。

### 5.5 测试与契约

- 11 条契约测试全过（user 边界 / tool_call 配对 / 单调收敛 / 增量路径调用 4 个不变量）
- 真实 LLM IT 阈值从 50% 提到 70%，PASS

---

## 六、后续优化路线（PR-2 / PR-3 / PR-4）

### PR-2：StickyContext + 启发式 pinned facts

**目标**：解决 PR-1 还在丢的 4 题（用户偏好、tool_call args 数字常量）。

**做法**：新增 `StickyContext` 数据结构，存"显式决策、用户偏好、错误码、关键 ID"等永不被压缩的事实。每次工具结果回灌后扫一遍，挑出可 pin 项追加到 `pinned_facts`。注入路径：每次组装 system prompt 时把 pinned_facts 整段塞进去。

**自动 pin 的启发式（首版只做这 3 条）**：
1. 用户消息含"决定 / 用 / 选择 / 改成 / 偏好"等关键词
2. tool_call args 里的精确路径 / 文件名（正则识别）
3. 数字常量（端口、版本号、时长、行号等）

**预期 retention**：77.8% → 85%+，HARD_ENTITY 从 60% 提到 80%+。

**工作量**：1 天。
**风险**：启发式规则容易越写越复杂；首版严格只做 3 条，避免 false positive 灾难。

### PR-3：rolling_summary 注入 system prompt（替代 user 消息装摘要）

**目标**：架构清晰、KV cache 命中率提升、释放假 user/assistant 配额。

**做法**：压缩重建结构改为：

```
旧：[system, user("[摘要]"+S), assistant("好的"), 保留尾部]
新：[system + "\n\n## 历史摘要\n" + S, 保留尾部]
```

摘要进 system 部分，模型把它当背景而非对话；保留尾部第一条还是真实 user，OpenAI 协议干净；prompt cache 命中前缀更长（system 占用增加但稳定）。

**预期 retention**：77.8% → 80%+（边际收益），主要好处是协议清晰和 cache 友好。

**工作量**：半天。
**风险**：现有 6 条契约测试断言"压缩后 history[1] 是 user(摘要)"，要更新到新结构。

### PR-4：双阈值 + KV cache 顺序优化

**目标**：成本下降，普通会话不再走全量 Map-Reduce。

**做法**：
1. 软阈值 60%：增量摘要（便宜）
2. 硬阈值 90%：兜底 Map-Reduce（贵，最后防线）
3. system prompt 内部按 1 → 2 → 3 → 4 顺序排（稳定层在前，rolling_summary 放最后），最大化 KV cache 命中率

**预期 retention**：与 PR-1 相当，单次成本下降，KV cache 折扣 5-10x。

**工作量**：半天。

---

## 七、四个 PR 的边际收益与建议

| PR | retention 预期 | 工作量 | 性价比 |
|---|---|---|---|
| PR-1（已做） | 16.7% → 77.8% | 0.5 天 | **极高** |
| PR-2 | 77.8% → 85%+ | 1 天 | 中 |
| PR-3 | 77.8% → 80%+ | 0.5 天 | 中（架构收益 > retention 收益）|
| PR-4 | 持平 | 0.5 天 | 低（成本收益）|

**当前建议**：到此收尾。

理由：
- PR-1 已经把 retention 推到 77.8%，最大的工程价值（"识别 3 个缺陷 + 验证修复 + 量化对比"）已经讲全
- PR-2/3/4 边际收益递减，主要是细节打磨
- 简历讲故事到 PR-1 已经足够耐打

如果还要继续，**优先 PR-3（半天，架构收益高）**，最后 PR-2（性价比看 retention 还能不能再涨）。

---

## 八、给简历的口径

> 设计 Agent 上下文压缩体系：消息历史达 `window × 90%` 阈值（Kimi 230k）触发 LLM 摘要并按 user 边界回注，分割点对齐 user 保护 tool_call/tool_result 协议。基于真实 LLM 多轮压缩 QA Eval（单会话连续触发 5 次压缩，18 题分 4 档难度），定位生产实现 3 个设计缺陷（first-N 截断 / 保留区按 user 数算 / 多轮压缩套娃），逐步重构为 Map-Reduce 分片摘要 → token 预算保留区 + 增量摘要管线，整体保留率 16.7% → 27.8% → 77.8%；其中决策变更最终值识别（HARD_OVERRIDE）从 0% 提升至 100%，单次提及类（MEDIUM）100%，工具结果精确实体（HARD_ENTITY）60%。

## 九、面试可能追问与标准答法

### Q1：「Map-Reduce 是怎么 Map 怎么 Reduce 的？」

旧消息按每片 60k 字符切。每片调一次 LLM 出独立片摘要——这是 Map。N 片摘要拼成一个 prompt 再调一次 LLM 合并成最终摘要——这是 Reduce。如果切出 1 片就跳过 Reduce 直接用。片数过多时（>8 片）先两两合并降阶再 Reduce，避免 Reduce prompt 自己撑爆 window。

### Q2：「阈值 90% 是 token 算的还是消息条数？怎么估？」

Token。每条消息字符数加起来按经验公式估算（中文 1.5 字/token，英文 4 字符/token），所有消息加总和 `window × 90%` 比。模型不同 window 不同：GLM-5.1 200k → 阈值 180k；Kimi 256k → 阈值 230k；DeepSeek V4 1M → 阈值 900k。

### Q3：「切的时候怎么不破坏 tool_call 和 tool_result 的配对？」

关键约束：分割点必须落在 user 消息边界。从尾巴往前累计 token，到 retainRecentTokens 时停在最近的 user 上。这样 assistant(tool_call) 和它的 tool(result) 一定在同一边——要么都在压缩区，要么都在保留区，不会被切散。

这个不变量我专门写了契约测试守护：`splitPointAlwaysLandsOnUserRole` / `summarizeReceivesCompleteToolCallPairs` / `compactionStrictlyReducesTokenCount` 等共 11 条。

### Q4：「77.8% 不是 100%，丢了什么？为什么？」

丢的 4 条集中在两类：
- **未显式 pin 的用户偏好**（"用中文沟通"、"不引入 SymbolSolver"）：增量摘要在 5 次轮转后会泛化掉
- **tool_call args 里的精确实体**（47.3 秒、stack trace 行号 217）：埋在 JSON 参数里，没在自然语言里复述过

下一步优化方向是 PR-2（启发式 pinned_facts），把这两类自动 pin 到永不丢失的 sticky 区。

### Q5：「为什么 Map-Reduce 那一版反而 EASY 档归零了？」

分片摘要让每片 LLM 失去全局 fact 重要性判断——它倾向于把"测试覆盖率""先稳一稳"这类灌 token 的过渡话术当 fact，反而把真实决策（"用 SymbolSolver 不引入"）当背景。这是为什么 PR-1 改成增量摘要而不是继续 Map-Reduce 改 prompt——架构层面解掉，比反复调 prompt 稳定。

### Q6：「为什么不直接做 4 层架构（Identity / Capability / Sticky / Conversation）？」

理论上对。当前 PaiCLI 已经有 Layer 1/2 的雏形（Identity 在 PromptAssembler、Capability 在 ToolRegistry），缺的是 Layer 3（StickyContext）和 Layer 4 内部的策略。PR-1 解决了 Layer 4 内部的核心问题（截断 / 保留区 / 套娃），retention 从 16.7% 推到 77.8%。PR-2/3 是把架构推到 4 层完整版。这种从瓶颈逐步往外推的做法，比一次性大重构稳定且可量化。

---

## 十、关键文件索引

```
src/main/java/com/paicli/memory/
  ConversationHistoryCompactor.java     # 生产压缩器，PR-1 已落地
  ConversationHistoryCompactor.java     # 真实 LLM messages 压缩；旧 ContextCompressor 路径已删除

src/test/java/com/paicli/memory/
  ConversationHistoryCompactorTest.java # 11 条契约测试

src/test/java/com/paicli/benchmark/
  RealLlmCompressionRetentionIT.java    # 真实 LLM 多轮压缩 QA Eval

target/benchmark/
  real-llm-compression-retention.json   # 每次跑完的详细报告（含 18 题逐题命中、压缩曲线、分档保留率）
```

## 十一、Eval 复跑指令

```bash
# 11 条契约测试（不需要 LLM，秒级完成）
mvn test -Dtest=ConversationHistoryCompactorTest -DskipTests=false

# 真实 LLM IT（需要 .env 里配了 KIMI_API_KEY + KIMI_BASE_URL，约 12-15 分钟）
mvn test -Dtest=RealLlmCompressionRetentionIT -DskipTests=false
```
