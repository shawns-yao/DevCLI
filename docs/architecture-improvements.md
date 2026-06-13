# 架构问题修复记录

## 修复时间
**2026-06-12 / 2026-06-13**

---

## 已修复问题

### 1. ✅ ResourceLeaseManager - 添加超时机制

**问题**：
- Worker 崩溃后租约永不释放 → 文件永久锁死

**修复方案**：
- 租约加时间戳（`LeaseEntry(stepId, acquireTime)`），`acquireWrite` 时超时（30s）自动回收
- `write_file` 执行前二次校验 `isLeaseValid`，防止租约超时被回收后旧任务仍然写入
- `pruneExpiredLeases()` 主动清理接口由 `ToolRegistry` 委托暴露，
  `AgentOrchestrator.run()` / `resume()` 启动时调用一次，回收上一轮崩溃残留

**价值**：
- 避免 Worker 崩溃导致文件永久锁死
- 30 秒超时足够正常 Worker 完成写入

**边界说明**：
- 超时回收只写 slf4j 日志，未接 `AuditLog` 审计链

---

### 2. ✅ Memory 维护职责内联到既有组件（方案演进）

**问题**：
- WorkingMemory 没有清理逻辑
- RagEvidenceMemory 旧版本失效后未清理
- LongTermMemory 没有淘汰策略

**最终方案**（曾设计独立的 `MemoryMaintenanceService`，因全部职责都有更自然的归属，该类已删除）：

- 会话清理：复用既有 `MemoryManager.clearShortTerm()`（`/clear` 命令路径）
- 失效 RAG 证据清理：`WorkingMemory` 在解析 `search_code` 结果时，对携带
  `oldSymbolVersion=` 的 negativeFact **即时清理**对应证据（`pruneEvidenceForNegativeFact`），
  不再依赖外部维护服务定期触发；`pruneInvalidEvidence(SymbolInvalidation)` 保留为公共接口
- 内存报告：复用既有 `MemoryManager.getSystemStatus()`（`/memory` 命令路径）

**价值**：
- 失效证据清理从"需要有人记得调用"变成检索链路内联自动触发
- 少一个无人调用的维护类，职责回归数据持有者本身

**注意**：
- LongTermMemory 当前无容量上限和淘汰策略，属于已知缺口；
  自动删除用户长期记忆涉及数据安全语义，淘汰策略需要单独决策后再实现

---

### 3. ✅ Multi-Agent - Checkpoint 断点续传（/team resume）

**问题**：
- Worker 失败/进程崩溃后任务从头开始，浪费已完成步骤的时间和 Token
- 早期版本 checkpoint 只写不读：未持久化计划本身，重启后重新规划产生新步骤 id，无法对位

**实现范围**（`AgentCheckpoint` + `AgentOrchestrator` + `ToolRegistry` + `Main`）：

- **计划 + 进度双层落盘**：计划解析完成时把完整任务文本、步骤列表（id/描述/类型/依赖）和
  验收点写入 checkpoint；步骤终态时保存完整 result（8KB 上限，供后续步骤当依赖上下文）
  和本步骤实际修改的文件列表（`ToolRegistry` 按 `resourceLeaseStep` 在 write_file 写入点归集）
- **恢复入口**：`/team resume` 恢复最近 checkpoint，`/team resume <id>` 恢复指定任务；
  恢复时重建步骤列表——已完成步骤直接标 COMPLETED 带回 result，其余（含上次失败的、被阻塞的）
  重置为 PENDING 重新执行；不重新规划、不产生新步骤 id
- **原子写入**：save 先写 `.tmp` 再原子 move，崩溃瞬间不会留下半截 JSON
- 全部成功后删除 checkpoint；失败/崩溃后保留在 `~/.devcli/checkpoints/` 供恢复或排查

**边界（不在实现范围内）**：
- resume 不恢复 WorkingMemory / 会话记忆，Worker 上下文完全来自 checkpoint 内的步骤 result
- 崩溃时执行到一半的步骤可能留下半成品文件改动，resume 不自动回滚（Side-Git 快照联动未实现）

---

### 4. ✅ Runtime API - 跨 turn 上下文延续（存储即状态）

**问题**：
- `/v1/threads/{id}/turns` 语义上是多轮对话，但每个 turn 新建 Agent，turn 2 不记得 turn 1

**实现方案**（方案 A"存储即状态，计算无状态"）：

- `RuntimeThreadStore.turnHistory(threadId)` 从 `runtime_events` 解析已完成 turn 的
  输入/输出对（只取有 `turn.completed` 终态的完整 turn，失败/被拒 turn 不进历史，
  事件解析失败跳过并 warn）
- `TurnRunner(threadId, input)` 新接口替换 Runtime API 侧的 `TaskRunner`
  （`TaskRunner` 保持不动，`DurableTaskManager` 继续使用）
