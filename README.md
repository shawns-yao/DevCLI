# DevCLI 架构

DevCLI 是面向代码仓库的 Java Agent CLI。它把模型调用、工具执行、代码检索、任务记忆、隔离工作区、人工审批和运行恢复组织成一个本地开发运行时。

## 总体结构

```text
Main
├── Agent                         # 默认 ReAct 主 Agent
│   └── DelegationSession         # 按需委派的独立子 Agent
└── AgentOrchestrator             # 显式 /plan 编排入口
    ├── PlanCoordinator           # 计划与 DAG 预处理
    ├── StepExecutionCoordinator  # 隔离执行、重试与补丁归并
    ├── ReviewCoordinator         # 计划与产物评审
    └── CheckpointCoordinator     # 检查点保存与恢复

共享运行时
├── AgentExecutionEngine          # 统一 Agent / Turn / Tool 控制流
├── ToolRegistry                  # 内置工具、MCP 工具与权限管线
├── PromptAssembler               # 分层 Prompt 组装
├── MemoryManager                 # 当前上下文、任务记忆与长期记忆
├── CodeRetriever                 # 代码 RAG 检索
├── WorkspaceExecutionSession     # 隔离工作区与 PatchSet
├── RunCoordinator / RunStore     # 运行生命周期与事件存储
├── McpServerManager               # MCP 连接与动态工具生命周期
├── SnapshotService                # Side-Git 快照与回滚
└── Renderer                       # inline / plain 终端呈现
```

## 执行模式

### 默认 ReAct

主 Agent 直接理解需求、选择工具、验证结果并负责最终验收。遇到需要并行调查、独立实现或独立复核的任务时，通过 `delegate_task` 按需调用 explorer、planner、worker 或 reviewer。

子 Agent 只接收冻结的系统规则、角色指引、显式子任务和必要背景，不复制父会话、兄弟消息或长期记忆。子 Agent 不能继续委派，也不能访问外部副作用工具。

### 显式 `/plan`

`/plan` 是重型编排模式，用于长流程、并行 DAG、检查点恢复和强审计任务。Planner 生成执行图和验收标准，Worker 在隔离工作区执行，Pre-Review 进行确定性检查，Reviewer 根据真实证据验收，最终只把通过版本检查的 PatchSet 归并到主项目。

普通交互任务不强制进入 `/plan`。

## 多 Agent 边界

```text
主 Agent：理解需求、分配工作、整合结果、最终验收
explorer：只读调查与证据收集
planner：只读拆解与验收设计
worker：隔离工作区中的代码修改
reviewer：只读、独立、基于证据的复核
```

每个子任务拥有独立的对话历史、上下文压缩器、工具访问范围、Skill 上下文副本、资源租约归属、取消边界和运行标识。角色可配置独立模型；显式配置不可用时失败关闭，不静默替换。

## 工具执行管线

所有工具统一经过：

```text
取消检查 → 工具存在性 → 能力范围 → Skill 权限
→ JSON Schema 参数校验 → HITL 审批 → 审计
→ 策略判定 → 结果尺寸治理
```

工具按副作用分为 `READ_ONLY`、`LOCAL_CONTEXT`、`PROJECT_MUTATION`、`HOST_PROCESS` 和 `EXTERNAL_MUTATION`。并行工具最多 4 路且保持结果顺序；参数错误、策略拒绝、超时、取消和命令失败以结构化状态回传模型。

工具调用参数会生成稳定语义指纹，用于重复动作检测、停滞提醒和硬熔断。只读结果可做会话级短期缓存，任何副作用工具执行或项目切换都会清空缓存。

## Skill 架构

Skill 是可路由、可验证、可维护的知识单元，而不是默认注入的长手册。

```text
Skill 索引 → 任务相关路由 → 按需 load_skill
          → 分页加载正文或 reference
          → 工具白名单约束
          → 记录实际激活
```

Skill 来源分为 builtin、user 和 project 三层。project Skill 默认不可信，必须显式信任；其正文使用不可信参考资料边界包裹，不能改变系统规则或执行结构。

Skill 内容按职责拆分：

```text
SKILL.md       路由入口与触发条件
rules/         稳定约束
workflows/     可执行流程
references/    代码地图与详细资料
gotchas/       已验证的高成本陷阱
scripts/       可复用的确定性检查
```

