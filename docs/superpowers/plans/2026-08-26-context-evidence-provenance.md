# Context Evidence Provenance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Multi-Agent 共享内容显式携带可信来源与逻辑新鲜度，并拒绝过期执行尝试产生的迟到证据。

**Architecture:** 复用现有 `ExecutionArtifact`、`ContextVersionLedger` 和 `SessionMemory`，不增加第二套状态源。Orchestrator 从 Reviewer 结论与结构化工具证据生成依赖摘要；Fork 捕获项目 epoch；证据记录携带 agent、step、origin sequence 与 context epoch。

**Tech Stack:** Java 17、JUnit 5、Maven、现有 checkpoint v8 与 Runtime schema v2

---

### Task 1: 锁定行为契约

**Files:**
- Modify: `src/test/java/com/devcli/agent/AgentOrchestratorTest.java`
- Modify: `src/test/java/com/devcli/agent/SubAgentTest.java`
- Modify: `src/test/java/com/devcli/memory/SessionMemoryArchitectureTest.java`
- Modify: `src/test/java/com/devcli/workspace/ContextVersionLedgerContractTest.java`

- [x] 写入摘要来源、Fork epoch、迟到证据拒绝和 epoch 单调递增测试
- [x] 运行限定测试，确认因缺少新契约而失败

### Task 2: 接入可信依赖摘要

**Files:**
- Modify: `src/main/java/com/devcli/agent/AgentOrchestrator.java`

- [x] 分离 Worker 原始输出与 Orchestrator 可信摘要
- [x] 依赖步骤和 Final integration 只注入可信摘要
- [x] 保留原始输出用于 Reviewer 和最终展示

### Task 3: 接入上下文 epoch

**Files:**
- Modify: `src/main/java/com/devcli/workspace/ContextVersionLedger.java`
- Modify: `src/main/java/com/devcli/agent/SubAgent.java`
- Modify: `src/main/java/com/devcli/agent/AgentOrchestrator.java`

- [x] 暴露单调 generation，并让外部 dirty 事件推进 generation
- [x] ForkContext 固化 `contextEpoch`，纳入 fingerprint 与任务后缀
- [x] Planner 状态视图注入 `context_epoch`

### Task 4: 拒绝迟到证据

**Files:**
- Modify: `src/main/java/com/devcli/memory/MemoryManager.java`
- Modify: `src/main/java/com/devcli/memory/SessionMemory.java`
- Modify: `src/main/java/com/devcli/agent/AgentOrchestrator.java`

- [x] 每次 agent/step 执行生成单调 origin sequence
- [x] 工具证据携带 origin sequence 与 context epoch
- [x] SessionMemory 拒绝已被新 attempt 取代的旧证据，并在 Prompt 标注来源与 epoch
- [x] 保留旧构造器，避免 checkpoint 与测试调用面破坏

### Task 5: 验证、文档与交付

**Files:**
- Modify: `AGENTS.md`
- Modify: `TODO.md`

- [x] 运行限定测试与 `mvn test -Pquick -DskipTests=false`
- [x] 检查 `git diff --check`、文档结构、状态和未跟踪文件
- [x] 使用 Conventional Commits 中文信息准备提交；推送状态以 Git 交付结果为准

说明：项目规则禁止未获单独授权的子代理，因此本计划在当前线程执行和复核。
