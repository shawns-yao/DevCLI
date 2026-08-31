# Benchmark 测试 Runbook：怎么不再重复踩坑

> 目标：把"环境手工拼、用真实模型调试协议问题、失败原因靠翻日志"这三类时间黑洞，变成**预检自动拦截 + 协议离线单测 + 一键流水线**。

> **2026-08-30 边界修正**：当前 `swe-bench-run.ps1` 是三模式执行与 Docker 日志诊断脚本，不是官方 Multilingual evaluator/report 的完整适配。它的 `resolved` 是自定义判定，即使 `eval.sh` 和镜像来自官方，也不能直接作为官方成绩。工程测试顺序与正式指标统一见 [量化评测规范](benchmark-evaluation.md)。

本轮实验只执行量化评测规范中的四项单轮配对：SWE-bench `solo/delegate`、LongBench/RULER 上下文压缩、LongMemEval 记忆、AgentDojo/ToolSandbox 工具安全；RAG 和固定 `plan` 流水线暂停。只有项目功能真正接入对应链路并满足文档中的执行前硬门，才允许调用真实模型。

## 本轮配对入口

以 [本轮记录](benchmark-paired-run-20260830.md) 为准，下面的历史三模式 SOP 不用于本轮。

```powershell
# 已有 Maven 仓库、Python 虚拟环境和模型配置均复用
$env:MAVEN_OPTS='-Dmaven.repo.local=C:\Document\Maven\repository'
cmd /c 'mvn -B -q -DskipTests=false -Dtest=PairedContextDriverTest,MemoryEvidenceDriverTest test'
& Temp/swebench-venv/Scripts/python.exe scripts/test_paired_benchmarks.py

# LongBench 200题，每条件一次；恢复时跳过已完成和中断条目
& scripts/run-paired-context.ps1

# 只重算评分，不调用模型
& Temp/swebench-venv/Scripts/python.exe scripts/paired-context-benchmark.py score
& Temp/swebench-venv/Scripts/python.exe scripts/paired-memory-benchmark.py score
& Temp/swebench-venv/Scripts/python.exe scripts/memory-bm25-control.py

# 对已有补丁运行未修改的官方 SWE harness，复用镜像
& scripts/score-swebench-official.ps1 -InstanceIds apache__druid-13704
```

主 Python 没有 LongBench 评分依赖，但已有 `Temp/swebench-venv` 具备依赖，优先复用后者，不重新安装整套环境。LongMemEval-S 使用本机现有代理从官方固定版本下载，SHA-256 已对照官方 LFS 元数据验证；后续直接复用本地文件。所有新输入、评分与进度都在 `Test/` 下，原始集合在 `Data/raw/`；不要通过删除结果文件触发付费重跑。

记忆 reader 使用 `com.devcli.eval.MemoryReaderDriver <jobs.jsonl> <results目录> gpt-5.6-luna`；记忆检索使用 `MemoryEvidenceDriver <jobs.jsonl> <results目录>`。二者 classpath 复用 SWE 批次的 `dependency-classpath.txt` 和 `target/classes;target/test-classes`。judge 命令为 `& Temp/swebench-venv/Scripts/python.exe scripts/judge-paired-memory.py`，同模型同题只判一次；不要与多个快速 reader 同时启动以免触发网关限流。

## 1. 时间到底浪费在哪（根因复盘）

历史 javaparser-4538 单次运行记录中，solo 为 128s、delegate 为 221s；这些耗时不是本轮复测结果，也不证明官方 resolved。复盘重点是以下三类准备与排障开销：