索引只携带短描述，正文和 reference 根据剩余 Token 预算分页加载。系统分别记录索引展示、正文激活和 reference 激活，避免把“被列出”误认为“真正生效”。

## Prompt 与上下文

Prompt 按稳定性分层：

```text
base → personality → mode → approval → project_context
     → skills → context_management → handoff
```

稳定层前置，易变层后置，以保持模型前缀缓存命中。父 Agent 与子 Agent 共享冻结的系统规则和工具定义快照，但不共享可变会话历史。

上下文压缩只治理当前运行窗口，不替代任务记忆或长期记忆：超大单条消息先做 microcompact，历史摘要按 Token 预算生成，关键文件、工具证据、失败尝试和下一步动作保留为结构化投影；Skill、MCP、RAG 等运行状态在压缩后通过恢复段重新注入。

## 记忆分层

```text
conversationHistory + RollingSummary  当前线程上下文
SessionMemory                         当前任务运行投影
LongTermMemory                        跨任务稳定事实
```

`SessionMemory` 保存本任务改动、工具证据、待办和失败尝试，不跨进程持久化。长期记忆经过脱敏、限长和隔离 Curator 处理；自动保存与人工确认分离，召回只记录观测，不因被检索自动延长生命周期。

长期记忆来源以快照和 SHA-256 固化，不依赖原始会话是否仍然存在。记忆按作用域隔离，并通过确认、重复显式保存或验证通过等强信号延长有效期。

## 代码 RAG

```text
JavaParser 分块
  → keyword / semantic / bounded graph
  → RRF 融合
  → symbol-aware boost
  → CrossEncoder rerank
```

索引以文件 generation 和项目 epoch 做并发控制。增量构建先写入影子表，完成 CAS 校验后原子提升；旧 epoch 结果不可见。检索结果带 `CURRENT`、`STALE` 或 `DIRTY` 状态，必要时回读实时文件校验。

`search_code` 负责语义和结构检索，`grep_code` 保留为独立的精确定位工具，用于类名、方法名、配置键和固定错误文本。

## 安全工作区与补丁

副作用任务在隔离工作区中执行，后端可使用 Git worktree、文件系统写时复制或有界复制。敏感文件、符号链接和路径逃逸受到统一围栏限制。

Worker 的结果只有形成 PatchSet 后才能进入主项目：

```text
工作区修改 → before/after hash 与权限快照
           → 资源租约 → 版本冲突预检
           → 项目锁与跨进程文件锁 → 原子应用
           → 失败回滚或 checkpoint
```

同一文件不允许多个运行中步骤并发写入。命令默认在禁网、只读根文件系统的 Docker 沙箱中执行；`HOST_WARN` 仅作为显式的受限主机检查模式，不自动回退。

## Reviewer 与验收

确定性检查和语义评审分层：编译、测试、哈希和版本校验由工具执行；Reviewer 负责静态和语义核对；验收标准声明验证方式、验证器和适用节点；`critical/high` 失败阻断，`normal` 进入 advisory；缺少真实工具证据时不能伪装成通过。

默认 ReAct 委派路径只在高风险条件下触发独立 Reviewer，例如大范围修改、关键安全资源或副作用工具失败。Worker 报告通过程序保存并以报告 ID 原文注入下游，避免模型二次转述损失信息。

## 运行时、恢复与观测

每次 CLI、Runtime API 或后台任务都绑定独立 `RunContext`，包含项目路径、取消令牌、资源生命周期和 `runId`。所有阶段输出强类型 `RunEvent`，由 `RunStore` 和 JSONL trace 保存。

Checkpoint 保存执行图、执行产物、验收元数据、PatchSet 写前日志、权限、步骤身份、失败摘要和重做额度，不保存完整的 SubAgent 对话对象图。恢复时重建原拓扑并重新验证，不直接信任旧的工作区状态。

失败统一输出原因、分类、下一步动作以及重试、人工接手、接受部分结果和回滚选项。

## 核心设计原则

1. 主 Agent 负责最终决策，子 Agent 只承担边界清晰的子任务。
2. 默认路径保持轻量，复杂能力按需启用。
3. 模型负责理解和生成，程序负责权限、状态、证据和一致性。
4. 读取、修改、验证、归并和恢复拥有明确边界。
5. “被加载”不等于“已生效”，“模型声明成功”不等于“任务验收通过”。
