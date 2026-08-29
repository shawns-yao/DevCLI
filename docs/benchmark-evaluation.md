# DevCLI 量化评测

> **测试资产状态（2026-08-27）**：本文件中的“历史自建评测归档”只保留执行方法、原始结果和废弃原因，禁止作为当前能力、公开 benchmark 成绩或简历数字。旧的自建 CLI、订单 Saga、Checkout、对抗记忆、记忆晋升、长会话压缩、Agent 并发和合成 RAG 测试已退役；后续正式结果只能来自公开数据集及其官方 harness/evaluator。公开 benchmark 首轮在清理旧测试后按“每个数据集 1 个样本”重新接入，未完成前不得引用历史数字替代。

## 评测范围

评测框架覆盖四条核心链路：

| 链路 | 数据来源 | 核心指标 |
| --- | --- | --- |
| RAG | CodeSearchNet Java 公共 test split | Recall@5、MRR@5、nDCG@5 |
| Agent | SWE-bench Multilingual Java；SWE-bench Lite 仅保留兼容适配 | 官方 resolved rate、FAIL_TO_PASS、PASS_TO_PASS |
| Memory | LongMemEval-S | 官方 normalized answer hit、官方 judge accuracy |
| Context Compression / Long Context | LongBench v2；RULER v1 仅作诊断 | 官方任务指标、RULER 官方评分 |

CodeSearchNet、SWE-bench Lite、LongMemEval、LongBench 和 RULER 属于公开集合。旧项目内评测只保留历史归档，禁止继续运行或描述成当前成绩。公开数据版本、哈希、许可和本地文件边界记录在 `Config/public-benchmarks.json` 与 `Data/manifest/public_benchmark_sources_20260716_v1.md`。

## 历史自建评测归档（已废弃）

以下内容仅用于解释旧测试怎样做、曾经得到什么结果，以及为什么不再使用。对应测试代码、契约资源和结果文件已退役；新会话不得根据这些数字判断 ReAct、`/plan` 或记忆/压缩能力。

### 自建 Agent 五任务

- **怎么做**：在空工作区预置 5 个项目内 Java CLI 任务（`logops`、`salesops`、`incidentops`、`ordermvc`、`banking`），只暴露 `read_file`、`write_file`、`list_dir`，由隐藏验证器编译并执行行为检查；ReAct 与 Planner/Worker/Reviewer 使用同一任务和工具边界。
- **历史结果**：不同日期和端点曾出现 `0/5`、`3/5`、`1/5` 等互相冲突的结果；例如 2026-07-16 的 Krill AI `gpt-5.5` 运行中，ReAct 为 `3/5`、平均隐藏检查完成率 `94%`，Planner/Worker/Reviewer 为 `1/5`、`76%`。
- **为什么废弃**：任务是项目自建题，样本只有 5 个，隐藏检查和任务难度由项目自行定义，不能代表真实 Issue 修复能力；不同端点、协议修复和网络中断导致结果不稳定。

### 订单履约 Saga

- **怎么做**：预置只读 Java 契约，要求实现库存、支付、配送、通知、审计和最终履约编排六个模块；Multi-Agent 将前五个模块拆成可并行步骤，最终步骤负责集成；模型结束后由隐藏验证器执行 30 项检查。
- **历史结果**：2026-07-16 单次有效运行中，ReAct `27/30`（`90.0%`、`192.8 秒`），Planner/Worker/Reviewer `30/30`（`100.0%`、`725.1 秒`，约 `3.76×`）。
- **为什么废弃**：这是项目自建订单场景，不是公开 benchmark；只有一次有效配对，且首次运行曾因工具白名单泄漏而作废。它只能说明某个自建可拆分场景的冒烟现象，不能证明通用 Multi-Agent 优势。

### Checkout Saga

- **怎么做**：预置多租户结账契约，验证访问策略、库存、支付、配送、通知 Outbox、审计和最终编排的幂等、补偿与并发。
- **历史结果**：2026-08-15 的 DeepSeek 运行被标记为 `invalid`，未形成可用公开结论。
- **为什么废弃**：与订单 Saga 相同，属于自建业务题；结果受自定义契约、隐藏验证器和端点状态影响，不具备外部可比性。

### 自建 Memory / Compression / Concurrency / 合成 RAG

- **怎么做**：使用项目内手写事实、手写对话、手写并发时序和手写 Java 调用链，分别检查记忆写入/召回、压缩保真、旧 turn 隔离和检索排序。
- **历史结果**：曾报告记忆 `Recall@5 82.0%`、注入命中率 `62.0%`、压缩保真率 `93.3%`、并发旧结果覆盖 `0/45`，以及合成 RAG 的 `Recall@5 1.0` 等数值。
- **为什么废弃**：题目、事实、相关性标签和验收规则均由项目自行编写，存在主观性和数据泄漏风险；这些数字只适合开发期回归，不适合作为公开能力证据。公开集合接入后，正式结果必须使用原始数据和官方评分器。

## 公开集合接入状态

当前保留 CodeSearchNet Java、SWE-bench Lite、LongMemEval Oracle Cleaned、LongBench v1 和 RULER v1 的公开数据适配代码。历史小样本结果均已撤销，不作为当前成绩。下一轮按每个公开集合 1 个官方样本分别验证数据下载、任务适配、真实模型调用和官方评分链路；未得到官方 evaluator/harness 输出前，状态统一记为“尚无有效结果”。

