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

默认尾部预算为当前模型窗口的 8%，并夹在 4K～32K 之间；同时不超过当次 history 触发阈值的一半。切换模型后重新计算。显式传入的尾部 Token 预算保持原语义，供调用方和实验固定参数。

尾部原文预算、模型摘要预算和压缩后总 history 预算是三个独立概念。尾部预算只决定保留多少最近原文，不代表最终压缩结果的总大小；语义摘要必须单独记录其输入、输出和事实保真结果。

公开评测日志分别记录 `triggerTokens`、`tailBudgetTokens`、`summaryInputBudgetTokens`、`summaryTokens`、`retainedTailTokens` 和 `postCompactionHistoryTokens`。其中 Token 体积只解释压缩结构，效果结论仍以结构化关键事实保留率和压缩后 SWE-bench 官方结果为准。

如果边界对齐后仍然超出原文预算，只继续把最旧的保留轮次前移到摘要区；规则层不得截断 user、assistant、任务状态或决策文本。

## Microcompact

Microcompact 的职责是旧 `tool_result` 垃圾回收，不是会话语义压缩：

- 只处理 `role=tool` 的历史消息，不修改 user 或 assistant 消息。
- 默认保留最近 4 个工具结果；可通过 `DEVCLI_CONTEXT_MICROCOMPACT_KEEP_RECENT_TOOL_RESULTS` 调整，不沿用外部产品的固定阈值。
- `save_memory`、`confirm_memory`、`list_memory`、记忆型 MCP、`web_search`、`web_fetch` 和仍包含失败信号的命令结果默认保护。
- 额外排除工具可通过 `DEVCLI_CONTEXT_MICROCOMPACT_EXCLUDE_TOOLS` 配置，支持精确名称和末尾 `*` 前缀匹配。
- 被回收的完整结果写入项目 `.devcli/microcompact_tool_outputs/`；`storedPath` 使用项目相对路径，可由隔离工作区中的 `read_file` 读取。
- 指标记录压缩前后总 Token、各角色 Token、按工具名称清理的 Token 和工具结果数量。
- Microcompact 后仍达到触发阈值时，继续执行模型语义压缩。

不采用 Anthropic 专属 `cache_edits`，避免破坏 Luna 和其他 OpenAI-compatible Provider 的统一链路。近期保留数量与触发策略由 DevCLI 自身配置和公开数据实验决定，不复制逆向分析中的 5 个结果或 60 分钟规则，也不复制官方 API 的默认 3 个结果和 100K 阈值。

## 摘要策略

- 首次压缩：完整摘要请求能放入模型输入预算时只调用一次；确实超出时才 Map-Reduce。取消 60,000 字符分片上限，按完整提示词的 Token 估算动态分片，优先在消息文本边界切分，不切断 Unicode 代理对。
- 后续压缩：每批按“已有摘要 + 增量提示词 + 新增消息”的完整请求预算选择输入，提出 `ADD / UPDATE / RESOLVE / SUPERSEDE / EXPIRE / DELETE` 操作，程序校验后更新滚动摘要，再处理下一批；不先生成一堆独立摘要后无界拼接。
- 归并：按实际 Reduce 请求 Token 预算分组，最多八片不是窗口保证。不能归并、空分片或增量协议无效时，本次压缩失败，不以旧摘要冒充已吸收新增历史。
- 固定结构：新摘要保留六段；旧九段兼容读取但不携带任务状态投影。生命周期是每条事实的元数据，不新增或替换分段。
- 会话预摘要：如果覆盖当前旧消息前缀且未过期，优先复用；当前切分范围扩大时，使用前缀摘要继续做增量摘要，不因 token 边界变化重复处理全部历史。
- 周期性治理：默认每 5 次成功压缩执行一次生命周期 GC；清理过期事实和有界的覆盖审计，但不二次压缩稳定决策和未解决事项。
- 更新语义：同主题新值覆盖旧值，完成事项从活跃段迁移为最终结果；旧值退出有效 Prompt，仅保留有界审计。操作格式错误时保留原 history，不提交已处理的部分批次。
- 摘要提交前：执行 `CompactionSemanticGuard`，恢复缺失的命令、路径、版本、配置、禁止项和其他工作状态约束。它不承担未来随机事实检索，也不能根据尚未出现的查询选择事实。
- 超限重试：Provider 报告上下文超限时，将单次摘要输入预算乘以 0.75 后重新分片，最多重试三次；不再删除最旧 20% 轮次。学习到的预算仅作用于当前 compactor，切换模型时重置。原有连续失败熔断及带显式提示的紧急截断策略不在本次替换范围内。

## 触发时机

Agent 在每次模型迭代前执行轻量 token 检查。未超过完整请求预算时不调用摘要模型；超过后才执行 microcompact、摘要或降级截断。模型回答追加到 history 后，如果没有下一次迭代，压缩会延迟到下一次请求前执行，避免在不需要继续调用模型时额外发起摘要请求。

## 已知边界

- token 数是 DevCLI 统一估算器的结果，不等同于每个 Provider 的原生 tokenizer。
- 提示词和受保护的旧摘要本身已经超过预算时不能继续塞入新增消息；返回压缩失败，不暗中删除稳定事实。
- 生命周期 GC 只能治理已经进入结构化摘要的事实；此前已丢失且没有落盘引用的内容无法凭空恢复。
- 单条用户消息本身已经超过模型窗口时，Microcompact 不会删除它；该情况需要入口限制、附件化或显式语义处理，不能伪装成工具结果 GC。
- 完整请求预算仍依赖工具定义、Skill 和动态 MCP 内容在调用前可被当前 registry 正确枚举；外部 Provider 的隐藏 token 计费规则不在本地估算范围内。
- 高密度、未来查询未知且要求精确恢复的独立事实属于长期记忆检索问题，不适合用 bounded summary 证明。RULER NIAH 多键任务只能作为该边界的诊断或未来记忆检索评测，不能作为摘要质量的正式成绩。
