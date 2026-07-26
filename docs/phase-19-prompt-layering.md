# 第 19 期：Prompt 分层架构

> 当前状态：MVP 已落地。目标是把 ReAct / Plan / Team / Planner 的 system prompt 从 Java 源码中抽离为 Markdown 资源，并支持用户级、项目级覆盖。

## 目标

第 19 期解决的是 prompt 可维护性问题：

1. 调 prompt 不再需要改 Java 源码。
2. 不同职责的 prompt 分层存放，避免一个超长字符串承担所有职责。
3. system prompt 只承载会话级稳定内容，轮次级内容改由消息尾部 append-only 注入，保证 prompt cache 命中。
4. Agent / Plan / Team / Planner 四条路径共用同一个组装器。
5. 用户可以覆盖内置 prompt，项目也可以覆盖 prompt。

## 已落地范围

主模块：

```text
src/main/java/com/devcli/prompt/
├── PromptAssembler.java
├── PromptContext.java
├── PromptMode.java
└── PromptRepository.java
```

内置 prompt 资源：

```text
src/main/resources/prompts/
├── base.md
├── handoff.md
├── approvals/
│   ├── auto.md
│   ├── never.md
│   └── suggest.md
├── context/
│   └── context-management.md
├── modes/
│   ├── agent.md
│   ├── plan.md
│   ├── planner.md
│   ├── team-planner.md
│   ├── team-reviewer.md
│   └── team-worker.md
└── personalities/
    └── calm.md
```

接入点：

- `Agent`：默认 ReAct system prompt 由 `PromptAssembler` 组装。
- `PlanExecuteAgent`：每个 task 的执行 system prompt 由 `PromptAssembler` 组装，并注入 `taskType` / `taskDescription`。
- `SubAgent`：Planner / Worker / Reviewer 三角色按 `PromptMode` 组装。
- `Planner`：Plan-and-Execute 的规划 prompt 由 `PromptAssembler` 组装。

## 组装顺序

`PromptAssembler.assemble()` 组装 **system prompt**，固定按下面顺序：

```text
base
personality
mode
approval
sticky_memory
project_context
context_mgmt
handoff
```

这里只允许放**会话级稳定内容**：

- `stickyMemory`（启动加载，会话内极少变化）
- `externalContext`（MCP resource index）

## Turn Context：轮次级内容的独立通道

`PromptAssembler.assembleTurnContext()` 单独组装按轮次变化的内容：

```text
Turn Context
├── Retrieved Memory   （memoryContext，长期记忆按 query 检索结果）
├── Skills             （skillIndex，按当轮输入过滤）
└── Working Memory     （workingMemory，最近工具证据 / 任务状态 / 恢复状态）
```

调用方把它**前置到当轮 user / task 消息内容**里（与 `prependSkillBodies` 同一模式），不写入 `messages[0]`。

### 为什么不能放进 system prompt

自动前缀缓存按请求 token 前缀命中，而 system prompt 是整个请求的前缀：

- 它一旦有任何字节变化，其后**全部对话历史**都会失配。
- 静态头通常只有几千 token，历史可以到几十万，缓存等于形同虚设。
- 把易变段放在 system prompt **内部尾部并不能解决问题**——失配点之后的一切都不可复用。

### append-only 约束

任何「每次迭代替换某条消息」的方案都保不住前缀缓存：新内容永远追加在尾部，被替换的那条消息一旦位移，其后 token 全部失配。因此轮次级内容只能追加、不能改写。

对应实现约定：

- `Agent` / `SubAgent` / `PlanExecuteAgent` 的 `beforeIteration` **不再重建** `messages[0]`。
- `Agent.refreshSystemPromptIfChanged()` 只在内容真变化时写入。
- fork 执行由 `SubAgent.ForkContext.turnContextSnapshot()` 冻结快照，保证同批并行 Worker 看到同一份内容，且不产生对共享 `WorkingMemory` 的并发读竞争。
- 单轮内新产生的工具证据由 `tool_result` 原文承载；跨压缩边界由 `ConversationHistoryCompactor` 的 `<post_compact_restore>` 兜底。
- 历史里可能同时存在多份 Turn Context 快照，段头显式声明「只有最后一份有效」。

