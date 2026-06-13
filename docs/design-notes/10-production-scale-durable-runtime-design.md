# Production Scale And Durable Runtime Design

## Status

This is a future design document. It records issues exposed by interviews and does not change current runtime behavior.

## 暴露的问题

生产级场景面追问：

- 万人、多租户、千万行代码如何索引？
- 每天数万次 Git Push，`Embedding（向量化）` 成本怎么控？
- 向量库和图数据库分离后，怎么避免 N+1 查询？
- FastAPI / Spring Boot 后端如何执行长任务？
- Worker OOM 或缩容重启后，Agent 状态怎么恢复？

## 当前已有

- DevCLI 是本地 Java Agent CLI。
- `RuntimeApiServer（运行时 API 服务）` 提供本地 API 能力。
- `DurableTaskManager（持久任务管理器）` 已有本地任务管理雏形。
- RAG 当前使用本地 SQLite。
- Multi-Agent 当前在单进程内编排。

## 不足

- 没有事件驱动 ingestion pipeline。
- 没有分布式向量库 / 图数据库联合查询。
- 没有租户级 `IndexCatalog（索引目录）`。
- 没有 tenant-aware permission filtering。
- 没有多节点 Agent checkpoint 恢复。
- 没有分布式 Worker lease 和 fencing token。

## 怎么修改

### 1. 分布式索引摄取

```text
Git webhook
-> Kafka/Pulsar
-> diff parser
-> DirtyFileQueue
-> chunk hash
-> embedding cache
-> vector index
-> graph index
-> IndexCatalog
```

热点文件策略：

- debounce.
- event coalescing.
- branch priority.
- active Agent priority.
- tenant token bucket.

### 2. Embedding Cost Control

缓存层：

- global content_hash cache.
- tenant cache.
- repo cache.
- hot chunk cache.

降级：

- embedding lag 时使用 keyword + symbol graph。
- 后台补齐向量。
- 查询结果标记 freshness。

### 3. 图向量联合查询

避免 N+1：

```text
vector topN with metadata filter
-> batch graph expansion(seedIds, depth, relationTypes)
-> server-side BFS and limit
-> fusion/rerank
```

优化：

- batch API.
- materialized subgraph.
- precomputed adjacency.
- metadata filtering pushdown.

### 4. Durable Agent Runtime

HTTP 层只做 `ControlPlane（控制面）`。

长任务进入：

- queue worker.
- Kubernetes Job.
- durable task store.
- event log.
- artifact store.

持久化：

- task_id.
- plan_version.
- dag_state.
- current_step.
- tool_calls.
- memory_summary.
- patch_refs.
- artifact_refs.
- workspace_snapshot_id.
- retry_count.

### 5. Crash Recovery

```text
worker heartbeat lost
-> lease timeout
-> task returns to pending
-> new worker loads checkpoint
-> rebuild sandbox/worktree
-> replay accepted PatchSet
-> continue next pending step
```

防重复：

- idempotent patch id.
- tool call id.
- fencing token.
- compare-and-swap state update.

## 设计边界

- 本地 CLI 不引入企业级依赖。
- 不让 Agent 状态只存在内存。
- 不做 per-edge remote graph query。
- 不跨租户共享未隔离索引。
- 不把 long context 当成分布式索引替代品。

## 验收标准

- ingestion lag 可观测。
- embedding cache hit rate 可观测。
- 每次 query 都带 tenant/repo/branch 过滤。
- graph expansion 是 batch API。
- Worker crash 后能从 checkpoint 恢复。
- 重复执行不会重复应用 PatchSet。

## 文字解释

面试时可以这样讲：

> DevCLI 当前是本地 CLI，不能直接说已经具备企业级平台能力。如果做万人规模 Code RAG 服务，索引侧要事件驱动：Git webhook 进 Kafka/Pulsar，diff parser 只处理变更文件，chunk hash 命中就复用 embedding cache，热点文件做 debounce 和 coalescing。查询侧不能向量 topK 后一条条查图，那会 N+1，要提供 batch graph expansion API，把 BFS、关系过滤、limit 下推到图服务。执行侧 HTTP 只做 control plane，Agent 长任务进入 durable worker，每个 DAG step、tool call、PatchSet、memory summary 都 checkpoint，Worker 挂了以后靠 lease timeout 和 fencing token 恢复。