| 类别 | 典型坑 | 为什么耗时 | 永久规避 |
|---|---|---|---|
| **A. 环境没固化** | 禁网容器没挂本地 `.m2`；兼容网关 HTTP 协议；Windows CRLF/BOM；driver 未 test-compile；classpath 手工维护 | 参数遗漏会造成重复运行 | 脚本已覆盖部分环境准备，仍缺完整版本/配置指纹与官方 report 接入，不能称为全部固化 |
| **B. 用最贵的方式调最便宜的 bug** | `tasks`/`steps` 键名撕裂、read_file 参数同传被拒、评审 JSON 字段缺失——这些是**纯字符串/JSON 解析问题**，却用"真实 LLM + 全链路 + 等 25 分钟"来发现 | 一次端到端 = 真金白银 + 数分钟等待，而等价单测只要毫秒 | **离线优先铁律**（见 §3），协议问题写契约单测，绝不烧模型 |
| **C. 失败不可观测 + 无一键复现** | driver 用 SILENT 流，plan 为什么失败只写进 `~/.devcli/logs/devcli.log`；三个模式每次手工 reset/跑/评分 | 定位一个失败点比修复还慢；步骤多易漏 | 已落地：确定性规划失败原因透出终态；流程脚本化，结果与 Token 自动落盘 |

**一句话根因**：不是"测试"慢，是**用端到端真实模型去调试框架协议、且环境与失败原因都没固化**。

## 2. 坑台账（症状 → 根因 → 措施）

| 症状 | 根因 | 措施 / 已落地位置 |
|---|---|---|
| 容器内 Maven 卡在下依赖、`Could not resolve` | 禁网容器没挂本地仓库 | `-Ddevcli.command.sandbox.maven.repository=<m2>` + `docker -v <m2>:/root/.m2/repository`（脚本内置） |
| OpenAI 兼容网关流中断/协议异常 | HTTP/2 协商问题 | 强制 `-Ddevcli.llm.http.protocol=HTTP_1_1`（脚本内置） |
| `edit_file` 一直 0 匹配/找不到 | Windows `core.autocrlf=true`，模型只给 LF | 已修：`FileToolProvider.alignEol` 按文件 EOL 对齐；`EditFileToolTest` 锁定 |
| legacy Plan 报"计划必须包含至少一个任务" | `PlanExecuteAgent` 使用的 `Planner` 原来只认 `tasks` | 已修：`Planner.parsePlan` 回退 `steps`，`PlannerTest` 锁定；Team benchmark 的 `PlanCoordinator` 原本已兼容两键，此项不是本次 plan 失败根因 |
| read_file 同时给行范围和字符范围被硬拒 | 两组互斥参数同传直接报错 | 已修：优先行范围、容错忽略字符组；`FileToolProviderPaginationTest` 锁定 |
| plan 每步都失败、Reviewer 轮数耗尽 | 2 轮不足以取证；放宽后又缺少强制收敛 | 默认 5 轮，最后一轮禁用工具并要求裁决 JSON；计划语义审查为建议，产物审查仅在硬检查实际通过后降为建议；无硬检查时仍可阻断，真实模型收敛效果尚未复测 |
| Windows 下 `execute_command` 报"无法识别的 Maven 参数 -Dxxx=8" | 已验证 `HOST_WARN` 命令经 PowerShell `-Command` 执行时参数语义可能变化；不是 `CommandGuard` 分词 | 已修：Windows 已验证命令改用 `cmd.exe /d /s /c`；指纹测试确认带引号纠正命令与原失败命令不同 |
| patch 应用失败 / 整文件被改 | PowerShell `>` 重定向写成 UTF-8 BOM 或 CRLF | 用 `cmd /c "git diff ... > patch"`（脚本内置），不要用 PS 重定向 |
| 改了 `src/test` 下 driver 不生效 | 只 compile 没 test-compile | 脚本统一 `compile test-compile` |
| 找不到主类 / classpath 错 | 手工 cp 漏 jar | 脚本用 `dependency:build-classpath` 自动生成绝对 classpath |
| 镜像/容器、工作副本脏、base 不对 | 手工管理 | preflight 校验镜像/daemon；每个模式只创建新的独立 workcopy，已存在目录直接拒绝覆盖，不执行破坏性 reset/clean |

