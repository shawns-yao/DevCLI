# 可验证代码质量实施计划

日期：2026-10-17。状态：进行中。

目标：补齐可机械执行的质量门禁，清理确定无调用的旧路径，保持现有产品范围。

按功能在当前会话顺序实施并提交、推送；不启动子代理。保留无关的 `pelican-bicycle.html`。

## 1. 构建与门禁

- [x] `.mvn/wrapper/`、`mvnw`、`mvnw.cmd` 固定 Maven 3.9.11 与发行包 SHA-256。
- [x] `pom.xml` 启用 SpotBugs 失败门禁；`Config/quality/spotbugs-baseline.xml` 仅排除实测 533 条存量实例，不按整类或告警类型豁免。已验证新增空指针告警被拒绝、删除临时缺陷后检查通过。
- [x] Spotless 检查行尾空白和文件结尾，保留导入顺序与布局，不引入重排风格的 formatter。
- [x] JaCoCo 生成覆盖报告，不设百分比门槛；保留 Surefire 的 UTF-8 参数和全部 profile。
- [x] CI 配置 Windows/Linux 的 quick 与全量矩阵、静态检查、依赖检查和打包；远端执行结果另行记录。
- [x] Dependabot 每周检查 Maven 和 Actions；统一 Banner 与 Maven 版本的来源。
- [ ] 新文件最多 1000 行；用现有大文件清单约束存量，拆分完成后删除豁免。

验证：wrapper 版本、Spotless 检查、SpotBugs 基线与新增告警拒绝、全量测试和覆盖报告、打包产物版本、质量脚本拒绝样例。Windows 本地执行，Linux 由 CI 执行，结果分别报告。

初始门禁验证：全量 2314 项（27 跳过）、quick 2232 项（21 跳过）、终端 104 项均无失败。新版本 JAR 的参数错误返回 UTF-8 JSON / 退出码 2，无模型调用；同一 JDK 下两次打包 SHA-256 一致。日志在 `target/quality-*.log`。匿名 NVD API 首次更新过慢，改用插件原生支持的 NVD 官方压缩 feed；扫描结果仍待记录，不宣称依赖无漏洞。

## 2. 存量清理与职责拆分

- [ ] 核实 `PlanExecuteAgent` 的生产入口、工厂和测试；无入口才删除及同步删除专用组件和陈旧测试。
- [ ] `SubAgent` 仍用于 `/plan` 的规划和独立审查，不因类名删除。RAG 的显式 `/index`、`/search` 与公开评测有实际调用，保留有效路径。
- [ ] 逐个拆分超 1000 行源码：SessionMemory、AgentCheckpoint、MemoryManager、VectorStore、DelegationSession、ConversationHistoryCompactor、RuntimeThreadStore、ToolRegistry、PlanExecuteAgent、Agent、CliApplication。
- [ ] `logs/`、`Data/processed/` 等生成物停止跟踪并补忽略；保留来源清单和固定样本，不清理本地原始数据。

验证：调用者搜索与编译、对应模块定向测试、包边界测试、quick 门禁；核心协议跨包拆分后执行全量。

## 3. 真实任务入口与文档

- [x] 固定 20 个公开 SWE-bench Multilingual Java 真实任务；官方修订、原始 Parquet 哈希、选题方法与任务原文存档。执行器仅通过 JAR 的 `-p` 入口，与组件测试、官方评分结论分开。
- [x] dry-run 校验固定样本，控制器测试禁止派生模型进程；本轮不付费运行，不报告能力通过。实际工作区先准备，再由用户按现有 CLI 信任与权限流程配置，执行器不绕过审批。
- [ ] 精简根 `AGENTS.md` 为规则和导航；实现细节归并到 `docs/agents-reference.md`，门禁集中到 `docs/quality-gates.md`。
- [ ] 同步启动脚本、README、注释和必要 ADR；不保留已删除路径的当前行为说明。

交付：验证命令与真实结果、未验证范围、分功能提交、远端同步状态、`git diff --stat` 增删行数。新增较多时说明其必要性。
