# Sandbox Execution Security Design

## Status

This is a future design document. It records issues exposed by interviews and does not change current runtime behavior.

## 暴露的问题

执行层安全面试主要追问：

- `execute_command（执行命令）` 如果执行危险命令怎么办？
- 模型被 `Prompt Injection（提示词注入）` 后扫描内网怎么办？
- 多租户服务端如何隔离？
- 沙箱里修改的代码怎么安全同步回宿主机？
- 命令超时、资源占用、孤儿进程怎么处理？

## 当前已有

- `PathGuard（路径守卫）` 限制路径在项目根内。
- `CommandGuard（命令守卫）` 拦截明显危险命令。
- `HITL（人类审批）` 处理危险工具审批。
- `AuditLog（审计日志）` 记录工具调用。
- 本地 CLI 场景下可以运行 Maven 编译/测试。

## 不足

- 当前是本地策略保护，不是强隔离沙箱。
- 没有 Docker / gVisor / Firecracker。
- 没有网络默认拒绝。
- 没有统一 `CommandProfile（命令画像）`。
- 没有资源限制和进程组清理完整实现。
- 没有 patch-based sandbox output。

## 怎么修改

### 1. 沙箱分级

```text
Tier 0: local process + policy guard
Tier 1: Docker container
Tier 2: gVisor
Tier 3: Firecracker microVM
```

选择规则：

- 本地可信项目用 Tier 0。
- 普通服务端任务用 Tier 1。
- 不可信仓库用 Tier 2。
- 高风险多租户任务用 Tier 3。

### 2. CommandProfile

命令按画像执行：

```text
MAVEN_COMPILE
MAVEN_TEST
GRADLE_TEST
READ_ONLY_SHELL
DENIED_MUTATION
NETWORK_DENIED
```

每个画像定义：

- allowed executable.
- args pattern.
- working directory.
- timeout.
- max output size.
- network policy.
- env allowlist.

### 3. Network Default Deny

服务端模式：

- 默认禁止公网和内网。
- 依赖下载走 dependency proxy。
- 只允许 Maven/Gradle registry allowlist。
- 所有外联尝试进入 AuditLog。

### 4. Patch-Based Output

沙箱不直接写宿主机。

```text
copy snapshot into sandbox
-> run Agent/tools
-> export PatchSet
-> validate path and diff
-> apply in host workspace only after review
```

### 5. Process Control

执行命令必须支持：

- timeout.
- process group kill.
- output truncation.
- CPU/memory limit.
- max file writes.
- cleanup hook.

## 设计边界

- 本地 CLI 不强依赖 Docker。
- 服务端模式不能给模型裸 shell。
- HITL 不能批准 policy denied 操作。
- 沙箱输出不能绕过 PathGuard。

## 验收标准

- 命令执行必须匹配 CommandProfile。
- 超时命令能清理子进程。
- 服务端模式默认无网络。
- 沙箱输出是 PatchSet。
- AuditLog 记录命令、资源、网络和结果。

## 文字解释

面试时可以这样讲：

> 当前 DevCLI 是本地 CLI，所以已有 PathGuard、CommandGuard、HITL 和 AuditLog，但这不是服务端强沙箱。生产级我会把执行面分层：可信本地项目可以用本地进程策略，不可信任务进入 Docker，更高风险用 gVisor 或 Firecracker。命令不是任意 shell，而是 `CommandProfile（命令画像）`，例如 Maven compile、Maven test、read-only shell，每类都有 timeout、资源限制、网络策略和参数白名单。沙箱不直接写宿主机，只导出 `PatchSet（补丁集）`，通过路径校验、编译测试和 Reviewer 后再合并。

