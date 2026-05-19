# 第 12 期开发任务：长上下文工程

> 本期目标是让 PaiCLI 的运行策略随模型上下文窗口变化，而不是继续使用固定 300K token 预算和固定 RAG topK。

## 1. 已交付范围

- `LlmClient` 能力声明：
  - `maxContextWindow()`
  - `supportsPromptCaching()`
  - `promptCacheMode()`
- 模型默认能力：
  - GLM-5.1：`200000` window，`glm-prompt-cache`
  - DeepSeek V4：`1000000` window，`automatic-prefix-cache`
- `ContextProfile`：
  - 不再按 short / balanced / long 做行为分档
  - 所有模型统一按 `maxContextWindow` 派生预算和阈值
- `AgentBudget` 动态预算：
  - 默认 `80% * maxContextWindow`
  - 仍可用 `-Dpaicli.react.token.budget=...` 覆盖
- Memory 策略：
  - 所有 window 都保留 `ConversationHistoryCompactor` 自动摘要压缩
  - 触发阈值统一为 `maxContextWindow × 90%`
  - `WorkingMemory` 是当前会话工具证据 / 任务状态 / 临时事实的 prompt 派生视图，不是压缩器
- RAG 策略：
  - `search_code` 未传 `top_k` 时按上下文模式自适应
  - short=5，balanced=10，long=20
- MCP resources 索引：
  - long 模式下把已知 resources 的 URI / 名称 / 描述 / mimeType 注入 system prompt
  - 不注入 body，需要正文时仍调用 read resource 或用户显式 `@server:protocol://path`
  - ReAct、Plan-and-Execute、Multi-Agent SubAgent 都接入同一索引供应器
- Token 可见化：
  - 输出当前已用 token / 动态预算 / window / cached input / 估算成本
  - `ChatResponse` 增加 `cachedInputTokens`
  - OpenAI-compatible SSE usage 中兼容解析常见 cached token 字段
- `/context` 扩展：
  - 显示上下文模式、window、动态预算、RAG topK、压缩开关、prompt cache 模式、MCP resource 自动索引状态
- Banner 升级到 `v12.0.0`，标语为 `Long-Context Agent CLI`

## 2. 明确不做

- 不新增 Anthropic / Claude provider
- 不实现 Anthropic `cache_control` 块
- 不向 GLM / DeepSeek 请求体注入未确认兼容的私有 cache 字段
- 不把 MCP resource body 自动塞进 system prompt
- 不改变 `pom.xml` 的 Maven 产物版本，Jar 仍是 `paicli-1.0-SNAPSHOT.jar`

## 3. 核心文件

```text
src/main/java/com/paicli/context/
├── ContextMode.java
├── ContextProfile.java
└── TokenUsageFormatter.java
```

集成点：

- `LlmClient` / `GLMClient` / `DeepSeekClient`：模型能力声明
- `AbstractOpenAiCompatibleClient`：解析 cached input tokens
- `AgentBudget`：按模型上下文窗口动态计算 token 预算
- `MemoryManager` / `TokenBudget` / `WorkingMemory` / `ConversationHistoryCompactor`：长上下文策略、工作记忆视图与 messages 压缩
- `ToolRegistry`：`search_code` 默认 topK 自适应
- `McpServerManager`：生成 MCP resources prompt index
- `Agent` / `PlanExecuteAgent` / `AgentOrchestrator` / `SubAgent`：注入长上下文策略与资源索引
- `Main`：Banner、模型切换后的上下文策略提示、Plan/Team resource index 供应器

## 4. 验证

新增或扩展测试：

- `ContextProfileTest`
- `AgentBudgetTest`
- `MemoryManagerTest`
- `McpServerManagerTest`

最终验证命令：

```bash
mvn clean package
```

通过结果：`347 tests, 0 failures, 0 errors, 0 skipped`。
