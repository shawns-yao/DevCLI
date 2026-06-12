# Reviewer Independent Retrieval Design

## Status

This is a future design document. It records issues exposed by interviews and does not change current runtime behavior.

## 暴露的问题

面试中多次质疑 `Reviewer（审查者）` 会不会变成橡皮图章：

- 如果只是看语法错误，编译器已经能做。
- Reviewer 是否只是复述 Worker 的解释？
- 怎么发现需求不符、隐藏副作用、漏改调用方？
- Reviewer 是否有不同于 Worker 的 RAG 检索策略？

## 当前已有

- Reviewer 前有 `Pre-Review Hook（审查前硬检查）`。
- Java 项目优先跑 `mvn -q -DskipTests test-compile`。
- Reviewer 输出包含：
  - `functional_correctness（功能正确性）`
  - `integration_completeness（集成完整性）`
  - `code_quality（代码质量）`
  - `criteria_results（验收结果）`
- critical/high 验收失败强制不通过。
- Reviewer WorkingMemory 与 Worker 视图隔离。

## 不足

- Reviewer 的 RAG profile 还不够独立。
- Reviewer 可能过度依赖 Worker 产物和解释。
- 对 tests、调用方、配置、文档、历史 bugfix 召回不够系统。
- 隐藏副作用检查没有固定 checklist。
- Reviewer 拒绝理由的证据引用还可以更强。

## 怎么修改

### 1. ReviewerRetrievalProfile

Worker 查“怎么改”，Reviewer 查“改完影响谁”。

Reviewer 默认召回：

- acceptance criteria.
- changed files.
- direct callers.
- public API users.
- tests.
- config files.
- mapper XML.
- related docs.
- similar bugfix history.

### 2. Patch-Centric Review

Reviewer 输入不要包含完整 Worker 对话。

输入应是：

```text
task goal
acceptance criteria
PatchSet
compile/test result
tool evidence
retrieved impact context
known invalidation events
```

### 3. Side Effect Checklist

Reviewer 必查：

- public API compatibility.
- null / boundary behavior.
- transaction / cache side effects.
- config and serialization compatibility.
- tests covering changed path.
- changed method callers.
- stale context warnings.

### 4. Evidence-Based Rejection

拒绝必须包含：

- failed criterion.
- evidence file.
- relevant symbol.
- why patch is insufficient.
- recommended next action.

## 设计边界

- Reviewer 不负责修代码。
- Reviewer 不直接相信 Worker 口头解释。
- 编译失败不进入 LLM Reviewer。
- Reviewer 不读取全量聊天历史。

## 验收标准

- Worker 和 Reviewer 使用不同 retrieval profile。
- Reviewer 输入以 PatchSet 和证据为中心。
- Reviewer 每个拒绝项都有 evidence reference。
- Reviewer 能发现“编译通过但需求不符”的案例。
- Reviewer 结论可被 Orchestrator 结构化消费。

## 文字解释

面试时可以这样讲：

> Reviewer 不能只是语法检查，语法和类型错误应该由 Pre-Review Hook 和编译器先挡掉。Reviewer 的价值是审需求、审集成、审副作用。所以我会让 Reviewer 使用独立的 `ReviewerRetrievalProfile（审查者检索画像）`：Worker 检索实现上下文，Reviewer 检索调用方、测试、配置、文档、历史 bugfix 和验收标准。Reviewer 不看完整 Worker 聊天史，而是看 PatchSet、编译结果、工具证据和影响面上下文。拒绝时必须引用具体 evidence，说明哪个验收点失败。

