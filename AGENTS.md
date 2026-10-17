# AGENTS.md

仓库首读入口。实际行为以代码为准，本文只保留规则和导航；详细实现见 [架构参考](docs/agents-reference.md)，演进设想见 `ROADMAP.md`。

## 项目与入口

- DevCLI 是 Java Agent CLI，使用 Maven、JLine、Jackson、SQLite、MCP；不增加单点登录、集中策略或遥测等企业功能。
- 运行要求 Java 17+；构建和测试要求 JDK 21。使用仓库 Maven Wrapper 3.9.11，生产源码以 `--release 17` 编译。
- 版本由 `pom.xml` 生成 `devcli-build.properties`，Banner 和启动脚本读取该版本，不另写常量。
- `Main` 分发交互 CLI、`-p` 无交互 JSON 和 Runtime API。`-p` 不读取 stdin，不启动浏览器、MCP 或后台服务，需人工审批的动作拒绝。
- 默认 provider 为 Anthropic。配置读取顺序和环境变量见 [README](README.md) 与 [配置目录](Config/)；`.env.example` 只放无秘密示例，本地 `.env` 不提交。

## 代码质量

- 企业级指正确、清晰、可验证，不指更多抽象或防御分支。
- 只为真实外部输入和已发生的错误加校验；内部合同保证的条件不重复检查。
- 不新增未要求的开关、兼容层、降级分支或重试；异常没有新的处理方式时向上传递。
- 替换实现时同次删除旧路径；能删代码完成的任务优先删代码。
- 每个生产 Java 文件最多 1000 行，由 `scripts/check-source-size.py` 全量检查。按实际职责拆分，不压缩代码布局或增加空壳规避。
- 新代码不得新增 SpotBugs 告警；存量基线只可删除已解决实例，不扩大忽略范围。
- 复用现有组件；不为假想扩展增加接口、Options 或第二份状态。
- 包依赖由 `PackageBoundaryTest` 检查，不通过放宽规则掩盖违规。
- 公共 API、依赖、协议、持久化或安全边界发生变化时新增 [ADR](docs/adr/README.md)，编号只递增。
- 同步修改受影响注释和当前行为文档；历史 ADR 保留当时取舍并注明后续替代。
- 交付附 `git diff --stat` 增删行数，新增明显多于删除时说明必要性。不加入生成署名。

## 状态与执行边界

- 只追加的会话事件保存事实，模型上下文是渲染视图；压缩记录不删除原始事件。工作计划由 `update_plan` 维护，执行证据不能由模型计划替代。
- ReAct、`/plan` 与委派共用执行内核；`/plan` 保留确认、依赖调度和恢复，步骤只经 `delegate_task` 执行。旧 PlanExecuteAgent 路径已删除。
- 主 Agent 默认写入；写角色仅同步且独占运行，后台仅只读。角色来自内置、用户与项目定义，能力只能比父 Agent 更窄。
- 工具权限、破坏性、幂等性、缓存资格由合同显式声明，不按工具名推断。模型工具定义在循环中固定；MCP 搜索返回 schema，固定入口执行仍走目标工具完整管线。
- 模型编辑工具为 `write_file` / `edit_file`，删除为 `delete_files`；补丁编译器仅作内部 API。编辑后沿用既有 LSP hook。
- 删除仅接受明确项目相对普通文件，不递归或展开通配符。最多 500 个、合计 20MB、单文件 5MB；审批绑定预检哈希。未获路径授权或达到阈值时逐次确认，不复用全部批准；细节见架构参考。
- 长期记忆以主题 Markdown 为权威，每轮只注入索引，正文经 `read_memory` 受限读取。模型代码定位使用 glob / grep / read_file；显式 `/index`、`/search` 保留。
- 隔离工作区和 PatchSet 保持路径保护、租约、版本闸门、硬验证与提交锁；工具成功、模型报告和真实验收分别记录。
- 普通主 Agent 主机命令不自动转入沙箱；已有可信项目、权限规则与审批顺序见 ADR 0021。不得扩大本次授权。

## 验证与交付

先实现，再按风险验证；后续新任务不先运行测试，测试时机遵循用户最新要求。全面检测、数据库、项目服务、子代理、Git 提交和推送分别遵循会话中已取得的授权，不重复确认。

