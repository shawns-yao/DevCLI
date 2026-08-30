# Markdown + SQLite 分层记忆设计

> 状态：分阶段实现中。2026-08-30 已交付语义卡向量索引，以及 Markdown 正文/证据真值源和 SQLite Catalog 兼容迁移；Catalog outbox、FTS5/HNSW 和 L1 Hot Working Set 仍未交付。本文不构成基准成绩。

## 当前实现边界（2026-08-30）

- Markdown `records/<hash-prefix>/<id-hash>.md` 保存长期记忆正文与证据，文件名由记忆 ID 的 SHA-256 派生，避免路径穿越和重命名抖动。
- SQLite `memory_facts` 保存生命周期、作用域、修订、热度、Markdown 相对路径、文档哈希、正文去重哈希和派生搜索语义卡；新写入不再保存正文、来源摘录或证据推理。
- 旧 SQLite 正文会在打开数据库时批量迁移到 Markdown；Markdown 原子替换成功后才提交 Catalog，Catalog 失败则恢复原文件。
- Markdown 缺失或哈希不一致时停止回读该条记忆，不回退到 SQLite 旧正文。
- `MemoryVectorStore` 新写入只保存由 `MemorySemanticCard` 生成的短语义卡和 float32 BLOB；正文通过 `fact_id` 回读，不再写入向量表的正文列。
- 旧库的 `content` / `embedding_json` 列保留为迁移兼容，旧数据仍可读取；新数据不依赖这两列。
- Markdown 人工编辑导入/对账、召回热度缓存和 HNSW 尚未接入，不能对外宣称已完成；当前采用单卡单文件，分片合并仍是后续扩展。

## 1. 目标与边界

长期记忆不仅保存用户明确要求记住的偏好和约束，也保存经过证据审核、可跨任务复用的项目决策、操作流程、故障经验和历史教训。

本文只设计跨会话长期记忆。`ConversationHistoryCompactor` 继续负责当前线程的消息窗口，`SessionMemory` 继续负责当前任务运行态，二者不会自动把全部对话写入长期记忆。

## 2. 组件职责

| 组件 | 权威范围 | 主要职责 |
| --- | --- | --- |
| Markdown | 记忆正文与证据 | 保存完整、可审核、可版本管理的记忆卡 |
| SQLite Catalog | 状态与目录 | 保存作用域、生命周期、修订、热度、文件定位和索引状态 |
| SQLite FTS5 | 派生索引 | 对标题、摘要、实体、错误码和结论执行 BM25 检索 |
| SQLite Vector | 派生数据 | 保存语义记忆卡、embedding、模型版本和内容哈希 |
| HNSW | 可重建索引 | 对 SQLite 中的向量执行近似近邻 Top-K 检索 |

Markdown 与 SQLite 都是必需组件，但不对同一字段形成双重权威：Markdown 决定正文内容，SQLite 决定运行时状态和定位。FTS5、Vector 与 HNSW 都可从二者重建。

## 3. 记忆类型

- `PREFERENCE`：用户稳定偏好。
- `CONSTRAINT`：用户或项目强约束。
- `FACT`：经过证据确认的稳定事实。
- `DECISION`：技术选型及其适用范围。
- `PROCEDURE`：可复用操作流程。
- `LESSON`：问题、根因、处理方案和验证结果组成的历史经验。

小模型只输出候选记忆和证据引用。`MemoryManager` 负责标识分配、脱敏、作用域、去重、冲突、审核、修订和最终写入，小模型不能直接修改 Markdown、SQLite 或索引。

## 4. Markdown 记忆卡

经验型记忆不能只保存一句摘要，至少包含：

```markdown
## MEM-20260830-001-R1

- 类型：LESSON
- 作用域：project:DevCLI
- 状态：ACTIVE
- 置信度：HIGH
- 主题：长上下文压缩丢失高密度精确事实
- 场景：未来查询未知，历史包含大量独立键值事实
- 根因：使用有限摘要承担任意事实检索
- 结论：工作摘要与事实存储分离，查询时召回原始证据
- 验证：记录数据版本、命令、报告路径和结果
- 来源：runId、消息序号或工具证据引用
```

修改采用追加新修订、旧修订标记 `SUPERSEDED` 的方式，不通过移动块或重排文件表达热度。

## 5. Markdown 分片

```text
memory/
├── explicit/
│   ├── preferences.md
│   ├── constraints.md
│   └── profile.md
├── experience/
│   └── <project>/<yyyy-mm>/part-0001.md
├── project/
│   └── <project>/decisions.md
└── archive/
    └── <yyyy-mm>/part-0001.md
```