### 契约测试

`AgentPromptCacheStabilityTest` 守四条：

1. `messages[0]` 跨 ReAct 迭代逐字节一致。
2. `messages[0]` 跨 user 轮次逐字节一致。
3. 工具证据不进 system prompt，且能通过 Turn Context 抵达下一轮。
4. 上一次请求的消息序列是下一次请求的前缀（append-only；合法例外只有上下文压缩）。

已知例外：`pruneHistoricalImagePayloads()` 在历史含图片时会改写既有消息，此时前缀会失配。这是图片 token 成本换取的有意取舍，未在本期改动。

### 实测效果

用项目自带 token 估算器（`TokenBudget.estimateMessagesTokens`）测量一次 13 轮迭代的 ReAct 运行，每轮读取一个不同文件：

| 指标 | 改动前 | 改动后 |
| --- | --- | --- |
| 单次请求可复用前缀 | 恒定 1,602 token（仅静态头） | 等于上一次请求全长，随历史增长 |
| 累计可复用 token | 19,224 | 57,129 |
| 累计未命中 token | 46,241 | 8,336 |
| 可复用占比 | 29.4% | 87.3% |

未命中输入下降约 **5.5 倍**。关键差别不在绝对数字，而在趋势：改动前可复用量是常数，会话越长占比越低；改动后可复用量随历史增长，占比趋升。

口径说明：这是基于本项目 token 估算器的结构性测算，不是 provider 账单。实际省下多少取决于各家 cached input 的折扣（常见为正常输入价的 10%–25%）。按 10% 折扣估算，本例总输入成本下降约 70%。真实命中率需带 API Key 跑长会话，看 `/context` 的 cached token 计数。

## 覆盖规则

同一路径按下面优先级读取：

1. jar 内置：`src/main/resources/prompts/...`
2. 用户级覆盖：`~/.devcli/prompts/...`
3. 项目级覆盖：`.devcli/prompts/...`

例如：

```text
~/.devcli/prompts/base.md
~/.devcli/prompts/modes/agent.md
.devcli/prompts/modes/team-worker.md
```

覆盖是“整文件替换”，不是局部 merge。

## 校验规则

`base.md` 或最终组装结果必须包含：

```markdown
## Language
```

这个 section 用来保证模型默认跟随中文输出。用户覆盖 `base.md` 时如果删掉该 section，启动或调用 prompt 组装会失败。

## 开发拆分

- [x] 新增 `PromptMode` / `PromptContext` / `PromptRepository` / `PromptAssembler`
- [x] 新增内置 Markdown prompt 资源
- [x] 支持用户级覆盖 `~/.devcli/prompts/...`
- [x] 支持项目级覆盖 `.devcli/prompts/...`
- [x] 校验 `## Language`
- [x] ReAct 接入
- [x] Plan task executor 接入
- [x] Multi-Agent 三角色接入
- [x] Planner 接入
- [x] 写 `PromptAssemblerTest`
- [x] 更新 `AGENTS.md`
- [x] 更新 `README.md`
- [x] 更新 `ROADMAP.md`
- [x] 写 prompt 审计模板

## 测试记录

已执行：

```bash
mvn test -Dtest=PromptAssemblerTest,PlannerTest,PlanExecuteAgentTest,SubAgentTest,AgentOrchestratorTest,AgentMemoryHintTest
```

结果：通过，28 个测试，0 failures / 0 errors。

## 当前边界

- Memory 压缩和事实抽取 prompt 仍在 `memory` 包内，后续可单独拆到 `prompts/memory/`。
- 覆盖策略是整文件替换，不做 YAML frontmatter、include 或局部 patch。
- 当前未提供 CLI 查看 prompt 内容，先通过文件约定和测试覆盖。