- `Main.runHeadlessTurn`：每 turn 仍新建 Agent 保持隔离，执行前重放该 thread 最近 20 个
  turn 的历史（`Agent.seedHistory` 注入 system message 之后），超长窗口交给既有
  `ConversationHistoryCompactor` 治理

**价值**：
- 同一 thread 多轮对话有上下文延续；历史在 SQLite，进程重启不丢
- 保留每 turn 隔离的并发安全性，无内存驻留 Agent 的淘汰/锁复杂度

---

### 5. ✅ Multi-Agent 失败恢复重构 - 平行 replan → 在位重做（含思路演进）

**问题（用户追问逼出的根本矛盾）**：
代码开发有**副作用**——Worker A 完成后文件已落盘。早期实现里 Worker 失败触发的"全局重规划"（`replanFailedSteps`）生成的是一份**平行新计划**（新 `r{round}_step_N` id、新结构），它与原 DAG 已完成步骤的真实文件副作用**没有依赖契约**（`remapRecoveryStepIds` 甚至丢弃对原步骤的依赖），只靠 prompt 文字求 Planner"别碰已完成的"。"换全新思路"在有副作用的代码任务里本质危险：新平行计划可能覆盖、破坏已落盘的旧成果，而状态机仍显示旧步骤 COMPLETED——状态与文件系统脱节。

**思路演进（记录我们怎么想的，含被否决的错误思路）**：

1. **方案 A：执行级文件冲突检测 + 告警** —— 写文件时检测是否触碰已完成步骤的文件，记冲突并报告。
   **被否决**：它只让冲突"可见"，不消除冲突，是在错误设计上打补丁。
2. **方案 R：失效传播 + 在位重做替代平行 replan** —— 失败步骤在原位重做 + 依赖图失效传播。
   **失效传播被砍**：取证发现"单向 DAG + 只重做失败步骤"模型下，失败步骤的下游必然还是 PENDING（被阻塞），**不存在需要因上游变化而重做的已完成下游**——失效传播无触发场景（YAGNI）。
3. **副作用信息传递：先想加 WorkingMemory「已修改文件账本」维度** ——
   **被否决**：取证发现 `recentToolResults` 已含 write_file 路径，再加账本维度只是"为免 FIFO 挤出而复制一份 path"，对 `ragEvidence`（提取了 result 没有的 SymbolVersion 元数据）并非真对称——是重复造轮子。
4. **最终落地**：① 失败步骤**在位重做**（保持原 id/依赖，换思路反馈来自 `StepRedoTracker`）替代平行 replan；② 改 `recentToolResults` **淘汰策略**让副作用证据（write_file/execute_command）优先保留、不被只读操作挤出——零新增维度、零信息复制，修的是"无差别 FIFO"的根本缺陷，且全局受益。

**实现范围**：
- 移除 `replanFailedSteps`/`remapRecoveryStepIds`/`supersedeFailedAndBlockedSteps`/`insertRecoverySteps`/`buildReplanTask`/`setReplanEnabled` 与 `StepStatus.SUPERSEDED`
- 新增 `StepRedoTracker`（独立类，承载在位重做的计数+失败反馈决策，与调度循环解耦，避免堆进已超 1600 行的 `AgentOrchestrator`）
- `WorkingMemory.recordToolResult` 淘汰改为副作用证据优先保留（`isSideEffectTool` 判定）
- `AgentCheckpoint.supersededSteps` 降级为遗留兼容字段（只反序列化旧 checkpoint，不再写入）

**补充修复：失败步骤副作用的跨进程持久化（resume 维度）**

上面的 WorkingMemory 增强只在**同进程内**有效——resume（崩溃/跨进程恢复）时 WorkingMemory 是空的，失败步骤的副作用必须走 checkpoint。而旧失败分支把 `consumeStepModifiedFiles` 的结果直接丢弃（"避免跨步骤串档"），导致：失败步骤已写文件 → resume 后该步骤重置 PENDING 重做 → Worker 不知道上次失败留下了哪些文件。修复：
- `AgentCheckpoint` 新增 `failedArtifacts`（复用 `StepArtifact`，不新增结构）+ `addFailedStep`（内部调 `recordFailure`，不重复计数）
- 失败分支改为 `addFailedStep` 保留 modifiedFiles，不再丢弃
- `addCompletedStep` 清理同 step 的旧失败 artifact，避免成功/失败记录并存
- resume 后把 `failedArtifacts` 载入 `restoredFailedArtifacts`，`buildStepContext` 注入已写文件清单 + 失败摘要（提示 Worker 重做前先读这些文件的当前内容）

职责边界：**WorkingMemory 是当前会话副作用证据缓存（会淘汰、不跨进程）；失败步骤的持久化副作用由 checkpoint `failedArtifacts` 负责**。两者互补：同进程靠 WorkingMemory，跨进程 resume 靠 checkpoint。