```powershell
.\mvnw.cmd compile -DskipTests
.\mvnw.cmd test -Pquick
.\mvnw.cmd test -Pphase16-smoke
.\mvnw.cmd test
.\mvnw.cmd spotless:check spotbugs:check -DskipTests
python scripts/check-source-size.py
.\mvnw.cmd package -DskipTests
java -jar target/devcli-16.1.0-SNAPSHOT.jar -p "任务"
```

Unix 使用 `./mvnw`。不使用 Git Bash 的系统 Maven 脚本调用 Windows JVM。`package` 默认执行测试；SpotBugs 在 `verify` 或显式 `spotbugs:check` 执行。仅构建时显式 `-DskipTests`。提交前门禁与例外、覆盖报告、Windows/Linux CI 和依赖扫描见 [质量门禁](docs/quality-gates.md)。

固定公开 Java 任务及 `-p` 运行器见 [真实任务回归](benchmarks/swebench-cli/README.md)。本轮只接线，不付费运行；官方 resolved 只能取官方 harness 报告。局部测试、CLI 完成和完整能力验收不得混称。

提交按功能划分，提交信息用简洁中文 Conventional Commits；不混入无关用户文件。提交、推送须有对应授权，不擅自 force push。交付区分已实现、已验证、未执行和失败设施，不用配置存在代替通过证据。

## 文件与数据

| 用途 | 实际位置与提交规则 |
| --- | --- |
| 源码、资源 | `src/main/java/`、`src/main/resources/`，提交；不把日志或运行数据写入其中 |
| 测试与固定公开样本 | `src/test/`、`benchmarks/`，提交；公开样本附修订、来源、哈希和选择规则 |
| 可复用脚本 | `scripts/`，提交；一次性诊断放 `target/`，正式流程不依赖它 |
| 配置和模板 | `Config/`、`.env.example`、`.mvn/`，提交；真实 `.env`、密钥和个人工具配置不提交 |
| 文档与规范 | 根说明、`docs/`；任务记录只维护已授权的指定位置，不自动创建 TODO |
| 数据清单 | `Data/manifest/`，提交；`Data/raw/`、`Data/processed/` 本地保留，不跟踪、不自动清理 |
| 构建、报告与缓存 | `target/`、`logs/`、`Log/`、`Temp/`，不提交，可按用途重建；运行数据清理须获授权 |
| 会话与图片 | 默认 `~/.devcli/runtime/`，事件与归档图片随会话保存，不提交；删除后不可保证重读 |
| 记忆与委派 | 默认 `~/.devcli/memory/`、`~/.devcli/delegations/`，不提交，不因目录是本地而擅自删除 |
| 数据库 | Runtime / RAG 使用本地 SQLite，结构在对应 schema 类；不连接业务数据库，测试仅用获准临时库 |
| 前端与部署 | 本项目为 CLI，无常规 Web 前端；Runtime API 不代表授权启动服务或部署 |

根目录评测 JSON、`logs/` 和 `Data/processed/` 中已有生成物仅停止跟踪，保留本地原件。`.codegraph/`、IDE 缓存和无关 `pelican-bicycle.html` 不纳入功能提交。

## 导航

| 任务 | 入口 |
| --- | --- |
| CLI / 无交互 | `Main`、`CliApplication`、`PrintCommandLauncher` |
| 执行 / 委派 | `AgentExecutionEngine`、`Agent`、`DelegationSession`、`DelegationChildLoop` |
| 计划 / 恢复 | `AgentOrchestrator`、四个 Coordinator、`AgentCheckpoint` |
| 工具 / 权限 | `ToolRegistry`、`ToolPipelineAssembly`、`BuiltInToolPolicy`、`HitlToolRegistry` |
| 上下文 / 记忆 | `ConversationJournal`、`ConversationHistoryCompactor`、`SessionMemory`、`MemoryManager` |
| 存储 / 检索 | `RuntimeThreadStore`、`RuntimeSchema`、`VectorStore`、`VectorIndexState` |
| 模型 / MCP / Skill | `llm/`、`mcp/`、`skill/` |
| 细节 / 评测 | [架构参考](docs/agents-reference.md)、[评测边界](docs/benchmark-evaluation.md)、[ADR](docs/adr/README.md) |

默认中文简短沟通；说明结果、必要证据和实质限制，不堆流程输出。
