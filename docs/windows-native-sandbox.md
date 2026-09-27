# Windows 原生离线隔离

## 启用范围

这是显式启用的 AppContainer + Job Object 后端，不替换现有工作区。命令仅在请求已经处于 `ISOLATED_PROJECT` 时使用；主 Agent 普通主机命令仍保留原路径和单次审批。默认命令后端仍为 Docker。

目标进程默认无网络 capability；工作目录按执行类型授予读写或只读，工具链目录只读，`.git` 拒绝访问。临时目录与用户配置属于独立 AppContainer。模型不能变更这些授权。

## 构建与配置

需要 Windows、.NET SDK 8 或可构建 .NET 8 的更新 SDK。发布为自包含程序，固定运行时补丁版本 8.0.31，使用时不需要另装 .NET Runtime。构建不安装账户、防火墙规则或系统服务。.NET 8 支持期到 2026-11-10，发布维护时须安排后续升级。

在仓库根目录执行：

```powershell
& ./native/windows-sandbox/build.ps1
$env:DEVCLI_WINDOWS_SANDBOX_LAUNCHER = (Resolve-Path ./target/windows-sandbox/devcli-windows-sandbox.exe).Path
$env:DEVCLI_COMMAND_SANDBOX_MODE = 'WINDOWS_NATIVE'
```

分发时保留 `target/windows-sandbox/` 的整个输出目录，不能只复制 `.exe`。ARM64 使用 `-RuntimeIdentifier win-arm64` 构建，尚需在对应机器验证。

| 配置 | 系统属性 | 含义 |
| --- | --- | --- |
| `DEVCLI_WINDOWS_SANDBOX_LAUNCHER` | `devcli.windows.sandbox.launcher` | 可信启动器的绝对 `.exe` 路径，不能位于目标可写工作区 |
| `DEVCLI_WINDOWS_SANDBOX_READ_ROOTS` | `devcli.windows.sandbox.read.roots` | 分号分隔的绝对目录，只读工具链及依赖；不继承宿主 PATH |
| `DEVCLI_COMMAND_SANDBOX_MODE` | `devcli.command.sandbox.mode` | 显式选择 `WINDOWS_NATIVE` |

例如将用户拥有的 JDK 与 Maven 专用安装目录加入读权限根。启动器从这些目录及其 `bin` 构建 PATH，并从包含 `bin/javac.exe` 的目录设置 JAVA_HOME。不要授权整个磁盘、用户目录或包含其他任务的父目录。

目录授权需要当前用户的 ACL 管理权限。系统安装目录不能授权时失败关闭，不自动提权或重写所有者；应使用用户准备的专用工具链副本。测试使用临时 JDK 副本，不修改 SYSTEM 所有的本机 JDK 权限。

Maven 依赖缓存也必须显式包含在只读根内，命令应显式采用 `-o` 和对应的 `maven.repo.local` 参数。现有 `DEVCLI_COMMAND_SANDBOX_MAVEN_REPOSITORY` 自动挂载/注入规则只属于 Docker 与受限主机后端；Windows 原生模式不自动继承它。缺失依赖时失败，不放开网络。PowerShell 参数中含 `=` 时对整个参数加引号，例如 `mvn -o '-Dmaven.repo.local=C:\Tools\maven-cache' test`。

## 本地 MCP

在已有 MCP server 配置中显式增加：

```json
{
  "mcpServers": {
    "local-reader": {
      "command": "C:/Tools/node/node.exe",
      "args": ["C:/Tools/local-mcp/server.js"],
      "sandbox": "WINDOWS_NATIVE"
    }
  }
}
```

Node.js 与 server 目录需加入只读根。首版拒绝自定义 `env` 和 `.cmd`/`npx` 入口，不支持运行时联网下载。项目目录只读；项目内的普通文件也在可读范围内，不能宣称自动识别或过滤其中全部凭据。未配置 `sandbox` 的 server 保持原宿主行为，HTTP server 不能配置该值。

该连接是项目级只读连接，不是每个 Worker 的独立工作区视图。原 MCP 审批和信任注解规则不变。

## 定向验证

```powershell
mvn.cmd test '-Dtest=WindowsNativeSandboxTest,WindowsSandboxMcpTest' "-Ddevcli.windows.sandbox.launcher=$env:DEVCLI_WINDOWS_SANDBOX_LAUNCHER" -DskipTests=false
```

真实隔离用例只在 Windows 且显式提供启动器属性时运行。普通构建跳过这些用例不代表隔离验证通过。用例使用临时目录、模拟外部 MCP 协议端点和真实操作系统限制，不调用远程模型，不访问业务数据库。

## 故障与边界

- 启动失败、授权失败、Job 设置失败均拒绝执行，绝不回退普通进程。
- Job 设置为 kill-on-close，不开放 breakaway，限制 64 个进程和合计 1 GiB 提交内存；命令还受调用期限约束，MCP 生命周期由连接关闭控制。
- 普通退出、超时和取消会清理权限与 profile；启动器被外部强杀可能遗留资源，当前没有自动恢复清扫。遗留的随机身份不会用于新任务。
- 重解析点、硬链接和 UNC 路径不支持。运行时安装中包含这些结构时必须提供可用的普通文件副本。
- AppContainer 默认系统资源、内核漏洞和宿主自身权限不由本功能完全隔离。授权目录内仍可能产生错误或恶意代码，必须继续验证再归并。
- 当前没有磁盘空间配额、CPU 硬限额和全面外部服务代理隔离；不得把内存/进程数限制解释为全部资源隔离。
