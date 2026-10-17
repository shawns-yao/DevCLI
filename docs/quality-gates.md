# 代码质量门禁

正确性靠验证，边界靠明确职责。门禁不要求增加产品功能、抽象层或防御分支。

## 命令与覆盖

使用仓库的 Maven Wrapper（3.9.11）和 JDK 21 构建、测试；生产源码以 `--release 17` 编译，测试源码沿用现有 Java 21 API。运行环境为 Java 17+，CI 固定 JDK 21。下表以 Unix shell 表示，Windows PowerShell 使用 `.\mvnw.cmd`。

| 门禁 | 命令 | 覆盖范围 |
| --- | --- | --- |
| 定向 | `./mvnw test -Dtest=XxxTest` | 开发反馈，不能单独作为提交依据 |
| quick | `./mvnw test -Pquick` | 每次功能提交前通过 |
| 终端 | `./mvnw test -Pphase16-smoke` | 终端、渲染、输入解析改动叠加 |
| 全量 | `./mvnw test` | 跨模块、公共接口、持久化协议、发布前 |
| 格式 | `./mvnw spotless:check` | CI 检查，不自动改写源码 |
| 文件规模 | `python scripts/check-source-size.py` | 全部生产 Java 文件不超过 1000 行，无豁免清单 |
| 静态检查 | `./mvnw -DskipTests compile spotbugs:check` | 新增 SpotBugs 告警阻断 |
| 依赖 | `./mvnw dependency-check:check` | 已知漏洞与扫描失败均阻断 |
| 打包 | `./mvnw package -DskipTests` | 构建 JAR，不代表测试或静态检查通过 |

quick 排除 MCP server 管理、stdio/HTTP transport、ToolRegistry、WebFetcher 的 5 个测试类，以及 `benchmark/`。涉及这些路径时，补跑对应定向或全量测试。真实模型评测不属于 Maven 单元回归。

CI 在 Windows、Linux 上各跑 quick 和全量，分别保留 Surefire 与 JaCoCo 报告。静态检查与漏洞检查为独立必需 job；打包依赖这些 job 全部通过。新增 job 后，还需仓库管理员在受保护分支的 required checks 中选中对应检查；仓库文件不能代替远端分支保护设置。

## SpotBugs

保持现有 4.8.6.6 插件、Max/Low 规则。`failOnError=true`，基线为 `Config/quality/spotbugs-baseline.xml`，由 `35b3951a` 的实测报告提取具体实例哈希与定位信息；不按包、整类或告警类型豁免。

基线只记录存量，不代表存量告警已修复。清理代码时可移除对应条目；不得为通过构建重新生成基线接纳新增问题。修改后出现的新告警必须核实并修复，不能把“可能误报”当作无证据的排除理由。

本地完整报告为 `target/spotbugsXml.xml`。门禁验证应包含一次能通过的现状和一次被拦截的新增缺陷，不能只验证“配置文件存在”。

## 格式与文件规模

Spotless 从固定提交 `35b3951a` 开始约束新增或改过的 Java、POM 和 workflow：清理行尾空白、确保文件结束换行，保留现有缩进、换行风格、导入顺序和代码布局。它不是一次全仓风格重排；CI 只运行 `spotless:check`，不运行 `spotless:apply`。完整 Git 历史用于解析固定基线。

Java 生产文件目标为最多 1000 行。拆分按真实职责，不通过删除空行、把多行代码挤在一行、继承外壳或通用 Options 绕过限制。

## 覆盖率

JaCoCo 0.8.14 在测试进程启动时装载 agent，在 test 阶段生成 `target/site/jacoco/index.html` 和 `jacoco.xml`；所有 Surefire profile 保留 UTF-8 与 agent 参数。`append=false` 让当前单 fork 测试批次覆盖旧执行数据，避免本地先跑全量、再跑 quick / 终端时得到混合覆盖率。CI 各 suite 使用独立 job。没有百分比门槛，也不为凑数增加镜像实现的测试。

报告只描述本次已执行测试覆盖的代码。quick、全量、定向报告分开看；测试替身不证明真实模型或外部服务效果。

## 依赖与可复现构建

Dependabot 每周检查 Maven 和 GitHub Actions。OWASP Dependency-Check 的 CVSS 门槛为 0，不接受已识别漏洞；数据更新失败同样失败，不降级成“通过”。唯一数据源是 NVD 官方按年压缩 feed（包含 modified 更新），不依赖 API Key；使用插件已有的 `.meta` 更新时间检查。扫描缓存和报告放在 `target/`，不提交。

插件使用 12.2.2，修复 12.1.9 无法保存长 Mozilla CVE 引用 URL 的数据库缺陷。该失败是扫描设施问题，不能报告为依赖无漏洞；依据见 [官方变更记录](https://github.com/dependency-check/DependencyCheck/blob/main/CHANGELOG.md#version-1222-2026-05-03)。

Wrapper 固定 Maven 版本和发行包 SHA-256。POM 固定构建插件与归档时间；只对 `devcli-build.properties` 过滤 Maven 版本，提示词和其他资源不做变量替换。Banner 由该资源读取版本，启动脚本由 POM 读取产物名。字节一致性需在同一 JDK 和依赖环境中验证，不将版本锁定本身描述成已证明所有平台产物完全一致。

官方配置依据：[Maven Wrapper](https://maven.apache.org/tools/wrapper/index.html)、[SpotBugs](https://spotbugs.github.io/spotbugs-maven-plugin/spotbugs-mojo.html)、[Spotless](https://github.com/diffplug/spotless/tree/main/plugin-maven)、[JaCoCo](https://www.jacoco.org/jacoco/trunk/doc/maven.html)、[Dependency-Check](https://dependency-check.github.io/DependencyCheck/dependency-check-maven/configuration.html)。

## 行为证据

固定真实任务通过 JAR 的 `-p` 入口执行，见 [20 个 Java 任务回归入口](../benchmarks/swebench-cli/README.md)。模型执行完成、补丁非空、局部测试通过都不等于官方 SWE-bench resolved。公开评测与组件测试的边界见 `benchmark-evaluation.md`。

报告提交时的命令、退出状态、测试数量与跳过项；未执行的 CI 平台、外部扫描或付费模型任务必须明确标注。遇到失败先判断陈旧测试、产品回归或已确认的外部故障，不因测试失败扩大基线或删掉有效用例。
