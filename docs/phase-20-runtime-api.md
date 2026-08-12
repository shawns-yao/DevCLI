# 第 20 期：异步后台任务 + Runtime API

> 当前状态：MVP 已落地。第 20 期补齐无头与后台执行入口；第 21 期 图片输入 已独立完成，不依赖本期 API。

## 已交付

### 后台任务

- `DurableTaskManager`：统一 RunStore 的后台提交器与 Worker，不再拥有独立任务状态机
- 默认数据库：`~/.devcli/runtime/runtime.db`；首次启动会从旧 `~/.devcli/tasks/tasks.db` 幂等迁移
- 生命周期：
  - `enqueued`
  - `running`
  - `completed`
  - `failed`
  - `canceled`
- Worker Pool：默认 2 个后台 worker，可用 `DEVCLI_TASK_WORKERS` 或 `-Ddevcli.task.workers` 覆盖
- 进程启动时只对 lease 已过期的 `running` Run 标记 `recovery_required`；新 Worker 认领后创建下一次 Attempt，禁止无条件重试副作用
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
- `GET /v1/threads/{id}/events`：以 SSE 格式回放事件

事件类型：

- `thread.created`
- `turn.started`
- `message.delta`
- `turn.completed`
- `turn.failed`

## 当前边界

- Runtime API MVP 是事件回放式 SSE，不做长连接持续阻塞推送
- 后台任务 runner 使用 headless ReAct Agent，不复用交互式 TUI 的 HITL 输入
- 后台任务取消通过线程中断 + 状态标记实现；正在进行的远端 LLM HTTP 调用能否立即停止取决于底层 client 边界
- Runtime API 当前不模拟完整 OpenAI Assistants API schema，只保留兼容方向的 threads / turns / events 主路径

## 验证

```bash
mvn test -Dtest=DurableTaskManagerTest,RuntimeApiServerTest,CliCommandParserTest
```

建议回归：

```bash
mvn test -Pquick
mvn test
mvn -q clean package -DskipTests
DEVCLI_RUNTIME_API_KEY=test java -jar target/devcli-1.0-SNAPSHOT.jar serve --http --port 0
```