## 3. 离线优先铁律（最重要）

**凡是不依赖模型"智能"的问题，一律离线复现，禁止用真实 LLM 端到端调试。**

- JSON 协议、参数校验、键名兼容、DAG 图校验、评审结果解析 → 喂**固定字符串**写单元测试，毫秒级、CI 可跑、零成本。
- 模型"会不会按协议输出"属于另一类：用**录制回放**（把真实响应存成资源，离线重放）而不是每次现调。
- 只有"模型真实推理/规划/编码质量"才值得烧真实额度，且先过离线层再上。
- 判断口径：**改完一个协议点，先问"能不能用一条固定输入的单测复现/锁定"，能就绝不端到端。**

## 4. 下次标准 SOP

```powershell
# 0) 一次性准备题目目录 <Instance>：task.txt(闭卷题面) / eval.sh(评分入口) / meta.json(含非空 expect) / base(目标仓库干净 clone, 固定 base commit)；Maven 依赖统一复用 `C:\Document\Maven\repository`
# 1) 日志诊断：先只跑 solo，确认 patch 与 Docker 测试日志，不作为官方成绩
pwsh scripts/swe-bench-run.ps1 -Instance <Instance> -Mode solo -M2 C:\Document\Maven\repository

# 2) 三模式诊断（预检→编译→独立副本→Agent→binary patch→自定义日志判定）
pwsh scripts/swe-bench-run.ps1 -Instance <Instance> -Mode solo,delegate,plan -M2 C:\Document\Maven\repository

# 3) plan 消融（需要时）：关闭计划语义建议 / 覆盖 Reviewer 轮数
pwsh scripts/swe-bench-run.ps1 -Instance <Instance> -Mode plan -PlanSemanticReview off -ReviewerIters 6

# 只跳过评分容器：加 -SkipEval；Agent 命令仍默认使用 Docker。评分镜像缺失自动拉：加 -AutoPull
```

结果落在 `<Instance>/runs-<时间戳>/`：`results.jsonl`（每题每模式一行，含用量、估算成本、容器退出码、类级期望）、各模式 `.out/.patch/*-run.log/*-eval.log`。脚本复用本地 `.env`、已存在的 Docker 镜像和 `C:\Document\Maven\repository`；每次测试仍会创建新的输出目录和目标仓库工作副本。评分容器只读挂载 `<Instance>` 并执行 `/instance/eval.sh`；自定义 `resolved=true` 要求退出码为 0、patch 已应用、无失败关键词且 `meta.expect` 匹配。这不校验官方 F2P/P2P 测试清单，也不产生官方 report。

当前脚本记录的 `wall_ms` 不含构建与评分，usage 缺失时写 0，不能解释为实际零用量。主模型与部分环境仅见 Driver 日志，完整角色配置、数据/评测器版本与 dirty diff 指纹尚未写入结果。正式运行前先补全这些字段，并验证空补丁负例和参考补丁正例能得到预期官方 verdict；参考补丁只供独立评分校验，不进入 Agent 可见工作区或提示词。

## 5. 结果纪律（避免结论失真）

- 正式结果只读取固定版本官方 evaluator 生成的 report；现有脚本日志判定不得替代它。区分环境失败、Agent 失败、未评分与官方未解决，不将缺失结果当成功，也不删除失败题后缩小分母。
- 不删失败样本、不只报成功；模型是第三方网关别名（如 gpt-5.6-sol）时**不等于官方型号**，报告写 provider=openai-compatible。
- 单题单次只是定性，**不能写"显著优于"**；下统计结论要多样本配对（resolved / wall time / token + McNemar、配对 bootstrap）。
- 运行清单必须记录 DevCLI commit/dirty diff、base、数据/评测器 revision、镜像 digest、各角色模型、脱敏配置、时间与原始报告；当前脚本只记录其中一部分，剩余是待实现项，不是已交付字段。不得把密钥原文写入配置快照。
