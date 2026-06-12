# Memory And Context Governance Design

## Status

First implementation phase completed.

- Completed: `SymbolVersion（符号版本）` is generated for indexed search results.
- Completed: `IndexEpoch（索引版本）` is generated on each `/index` rebuild and stored on `code_chunks`.
- Completed: `SymbolVersionDiff（符号版本差异）` compares old/new symbol snapshots during index replacement.
- Completed: `InvalidationMemory（失效记忆）` is persisted in `symbol_invalidations`.
- Completed: `NegativeFact（负向事实）` is emitted with retrieved stale symbols and injected into `WorkingMemory（工作记忆）`.
- Completed: `search_code` output now carries `symbolVersion`, `indexEpoch`, and `classpathEpoch（类路径版本）` evidence metadata.
- Completed: `WorkingMemory（工作记忆）` extracts `search_code` output into `RagEvidenceMemory（RAG 证据记忆）` and injects it into prompt context.
- Not completed: running Worker interruption and Orchestrator-level re-plan.

## 暴露的问题

多轮面试都在追问 `Memory（记忆）` 和 `Context（上下文）` 的一致性：

- 上下文压缩会不会丢变量名、路径、方法签名？
- 长期记忆保存什么，不保存什么？
- 敏感信息和模糊事实怎么处理？
- 用户推翻旧设定后，旧记忆怎么失效？
- 代码符号变化后，Worker Prompt 中的旧事实怎么办？
- Token 压力下，哪些信息必须置顶？

## 当前已有

- `ConversationHistoryCompactor（对话历史压缩器）` 控制真实 LLM messages 窗口。
- `WorkingMemory（工作记忆）` 保存当前会话派生状态和工具证据。
- `LongTermMemory（长期记忆）` 保存跨会话稳定事实。
- 长期记忆主要通过 `/save` 或用户明确要求保存。
- 敏感信息和模糊个人状态不会自动保存。
- Multi-Agent 按 Planner / Worker / Reviewer 注入不同视图。

## 不足

- 长期记忆条目没有绑定 `SymbolVersion（符号版本）`。
- RAG 证据目前已绑定 `SymbolVersion（符号版本）`、`IndexEpoch（索引版本）` 和 `ClasspathEpoch（类路径版本）`。
- 压缩摘要和代码结构变化没有联动。
- `NegativeFact（负向事实）` 已能从检索结果进入 `WorkingMemory（工作记忆）`，但还没有 Orchestrator 主动广播。
- `InvalidationMemory（失效记忆）` 已持久化，但还没有运行中 Worker 中断。
- 运行中 Worker 不会因记忆过期被阻断。

## 怎么修改

### 1. 记忆分层固定化

明确六层：

```text
StickyMemory（强约束记忆）
TaskStateMemory（任务状态记忆）
RagEvidenceMemory（RAG 证据记忆）
InvalidationMemory（失效记忆）
WorkingMemory（工作记忆）
LongTermMemory（长期记忆）
```

### 2. RagEvidenceMemory

每条 RAG 证据记录：

- file path.
- symbol name.
- chunk id.
- index_epoch. 已完成。
- symbol_version. 已完成第一阶段。
- classpath_epoch. 已完成第一阶段。
- retrieval reason.
- confidence.

### 3. InvalidationMemory

代码结构变化后写入：

```text
old fact: OrderService.createOrder(User, OrderDTO)
new fact: OrderService.createOrder(CreateOrderRequest)
status: old fact invalid
affected workers: [...]
```

### 4. NegativeFact 注入

刷新 Worker 时，不能只给新事实，还要给旧事实不可用：

```text
Do not use OrderService.createOrder(User, OrderDTO).
It was replaced by OrderService.createOrder(CreateOrderRequest).
```

### 5. 压缩保真规则

压缩时必须保留：

- 用户硬约束。
- 文件路径。
- 方法签名。
- 错误指纹。
- 已接受 PatchSet。
- 当前 DAG 状态。
- 失败原因。

## 设计边界

- 不把所有历史都塞进上下文。
- 不把临时事实写入长期记忆。
- 不自动保存敏感信息。
- 不把摘要当成代码事实来源。
- 不让过期 RAG 证据继续驱动写入。

## 验收标准

- RAG 证据带 symbol_version 和 classpath_epoch。已完成。
- RAG 证据带 index_epoch。已完成。
- 代码符号变化能产生 InvalidationMemory。已完成。
- Worker 刷新上下文时能看到 negative facts。已完成。
- 长期记忆策略能拒绝敏感和模糊事实。
- 压缩后仍保留路径、签名、错误指纹等硬锚点。

## 本次落地

### 代码链路

```text
VectorStore.SearchResult
  -> IndexEpoch.next()
  -> SymbolVersion.from(file, chunkType, name, content, classpathEpoch)
  -> SymbolVersionDiff(oldSnapshot, newSnapshot)
  -> SymbolInvalidation / NegativeFact
  -> SearchResultFormatter.formatForTool
  -> search_code tool result
  -> MemoryManager.addToolResult
  -> WorkingMemory.RagEvidenceMemory
  -> renderForPrompt
```

### 当前行为

`search_code` 返回结果时，每个代码块多一行证据元数据：

```text
evidence: symbolVersion=sv_xxx, indexEpoch=idx_xxx, classpathEpoch=yyy
negativeFact: Do not rely on xxx from symbolVersion sv_old.
```

`WorkingMemory（工作记忆）` 看到 `search_code` 工具结果后，会解析证据元数据，形成结构化 `RagEvidenceMemory（RAG 证据记忆）`。如果检索到的符号存在旧版本失效事件，`NegativeFact（负向事实）` 会进入当前会话关键事实，后续 Worker / Reviewer 的上下文不再只看到“某段代码文本”，还能看到“这段代码文本属于哪个索引版本、哪个符号版本、哪些旧版本不可用”。

### 还没做

- 运行中 Worker 中断：需要 Orchestrator 根据符号版本变化判断是否暂停、刷新上下文或重跑子任务。
- `LongTermMemory（长期记忆）` 代码事实版本化：需要禁止无版本代码事实长期保存。
- `IndexEpoch（索引版本）` 目前是重建批次，不是影子索引原子切换。

## 文字解释

面试时可以这样讲：

> 我不会把记忆简单理解成“摘要越短越好”。代码 Agent 的记忆要分层：长期记忆只存稳定跨会话事实，当前任务状态放 `TaskStateMemory（任务状态记忆）`，检索证据放 `RagEvidenceMemory（RAG 证据记忆）`，代码变化导致的旧事实失效放 `InvalidationMemory（失效记忆）`。每条代码相关记忆都绑定 file、symbol、`IndexEpoch（索引版本）` 和 `SymbolVersion（符号版本）`。当接口签名变化时，不只是刷新 RAG，还要向 Worker 注入 `NegativeFact（负向事实）`，明确告诉它旧签名不可用。