分片同时受条目数和文件大小限制，初始建议每片 200 至 500 条且不超过 4 MB。SQLite 使用 `memory_id + revision -> file + heading + content_hash` 定位，不使用易失效的字节偏移量。

如果允许人工编辑 Markdown，文件监听器必须根据内容哈希生成导入作业，经同一校验、冲突和索引流水线提交；不能绕过 `MemoryManager` 直接覆盖运行时状态。

## 6. SQLite 模型

Catalog 至少保存：

```text
memory_id, revision, kind, scope_type, scope_key
status, superseded_by, confidence, importance, pinned
file, heading, content_hash
recall_count, last_recalled_at
validated_use_count, last_validated_at
index_state, created_at, updated_at
```

向量表只保存派生语义内容，不复制完整 Markdown 正文：

```sql
CREATE TABLE memory_vectors (
    memory_id       TEXT PRIMARY KEY,
    semantic_text   TEXT NOT NULL,
    embedding       BLOB NOT NULL,
    dimensions      INTEGER NOT NULL,
    embedding_model TEXT NOT NULL,
    content_hash    TEXT NOT NULL,
    indexed_at_ms   INTEGER NOT NULL
);
```

`semantic_text` 由程序从结构化记忆卡生成：

```text
类型 + 主题 + 场景 + 根因 + 结论 + 关键实体 + 作用域
```

完整日志、长原文和无关过程不进入 embedding。向量使用 `float32 BLOB`，不再使用 JSON 数组。

## 7. 写入与一致性

```text
Curator 候选
-> MemoryManager 校验
-> SQLite 写 PENDING_MD 和目标分片
-> 临时文件写入并原子替换 Markdown 分片
-> SQLite 标记 ACTIVE，同时写 index outbox
-> 后台生成 FTS/Vector/HNSW
-> 成功标记 INDEXED；失败保持 PENDING_INDEX 并有界重试
```

启动时执行一致性对账：

- Markdown 缺失或哈希不一致的 ACTIVE 条目停止注入并进入审核。
- 缺失、模型版本过期或内容哈希变化的向量重新生成。
- 没有 Catalog 条目的孤儿向量和 FTS 记录删除。
- HNSW generation 与 SQLite generation 不一致时重建或增量补齐。

## 8. 检索与分层缓存

每次请求都查询全量索引，不让模型判断是否需要“深层检索”：

```text
scope/status/type 过滤
├── FTS5/BM25 Top-N
└── HNSW Vector Top-N
        -> RRF 合并
        -> confidence/freshness/scope 校正
        -> memoryId Top-K
        -> L1 命中或回读 Markdown
        -> 注入 3 至 10 条完整记忆卡与证据
```

三级记忆只表示注入和缓存层级：

- `L0 Pinned`：受 Token 预算约束的核心规则；现有 `RuleContext` 优先承接。
- `L1 Hot Working Set`：约 200 条已解析 `MemoryEntry` 的内存缓存，不等于 Prompt 内容。
- `L2 Long-term Memory`：完整 Markdown + SQLite 全量索引。

Hot Score 只用于缓存准入和淘汰，不参与最终相关性排序。普通召回不能提高事实置信度，只有用户确认或工具验证才增加 `validated_use_count`。召回计数在内存聚合后批量写回 SQLite，避免每次命中产生写放大。

## 9. 迁移顺序

1. 增加 Catalog 定位、内容哈希、索引状态和 outbox，不改变现有检索行为。
2. 增加 Markdown 写入与 SQLite/Markdown 哈希校验，迁移后清空 SQLite 正文兼容列。（已完成单卡单文件版本）
3. 增加 FTS5/BM25 和 RRF，替换内存 O(N) 关键词扫描。
4. 把向量改为 `semantic_text + float32 BLOB`，接入现有 HNSW 能力。
5. 增加 L1 Hot Working Set，取消启动时 `loadAll()`。
6. 数据迁移验证完成后，删除 SQLite 正文兼容列。

每一步都必须支持旧库迁移和回滚，不允许一次性切换双存储协议。

## 10. 验证指标

- LongMemEval 冻结样本：`Recall@5`、`MRR@5`、错误或过期记忆注入率。
- 检索消融：Keyword、Vector、RRF、RRF + 生命周期校正。
- 10K/50K 规模：启动时间、常驻内存、p50/p95 检索延迟。
- L1 缓存：命中率、回读 Markdown 次数；开启和关闭缓存时 Recall 必须一致。
- 一致性故障：Markdown 写失败、向量服务不可用、进程中断、索引模型升级和人工编辑。

在这些验证完成前，不把 Hot Working Set、Markdown 真值源或 HNSW 迁移描述为已交付能力。
