# 第 20 期：异步后台任务 + Runtime API

> 当前状态：MVP 已落地。第 20 期补齐无头与后台执行入口；第 21 期 图片输入 已独立完成，不依赖本期 API。

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
- `GET /v1/threads/{id}/events`：以 SSE 格式回放事件
- `GET/POST /v1/threads/{id}/branches`：列出或创建持久分支
- `POST /v1/threads/{id}/branches/{branchId}/activate`：切换活动分支

事件类型：

- `thread.created`
- `turn.started`
- `model.context`：初始化或模型请求前的窗口快照，包含消息来源和图片内容；iteration=0 表示初始化
- `model.message`：执行内核追加到模型历史的单条消息
- `model.usage`
- `tool.calls` / `tool.results`：包含结构化展示契约
- `tool.result.recorded`：审查后、尺寸治理前的完整返回结果、图片、修改资源和已有产物引用
- `agent.event`：子 Agent 身份、事件类型及事件正文；不混入父模型上下文
- `hook.call` / `hook.result`
- `message.delta`
- `turn.completed`
- `turn.failed`
- `context.compacted`：压缩来源事件范围、来源 hash、projection hash 和模式
- `thread.checkpoint.created` / `thread.checkpoint.failed`

## 当前边界

- Runtime API MVP 是事件回放式 SSE，不做长连接持续阻塞推送
- 后台任务 runner 使用 headless ReAct Agent，不复用交互式 TUI 的 HITL 输入
- 工具取消使用调用级协作信号；命令进程树、Web HTTP Call 和 MCP 请求会收到取消，MCP 同时发送 `notifications/cancelled`
- Runtime API 当前不模拟完整 OpenAI Assistants API schema，只保留兼容方向的 threads / turns / events 主路径
- 事件日志是会话恢复事实来源。`ConversationReplay` 重放窗口快照和后续消息，包括未完成及失败回合；缺失工具结果生成状态未知的配对，不重放工具。压缩只改变窗口，原消息、返回结果和图片留在日志；生产者输出配额仍有效，旧日志缺失的正文无法恢复。checkpoint 与会话投影是可重建缓存，checkpoint 保持现有来源和快照校验
- `tool.results` 的 `elapsed_millis` 和 `exit_code` 来自结构化执行元数据；兼容调用没有命令元数据时才使用明确的未知值，不从展示文本猜测退出码
- CLI `/session` 复用相同 thread/branch/event 存储；切换会话树不修改 Side-Git、PatchSet 或工作区文件
- CLI 和 `-p` 在执行期间实时记录；`-p` JSON 的 thread_id 可用于定位日志。事件发布器绑定开始时的分支，子任务后续事件不跟随活动分支切换

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
