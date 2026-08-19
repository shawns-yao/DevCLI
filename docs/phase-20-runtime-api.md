# 第 20 期：异步后台任务 + Runtime API

> 当前状态：后台任务与 Runtime API 已落地；本期后续补强了可续传实时 SSE 和 RunStore 恢复证据登记。第 21 期图片输入已独立完成，不依赖本期 API。

## 已交付

### 后台任务

- `DurableTaskManager`：后台提交与本地 Worker，运行状态委托统一 `RunStore`
- 默认数据库：`~/.devcli/runtime/runtime.db`
- 旧数据库：`~/.devcli/tasks/tasks.db` 通过只读连接导入，重复 id 不覆盖
- 生命周期：
  - `enqueued`
  - `running`
  - `completed`
  - `failed`
  - `canceled`
- Worker Pool：默认 2 个后台 worker，可用 `DEVCLI_TASK_WORKERS` 或 `-Ddevcli.task.workers` 覆盖
- 进程启动时把上次残留的 `running` 任务恢复为 `enqueued`
- 恢复保留 attempt，并记录 `process_restarted_before_terminal_state` 原因
- CLI 命令：
  - `/task` 或 `/task list [N]`
  - `/task add <任务内容>`
  - `/task cancel <task_id>`
  - `/task log <task_id>`

### Runtime API

实现位于 `src/main/java/com/devcli/runtime/api/`，使用 JDK 内置 `HttpServer`，不引入 Spring / Javalin。

启动：

```bash
DEVCLI_RUNTIME_API_KEY=your_local_api_key \
java -jar target/devcli-1.0-SNAPSHOT.jar serve --http --port 8080
```

安全策略：

- 仅监听 `127.0.0.1`
- 必须配置 `DEVCLI_RUNTIME_API_KEY` 或 `-Ddevcli.runtime.api.key`
- 请求头支持：
  - `Authorization: Bearer <key>`
  - `X-DevCLI-API-Key: <key>`

端点：

- `POST /v1/threads`：创建 thread
- `POST /v1/threads/{id}/turns`：提交一轮 Agent 输入，异步执行
- `GET /v1/threads/{id}`：读取版本化会话投影，包含标题、状态、事件游标、Token、费用和工具/Hook 审计统计
- `GET /v1/threads/{id}/events`：以可续传的 chunked SSE 格式回放并实时推送事件
- `GET/POST /v1/threads/{id}/branches`：列出或创建持久分支
- `POST /v1/threads/{id}/branches/{branchId}/activate`：切换活动分支

事件类型：

- `thread.created`
- `turn.started`
- `model.context`：每次模型请求前的实际消息快照，包含消息来源；图片正文不持久化
- `model.message`：执行内核追加到模型历史的单条消息
- `model.usage`
- `tool.calls` / `tool.results`：包含结构化展示契约
- `hook.call` / `hook.result`
- `message.delta`
- `turn.completed`
- `turn.failed`

### 实时 SSE 语义

- 响应为 `text/event-stream`，先 replay 当前活动分支可见事件，再 tail 后续事件；事件按 `id`、`event`、`data` 三行编码，并以空行分隔。
- `after` 查询参数和 `Last-Event-ID` 请求头都只接受非负游标，初始游标取两者中较大的合法值；缺失或非法值按 `0` 处理，适合断线重连。
- 每次数据库读取最多返回 128 条。事件写入在 SQLite commit 后才唤醒等待连接，活动分支查询按现有 lineage 规则过滤，避免发送不可见分支事件。
- 没有新事件时发送 `: heartbeat` SSE 注释，不推进游标。heartbeat 默认 15 秒，可通过 `devcli.runtime.api.sse.heartbeat.seconds` 或 `DEVCLI_RUNTIME_API_SSE_HEARTBEAT_SECONDS` 调整。
- 连接写入使用阻塞 socket 形成 backpressure；每个连接占用一个 HTTP worker，当前本地 Runtime 的并发受 `devcli.runtime.api.http.threads`（默认 16）约束，不代表生产吞吐上限。
- 客户端断开、服务关闭、线程中断或底层写入失败都会结束连接并清理订阅；服务端不保留每客户端无界队列。

### RunStore 恢复证据引用

`runtime_recovery_evidence` 是恢复产物的 metadata-only 索引，不复制 checkpoint、Patch Journal 或 Side-Git 内容，也不把秘密写入 SQLite。每条 `RecoveryEvidenceRef` 包含 `runId`、`threadId`、`branchId`、`kind`（`CHECKPOINT` / `PATCH_JOURNAL` / `SIDE_GIT`）、稳定 `logicalKey`、规范化引用、可选 SHA-256、状态、创建/更新时间和版本；同一 `(run_id, kind, logical_key)` 幂等 upsert，并拒绝非法状态迁移。

CLI 在执行开始前生成稳定 `runId`，`SessionTree` 记录该 ID；`RunContext` 注入 run-scoped evidence sink，checkpoint 保存/删除、Patch Journal prepare/terminal/rollback、Side-Git pre/post 都会登记引用。证据写入失败只记录 warning，不影响本地产物或本地恢复流程。回滚不完整时保留 journal；在真实 reconcile 后，`FAILED` 引用才可提升为 `COMPLETED` 或 `ROLLED_BACK`。

## 当前边界

- Runtime API 仍只绑定 `127.0.0.1`；每个 SSE 连接占用一个 HTTP worker，并发能力受本地 HTTP 线程配置约束，不是面向公网的生产推送服务
- 后台任务 runner 使用 headless ReAct Agent，不复用交互式 TUI 的 HITL 输入
- 工具取消使用调用级协作信号；命令进程树、Web HTTP Call 和 MCP 请求会收到取消，MCP 同时发送 `notifications/cancelled`
- Runtime API 当前不模拟完整 OpenAI Assistants API schema，只保留兼容方向的 threads / turns / events 主路径
- 事件日志是会话恢复事实来源；`model.context` 只从已完成 turn 重放。checkpoint 与会话投影是可重建缓存，损坏时回退事件日志；Recovery refs 只记录本地产物元数据，不替代产物内容
- CLI `/session` 复用相同 thread/branch/event 存储；切换会话树不修改 Side-Git、PatchSet 或工作区文件

## 验证

```bash
mvn test -Dtest=SqliteRunStoreTest,DurableTaskManagerTest,RuntimeApiServerTest,SessionTreeServiceTest,CliCommandParserTest
```

建议回归：

```bash
mvn test -Pquick
mvn test
mvn -q clean package -DskipTests
DEVCLI_RUNTIME_API_KEY=test java -jar target/devcli-1.0-SNAPSHOT.jar serve --http --port 0
```
