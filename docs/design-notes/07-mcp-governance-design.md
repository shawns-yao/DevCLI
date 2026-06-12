# MCP Governance Improvement Design

## Status

This is a future design document. It records issues exposed by interviews and does not change current runtime behavior.

## 暴露的问题

MCP 面试暴露的问题：

- 为什么引入 `MCP（模型上下文协议）`？
- schema 各不相同怎么适配？
- MCP server 参数校验失败，模型死循环怎么办？
- resources 怎么进入上下文？
- 外部工具安全边界在哪里？

## 当前已有

- MCP dynamic tools.
- schema 清洗。
- resources mention。
- `ToolRegistry（工具注册表）` / `HITL（人类审批）` / `AuditLog（审计日志）` 统一入口。
- MCP 工具以 `mcp__server__tool` 形式接入。

## 不足

- MCP 工具调用的循环熔断不够系统化。
- MCP resource 和 RAG 的索引融合还可以加强。
- MCP server 的权限分级还可以细化。
- 外部工具错误分类和降级路径不够完整。
- MCP 工具能力声明和风险等级还没有统一治理模型。

## 怎么修改

### 1. MCP Tool Error Taxonomy

建立 `McpErrorTaxonomy（MCP 错误分类）`：

```text
SCHEMA_VALIDATION_ERROR
AUTH_ERROR
RATE_LIMIT_ERROR
REMOTE_RUNTIME_ERROR
UNSUPPORTED_CAPABILITY
POLICY_DENIED
RESOURCE_NOT_FOUND
RETRYABLE_NETWORK_ERROR
```

不同错误走不同处理：

- schema 错误允许有限自修正。
- auth 错误直接停止并提示配置。
- rate limit 降级或排队。
- policy denied 不允许 LLM 继续绕过。

### 2. MCP Call Budget

每个 Agent / task / tool 维度限制：

- max calls.
- max retries.
- max token feedback.
- max duration.
- max same-error repeats.

达到预算后返回结构化失败：

```text
MCP_TOOL_EXHAUSTED
tool: mcp__github__search
reason: same schema error repeated 3 times
```

### 3. Resource Index Integration

MCP resources 进入两类索引：

- `ContextIndex（上下文索引）`: 文档、配置、外部知识。
- `CodeIndex（代码索引）`: 可映射到项目文件或代码片段的资源。

资源展开时记录：

- server.
- uri.
- version.
- fetched_at.
- permission scope.

### 4. MCP Permission Tier

按 server/tool 分级：

```text
READ_ONLY
PROJECT_READ
PROJECT_WRITE
NETWORK_READ
EXTERNAL_WRITE
SECRET_ACCESS
```

高风险工具必须走 HITL 和 AuditLog。

### 5. Schema Normalization Report

MCP schema 清洗后保留报告：

- original schema hash.
- normalized schema hash.
- removed unsupported fields.
- required fields.
- enum constraints.

## 设计边界

- 不让 MCP 绕过 ToolRegistry。
- 不把远程错误原样无限喂给模型。
- 不自动授予外部写权限。
- 不把 resources 当成永久可信事实。

## 验收标准

- MCP 工具有错误分类。
- 同类错误重复会熔断。
- MCP resources 可进入 ContextIndex。
- 每个 MCP tool 有权限等级。
- AuditLog 能追踪 MCP server、tool、arguments、result。

## 文字解释

面试时可以这样讲：

> 我引入 MCP 不是为了多包一层工具调用，而是为了解决工具生态动态扩展和 resources 标准化接入。但 MCP 的风险是外部 server schema 不统一、错误不可控、权限边界复杂。所以生产级要做 `McpErrorTaxonomy（MCP 错误分类）` 和 `MCP call budget（MCP 调用预算）`。schema 错误允许有限自修正，认证错误直接停止，策略拒绝不允许模型绕过。resources 也不能只展开到 prompt，而要带 server、uri、version、permission scope 进入 `ContextIndex（上下文索引）` 或 `CodeIndex（代码索引）`。所有 MCP 工具仍然必须经过 ToolRegistry、HITL 和 AuditLog。