**边界（诚实声明）**：
- 在位重做仍可能改到计划外文件（Worker 步骤内越界改文件、"执行中拓扑变化"未解）——彻底根治需 Saga 事务（每步配补偿回滚），对本地 CLI 属过度设计，本次不做
- 副作用信息流是 prompt 软注入（让后续 Worker 知情），不是事务性保证

---

## 修复文件清单

| 文件 | 状态 | 说明 |
|------|------|------|
| `ResourceLeaseManager.java` | ✅ 已修改 | 超时回收 + 措辞与事实对齐 |
| `WorkingMemory.java` | ✅ 已修改 | negativeFact 即时清理失效 RAG 证据 |
| `AgentCheckpoint.java` | ✅ 已扩展 | 计划层落盘 + 原子写入 + loadLatest |
| `AgentOrchestrator.java` | ✅ 已修改 | resume() + executeSteps 共享循环 + 在位重做替代 replan + 失败副作用注入 |
| `AgentCheckpoint.java` | ✅ 已修改 | failedArtifacts 失败步骤产物账本 + addFailedStep |
| `StepRedoTracker.java` | ✅ 新建 | 在位重做计数+失败反馈决策（与调度解耦） |
| `WorkingMemory.java` | ✅ 已修改 | 副作用工具证据优先保留的淘汰策略 |
| `ToolRegistry.java` | ✅ 已修改 | 按 step 归集修改文件 + prune 委托 |
| `Main.java` | ✅ 已修改 | /team resume 入口 + runHeadlessTurn 历史重放 + 移除 setReplanEnabled |
| `TurnRunner.java` | ✅ 新建 | Runtime API 带 threadId 的执行接口 |
| `RuntimeThreadStore.java` | ✅ 已修改 | turnHistory 解析 |
| `Agent.java` | ✅ 已修改 | seedHistory 历史注入 |

---

## 测试验证

对应测试（均不依赖真实 LLM）：

- `AgentCheckpointTest`：计划+进度落盘往返、result 截断、原子写入、loadLatest
- `AgentOrchestratorTest`：resume 跳过已完成步骤、遗留 superseded 字段被忽略、
  失败步骤 FAILED 终态下最终集成可执行、失败比例熔断、未找到 checkpoint 列出可用项、旧格式拒绝恢复
- `StepRedoTrackerTest`：在位重做计数上限、失败反馈存取、reset 清空
- `WorkingMemoryEvictionTest`：副作用证据优先保留、全副作用淘汰最旧、纯只读 FIFO
- `ToolRegistryStepFilesTest`：按 step 归集修改文件、consume 后清空、无租约写入不归集
- `RuntimeThreadStoreTest`：turnHistory 完整 turn 解析、失败/进行中 turn 跳过、坏数据容错
- `RuntimeApiServerTest`：TurnRunner 收到 threadId、第二轮可见第一轮历史
- `AgentSeedHistoryTest`：注入位置、二次注入忽略、空入参容错
- `MainTeamResumeParseTest`：/team resume 子命令解析

运行：

```bash
mvn test -Dtest="AgentCheckpointTest,AgentOrchestratorTest,ToolRegistryStepFilesTest,RuntimeThreadStoreTest,RuntimeApiServerTest,AgentSeedHistoryTest,MainTeamResumeParseTest" -DskipTests=false
```

---

## 简历价值提升

### 修复前
- ❌ 租约永久锁死
- ❌ 内存可能泄漏
- ❌ 失败从头开始（checkpoint 只写不读）
- ❌ Runtime API turn 间无上下文

### 修复后
- ✅ 租约超时自动回收（30s）+ 启动时残留清理
- ✅ WorkingMemory 会话清理 + RAG 证据失效管理
- ✅ Checkpoint 断点续传（计划+进度落盘，/team resume 恢复）
- ✅ Runtime API 跨 turn 上下文（存储即状态，重启不丢）

### 面试话术
> "Multi-Agent 并行执行时我解决了三类可靠性问题：1）Worker 崩溃后文件租约永久锁死——租约加超时回收，写入前二次校验；2）大型任务失败后从头开始——checkpoint 同时落盘计划结构和步骤进度，恢复时重建步骤列表跳过已完成步骤，已完成步骤的完整 result 继续作为后续步骤的依赖上下文，失败步骤在原位换思路重做而非生成平行计划，恢复始终在原 DAG 上、避免新计划与已落盘成果冲突；3）Runtime API 多轮对话无上下文——采用'存储即状态'：每 turn 新建 Agent 保持隔离，执行前从 SQLite 事件流重放该 thread 的历史输入输出对，进程重启上下文不丢。可行性的关键前提是 Worker 每步执行后清空会话、步骤上下文完全来自步骤结果列表，所以恢复只需要'计划+进度'，不需要恢复任何 LLM 会话状态。"
