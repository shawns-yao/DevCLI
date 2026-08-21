# 上下文压缩设计

## 目标

在不破坏工具消息协议和关键约束的前提下，控制完整 LLM 请求的上下文规模。压缩对象不是旁路笔记，而是实际发送给模型的 `conversationHistory`。

## 请求预算

每次模型调用前，压缩阈值按以下预算计算：

```text
基础触发阈值 = min(模型上下文窗口 × 压缩比例, 模型上下文窗口 - 模型输出预留)
conversationHistory 有效触发阈值 = 基础触发阈值 - 当前工具定义 token
```

`ContextProfile.historyTriggerTokens(...)` 负责扣除额外请求内容，`TokenBudget.estimateToolDefinitionsTokens(...)` 负责估算当前工具定义。system prompt、当轮记忆、Skill 和 MCP 动态内容已经位于当前 history 或当轮消息中，不再单独创建第二份状态。

## 原文尾部

`ConversationHistoryCompactor` 使用 token 预算从最新消息向前填充原文尾部，并在 `user` 边界切分，避免拆开 assistant tool call 与 tool result。

如果边界对齐后仍然超出原文预算：

1. 继续把最旧的保留轮次前移到摘要区。
2. 如果只剩一条大消息无法继续切分，则保留头尾并将中间内容落盘。
3. 在 history 中写入 `microcompact_boundary`，保存 `storedPath`、消息类型和原始字符数。

普通 user/assistant 大消息和 tool result 使用不同落盘目录，但都通过 `read_file` 具备恢复路径。单条多模态消息不做文本截断，按其内容部件的 token 估算参与预算治理。

## 摘要策略

- 首次压缩：Map-Reduce 全量摘要。
- 后续压缩：模型只根据上一摘要和新增消息提出 `ADD / UPDATE / RESOLVE / SUPERSEDE / EXPIRE / DELETE` 操作，程序校验后增量更新，不让模型自由重写完整摘要。
- 固定结构：摘要始终保留九段；生命周期是每条事实的元数据，不新增或替换分段。稳定决策、活跃上下文、未解决事项、最终结果、已覆盖事实因此可以使用不同保留策略。
- 会话预摘要：如果覆盖当前旧消息前缀且未过期，优先复用；当前切分范围扩大时，使用前缀摘要继续做增量摘要，不因 token 边界变化重复处理全部历史。
- 周期性治理：默认每 5 次成功压缩执行一次生命周期 GC；清理过期事实和有界的覆盖审计，但不二次压缩稳定决策和未解决事项。
- 更新语义：同主题新值覆盖旧值，完成事项从活跃段迁移为最终结果；旧值退出有效 Prompt，仅保留有界审计。操作格式错误时保留上一版摘要。
- 摘要提交前：执行 `CompactionSemanticGuard`，恢复缺失的命令、路径、版本、配置、禁止项和其他保护约束。

## 触发时机

Agent 在每次模型迭代前执行轻量 token 检查。未超过完整请求预算时不调用摘要模型；超过后才执行 microcompact、摘要或降级截断。模型回答追加到 history 后，如果没有下一次迭代，压缩会延迟到下一次请求前执行，避免在不需要继续调用模型时额外发起摘要请求。

## 已知边界

- token 数是 DevCLI 统一估算器的结果，不等同于每个 Provider 的原生 tokenizer。
- 生命周期 GC 只能治理已经进入结构化摘要的事实；此前已丢失且没有落盘引用的内容无法凭空恢复。
- 普通用户直接粘贴的大内容可以通过 `storedPath` 重新读取，但模型是否主动调用 `read_file` 仍取决于当前任务和工具选择。
- 完整请求预算仍依赖工具定义、Skill 和动态 MCP 内容在调用前可被当前 registry 正确枚举；外部 Provider 的隐藏 token 计费规则不在本地估算范围内。
