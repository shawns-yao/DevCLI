# Streaming Event And UI Design

## Status

This is a future design document. It records issues exposed by interviews and does not change current runtime behavior.

## 暴露的问题

TUI / Streaming 面试主要追问：

- LLM token、工具日志、编译输出、Worker 进度怎么同时展示？
- 多 Worker 并行时终端输出怎么不乱？
- 用户输入和后台输出如何避免互相覆盖？
- Web 端要用 SSE、WebSocket 还是 Long Polling？
- CLI 和 Runtime API 是否共用同一套事件模型？

## 当前已有

- `Renderer（渲染器）` 抽象。
- `InlineRenderer（内联渲染器）`。
- JLine 交互升级。
- `BottomStatusBar（底部状态栏）`。
- Runtime API 初步支持后台任务。
- Agent、Planner、Orchestrator 已逐步接入 renderer 输出流。

## 不足

- 事件模型还不够统一。
- 多 Worker 输出缺少独立 channel。
- CLI / Runtime API / Web UI 之间还没有完全共享 `AgentEvent（Agent 事件）`。
- 工具日志、LLM token、状态更新仍可能以文本方式耦合。
- 前端如果解析日志文本，后续维护成本高。

## 怎么修改

### 1. AgentEvent

统一事件：

```text
AgentEvent
- event_id
- run_id
- task_id
- worker_id
- type
- payload
- created_at
```

事件类型：

- `LLM_TOKEN（模型 token）`
- `REASONING_PREVIEW（推理预览）`
- `TOOL_STARTED（工具开始）`
- `TOOL_OUTPUT（工具输出）`
- `TOOL_FINISHED（工具结束）`
- `TASK_STATE_CHANGED（任务状态变化）`
- `REVIEW_RESULT（审查结果）`
- `ERROR（错误）`
- `COST_UPDATE（成本更新）`

### 2. EventBus

Agent 主流程只发布事件，不直接关心 UI。

```text
Agent / Worker / Tool
-> EventBus
-> CLI Renderer
-> Runtime API SSE
-> WebSocket bridge
-> Log store
```

### 3. Worker Channel

多 Worker 并行时：

- 每个 Worker 有 channel。
- Renderer 聚合展示摘要。
- 详细日志按 Worker 展开。
- final transcript 保持顺序稳定。

### 4. Web 推送选择

默认：

- SSE 用于单向执行日志。
- WebSocket 用于需要双向控制的交互任务。
- Long Polling 只作为兼容降级。

### 5. Transcript 与 Live 区隔离

CLI 中区分：

- stable transcript.
- live thinking area.
- bottom status.
- user input line.

任何实时刷新不能覆盖已提交 transcript。

## 设计边界

- 不让 UI 解析自由文本判断状态。
- 不让多个 Worker 直接写 stdout。
- 不把 token 流、工具日志、错误状态混成一种字符串。
- 不为了炫酷 UI 破坏终端稳定性。

## 验收标准

- CLI 和 Runtime API 共用 AgentEvent。
- 多 Worker 并行输出不交叉。
- 工具日志可折叠、可追踪。
- SSE 能实时显示任务状态。
- transcript 不被 live 区覆盖。

## 文字解释

面试时可以这样讲：

> 本地 CLI 的难点不是能不能 print，而是多源输出不能互相污染。生产级我会把 LLM token、工具日志、Worker 状态、Reviewer 结果统一成 `AgentEvent（Agent 事件）`，Agent 主流程只发事件，不直接写 UI。CLI Renderer、Runtime API、Web 前端都消费同一套事件。多 Worker 并行时每个 Worker 有独立 channel，UI 展示摘要，详细日志可展开。Web 端默认用 SSE 推送执行日志，需要双向控制时再用 WebSocket。这样终端和 Web UI 不需要解析自由文本，稳定性更高。