目标正式集合为 SWE-bench Multilingual Java、CodeSearchNet Challenge、LongMemEval-S、LongBench v2 与 RULER v1。尚未完成的升级不得写成已接入。

## 指标定义

- Recall@5：前 5 个结果覆盖的唯一相关目标数除以相关目标总数。
- MRR@5：第一个相关结果排名的倒数；前 5 个结果没有相关项时记 0。
- nDCG@5：按排名折损的相关性收益除以理想排序收益；重复命中同一相关目标只计一次。
- Agent resolved rate：官方 harness 判定 resolved 的 Issue 数除以总 Issue 数。
- LongMemEval accuracy：只使用官方 normalized answer hit 和官方 LLM judge 输出。
- LongBench / RULER：使用对应官方 evaluator，不用项目内关键词规则代替。

## 复现命令

RAG 公共数据集：

```powershell
mvn -q "-Dtest=RagRetrievalBenchmarkIT" -DskipTests=false `
  "-Ddevcli.benchmark.rag=true" `
  "-Ddevcli.benchmark.rag.codesearchnet=true" `
  "-Ddevcli.benchmark.rag.codesearchnet.length=50" test
```

公开数据完整性与适配：

```powershell
mvn -q "-Dtest=PublicBenchmarkReadinessIT" -DskipTests=false `
  "-Ddevcli.benchmark.public.datasets=true" `
  "-Ddevcli.benchmark.public.sample.limit=5" test
```

LongMemEval、LongBench 与 RULER 首轮真实样本：

```powershell
mvn -q "-Dtest=PublicLongContextBenchmarkIT" -DskipTests=false `
  "-Ddevcli.benchmark.public.longcontext=true" `
  "-Ddevcli.benchmark.public.limit=3" test
```

RULER 固定种子数据生成：

```powershell
mvn -q "-Dtest=RulerDatasetGenerationIT" -DskipTests=false `
  "-Ddevcli.benchmark.ruler.generate=true" `
  "-Ddevcli.benchmark.ruler.samples=3" `
  "-Ddevcli.benchmark.ruler.length=4096" test
```

SWE-bench Lite Linux harness 镜像：

```powershell
$harness = Get-ChildItem Data/raw/public-benchmarks/official-harnesses/swebench-harness -Directory |
  Select-Object -First 1

docker build -t devcli/swebench-harness:f7bbbb2 `
  -f Config/swebench-harness.Dockerfile $harness.FullName
```

SWE-bench Lite 单样本补丁生成：

```powershell
mvn -q "-Dtest=SweBenchLiteAgentBenchmarkIT" -DskipTests=false `
  "-Ddevcli.benchmark.swebench=true" `
  "-Ddevcli.benchmark.swebench.limit=1" `
  "-Ddevcli.benchmark.swebench.mode=single" test
```

`SweBenchDriver` 的 `solo` / `delegate` / `plan` 对照只改变编排方式。三种模式统一使用
`read_file`、`write_file`、`edit_file`、`list_dir`、`grep_code`、`execute_command` 和
`read_tool_result` 白名单，并关闭长期记忆注入；`delegate` 仅额外开放 `delegate_task`。
驱动不开放 `search_code`，避免本机残留索引成为未记录变量。新增工具不会自动进入评测面。驱动不再强制覆盖命令沙箱模式，默认沿用生产 `DOCKER`；只有运行方显式设置 `DEVCLI_COMMAND_SANDBOX_MODE=HOST_WARN` 或对应系统属性时才使用主机白名单。每次运行记录 Java 版本、实际沙箱模式、LLM HTTP 协议和 Maven 仓库使用默认或显式配置；仓库绝对路径不写入日志，避免泄露开发机目录。

使用固定 Dockerfile 构建 Linux 官方 harness 后，可追加 `-Ddevcli.benchmark.swebench.evaluate=true`。Windows Python 缺少 `resource` 模块，不能直接运行官方 harness；必须使用 Linux Docker 或 Linux 主机。

若 Java 访问 HuggingFace 需要本地代理，应额外传入标准 JVM `https.proxyHost` 和 `https.proxyPort` 参数。报告默认写入 `target/benchmark-reports/`；旧的跨自建任务聚合器已删除，公开 benchmark 后续按各自官方结果格式单独汇总。

## 复现边界

- 真实 LLM、Embedding 和 Reranker 会产生费用，并受模型版本、端点负载和随机性影响。
- CodeSearchNet 接入仍需用官方相关性标注和固定样本重新验证，旧 50 条适配性数字已撤销。
- Agent 正式结果必须来自同一公开 Issue 上的 ReAct 与 `/plan` 配对运行。
- LongMemEval 的 normalized answer hit 只是代理指标；只有官方 `evaluate_qa.py` judge 结果才能称为 LongMemEval accuracy。
- LongBench 和 RULER 必须同时写明实际子任务、上下文长度和样本量，不能外推到完整集合。
- SWE-bench 必须使用官方 Docker harness 的 resolved 结果，不能根据补丁非空、编译成功或模型自述判断成功。
- 公开简历表述应同时写明数据集、修订版本、样本量、子任务、模型和评测日期。
