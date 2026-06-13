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
  恢复时重建步骤列表——已完成步骤直接标 COMPLETED 带回 result，被重规划接管的步骤保持
  SUPERSEDED，其余（含上次失败的）重置为 PENDING 重新执行；不重新规划、不产生新步骤 id
- **重规划同步**：运行中触发失败重规划（原步骤接管 + 恢复步骤插入）时，最新计划结构同步回
  checkpoint，崩溃后 resume 仍能对位
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

## 修复文件清单

| 文件 | 状态 | 说明 |
|------|------|------|
| `ResourceLeaseManager.java` | ✅ 已修改 | 超时回收 + 措辞与事实对齐 |
| `WorkingMemory.java` | ✅ 已修改 | negativeFact 即时清理失效 RAG 证据 |
| `AgentCheckpoint.java` | ✅ 已扩展 | 计划层落盘 + 原子写入 + loadLatest |
| `AgentOrchestrator.java` | ✅ 已修改 | resume() + executeSteps 共享循环 + 重规划同步 |
| `ToolRegistry.java` | ✅ 已修改 | 按 step 归集修改文件 + prune 委托 |
| `Main.java` | ✅ 已修改 | /team resume 入口 + runHeadlessTurn 历史重放 |
| `TurnRunner.java` | ✅ 新建 | Runtime API 带 threadId 的执行接口 |
| `RuntimeThreadStore.java` | ✅ 已修改 | turnHistory 解析 |
| `Agent.java` | ✅ 已修改 | seedHistory 历史注入 |

---

## 测试验证

对应测试（均不依赖真实 LLM）：

- `AgentCheckpointTest`：计划+进度落盘往返、result 截断、原子写入、loadLatest
- `AgentOrchestratorTest`：resume 跳过已完成步骤、SUPERSEDED 不参与调度、
  未找到 checkpoint 时列出可用项、旧格式 checkpoint 拒绝恢复
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
> "Multi-Agent 并行执行时我解决了三类可靠性问题：1）Worker 崩溃后文件租约永久锁死——租约加超时回收，写入前二次校验；2）大型任务失败后从头开始——checkpoint 同时落盘计划结构和步骤进度，恢复时重建步骤列表跳过已完成步骤，已完成步骤的完整 result 继续作为后续步骤的依赖上下文，重规划改变计划结构时同步回 checkpoint 保证崩溃后仍可对位；3）Runtime API 多轮对话无上下文——采用'存储即状态'：每 turn 新建 Agent 保持隔离，执行前从 SQLite 事件流重放该 thread 的历史输入输出对，进程重启上下文不丢。可行性的关键前提是 Worker 每步执行后清空会话、步骤上下文完全来自步骤结果列表，所以恢复只需要'计划+进度'，不需要恢复任何 LLM 会话状态。"
