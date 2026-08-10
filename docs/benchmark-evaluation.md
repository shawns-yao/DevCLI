# DevCLI 量化评测

## 评测范围

评测框架覆盖四条核心链路：

| 链路 | 数据来源 | 核心指标 |
| --- | --- | --- |
| RAG | CodeSearchNet Java 公共 test split；项目内合成调用链集合 | Recall@5、MRR@5、nDCG@5 |
| Agent | 5 个带隐藏检查的 Java CLI 编程任务；SWE-bench Lite | 任务成功率、隐藏检查完成率、官方 resolved rate |
| Memory | 25 条写入策略样本、12 条语义召回与注入查询；LongMemEval Oracle Cleaned | 写入准确率、Recall@5、代理答案命中率、官方 judge accuracy |
| Context Compression / Long Context | 230k token 长会话；LongBench v1；RULER v1 | 事实保真率、官方任务指标、RULER string match |

CodeSearchNet、SWE-bench Lite、LongMemEval、LongBench 和 RULER 属于公开集合。项目内 Agent、Memory 和 Context Compression 受控任务继续单独报告，禁止把代理指标、子集结果或项目内样本描述成完整公开榜单成绩。公开数据版本、哈希、许可和本地文件边界记录在 `Config/public-benchmarks.json` 与 `Data/manifest/public_benchmark_sources_20260716_v1.md`。

## 2026-07-13 基线结果

运行环境：Java 21、Windows、Anthropic Messages 兼容端点，实际模型 `glm-5.2`；Embedding 使用 `Qwen/Qwen3-Embedding-4B`，Reranker 使用 `Qwen/Qwen3-Reranker-8B`。

### RAG（旧适配性结果，不作为简历指标）

CodeSearchNet Java 公共 test split 采样 50 条，Top-K 固定为 5：

| 指标 | Semantic baseline | DevCLI 路由后检索 |
| --- | ---: | ---: |
| Recall@5 | 1.0000 | 1.0000 |
| MRR@5 | 0.9700 | 0.9900 |
| nDCG@5 | 0.9779 | 0.9926 |

该轮适配器把查询使用的函数文档同时写入了候选源码 JavaDoc，候选池也只有 50 个函数，纯语义基线 Recall@5 已达到 1.0。因此该表只证明数据下载、索引、检索和指标链路可以运行，不能用于证明 DevCLI 在公开代码检索任务上达到 100% Recall，也不得继续写入简历。

2026-08-09 已重构复跑协议：索引源码排除查询文档，默认候选池扩大到 1,000 个函数、查询集扩大到 200 条，代码内容去重，使用固定随机种子并按仓库轮转抽取查询；报告额外记录候选规模、随机种子、样本 ID、仓库和泄漏保护状态。新结果需由 `Temp/run-rag-benchmark.mjs` 在本地 VSCode 终端执行后补充。

### Memory

2026-08-09 使用 OpenAI-compatible `gpt-5.6-terra` 重做对抗型长期记忆评测：120 次写入决策、50 个跨会话检索场景、10 个过期场景和 25 轮噪声。结果为写入准确率 100%、Recall@5 82.0%、上下文注入命中率 62.0%、召回到注入传递率 75.6%、过期过滤率 100%。同主题更新仅 1/10 召回成功；高相似干扰场景 10/10 在 Top-5 召回目标，但 0/10 通过最终注入验收。旧版规则模板得到的四项 100% 结果作废。

当前实现已明确区分两个环节：Recall@5 判断正确记忆是否进入前五条；上下文注入命中率判断正确事实是否进入最终上下文；召回到注入传递率统计已召回事实中实际成功注入的比例。旧版 12 个查询的两个 91.7% 数值不再作为正式结论。

### Agent 并发与状态一致性

真实 Runtime API 评测固定执行 45 个案例：5 类长任务、20%/50%/80% 三个纠偏时间点、每种组合重复 3 次。45 个案例中，旧 turn 在新 turn 启动后产生的残留 reasoning、消息、工具结果或完成事件为 0；42/45 完整观察到纠偏标记，3 个案例没有观察到新响应标记。简历可以使用“45 次测试中 0 次旧结果覆盖新状态”，但不能把任务整体成功率写成 100%。

旧版 12 查询结果为写入准确率 96.0%、Recall@5 91.7%，仅保留为历史记录。最新对抗型结果以本节前述 120 次写入决策和 50 个跨会话检索场景为准：写入准确率 100%、Recall@5 82.0%、上下文注入命中率 62.0%。

### Context Compression

2026-08-10 使用 `gpt-5.6-terra` 重构并重新执行：先通过 4 轮真实 `Agent.run` 对话建立连续会话，再执行 5 轮正式对话；每轮输入均跨过受控的 6,000 token 自动压缩阈值，并由生产入口 `Agent.maybeCompactHistory` 触发一次摘要更新。30 条预先固定事实保留 29 条，自动问答保真率为 96.7%。该结果未经过人工复核，不等同于 236k 生产窗口结果。

| 难度层 | 通过数 |
| --- | ---: |
| EASY | 5/5 |
| MEDIUM | 5/5 |
| HARD_ENTITY | 4/5 |
| HARD_OVERRIDE | 3/3 |
| COMMAND_PARAM | 4/4 |
| PATH_VERSION | 4/4 |
| BUSINESS_CONSTRAINT | 4/4 |

唯一失败位于 HARD_ENTITY 类。旧版 236k 历史反复调用压缩器的 21/30、70.0%，以及更早的 17/18、94.4%，均不再作为当前简历结论。

### Agent

每个模式执行同一组 5 个任务，任务成功要求 LLM 执行完成且全部隐藏检查通过。为避免本机 Docker daemon 状态污染模型能力指标，受控 Agent benchmark 不向模型暴露 `execute_command`，Multi-Agent 的 Pre-Review 编译交由测试侧隐藏验证器统一执行；生产运行时仍保持隔离命令和 Pre-Review 强制 Docker、禁止主机回退：

| 模式 | 任务成功率 | 隐藏检查平均完成率 | 平均隐藏失败率 |
| --- | ---: | ---: | ---: |
| 单 Agent | 20.0%（1/5） | 20.0% | 80.0% |
| Planner/Worker/Reviewer | 0.0%（0/5） | 0.0% | 100.0% |

该结果不能作为 Multi-Agent 优势写入简历。失败证据显示两个系统性问题：Planner 有时违反仅输出 JSON 的协议，导致计划无法解析；部分计划把空工作区检查拆成前置步骤，空结果被判定为步骤失败，后续实现任务全部跳过。单 Agent 仅 `ordermvc` 通过 15/15 隐藏检查，其余任务没有形成可编译交付物。该表属于 2026-07-14 协议修复前基线，只用于暴露缺陷。

## 2026-07-16 受控 Agent 复跑

使用同一 `glm-5.2`、每任务每模式 1 次、LLM 重试 1 次、最大输出 4096 token。任务成功仍要求全部隐藏检查通过：

| 任务 | 单 Agent | Planner/Worker/Reviewer |
| --- | ---: | ---: |
| logops | 0/10 | 9/10 |
| salesops | 0/10 | 0/10 |
| incidentops | 0/10 | 0/10 |
| ordermvc | 0/15 | 7/15 |
| banking | 0/20 | 0/20 |

| 模式 | 任务成功率 | 隐藏检查平均完成率 | 平均耗时 |
| --- | ---: | ---: | ---: |
| 单 Agent | 0.0%（0/5） | 0.0% | 123.0 秒 |
| Planner/Worker/Reviewer | 0.0%（0/5） | 27.33% | 543.2 秒 |

修复后的编排链路已经能在 logops 和 ordermvc 产生真实文件，但结果仍不具备简历价值。主要失败包括 Planner 输出在 token 上限处截断、Provider 忽略命名工具选择、Worker 只返回设计说明或不完整文件，以及 Reviewer 链路耗时过长。当前结论是评测框架有效，Agent 交付可靠性仍未达标。

### OpenAI 兼容端点复测

Krill AI 端点会在多个流式分片中重复发送完整工具名和完整参数，而不是只发送增量片段。旧聚合逻辑直接拼接，导致 `write_filewrite_file`、重复 JSON 参数和未知工具拒绝。修复后同时支持完整快照、累积快照与标准增量分片。

使用 `gpt-5.5` 和同一组 5 个隐藏任务完整复跑：

| 任务 | 单 Agent | Planner/Worker/Reviewer |
| --- | ---: | ---: |
| logops | 10/10 | 9/10 |
| salesops | 8/10 | 8/10 |
| incidentops | 10/10 | 2/10 |
| ordermvc | 15/15 | 15/15 |
| banking | 18/20 | 18/20 |

| 模式 | 任务成功率 | 隐藏检查平均完成率 | 平均耗时 |
| --- | ---: | ---: | ---: |
| 单 Agent | 60.0%（3/5） | 94.0% | 169.1 秒 |
| Planner/Worker/Reviewer | 20.0%（1/5） | 76.0% | 470.7 秒 |

单 Agent 在当前样本中明显优于 Multi-Agent。Multi-Agent 的主要退化来自 incidentops，只通过 2/10；说明更多角色和审查轮次没有自动转化为更高正确率，反而放大了步骤间语义偏差。该结果可以作为真实评测结论，但样本量仍只有 5 个项目内任务，不能外推为通用 Agent 排名。

### Saga 协作场景

现有 5 个 CLI 任务以单文件或紧耦合实现为主，不适合验证任务拆解、并行模块开发和最终集成的收益。新增订单履约 Saga 场景，向两种模式提供同一份只读 Java 契约，并要求实现库存、支付、配送、通知、审计和履约编排六个模块。Planner/Worker/Reviewer 模式应把前五个模块拆为可并行步骤，最终履约编排依赖这些模块。

隐藏验证固定为 30 项，覆盖 3 项架构约束、17 项模块行为、4 项正常流程、3 项反向补偿、2 项幂等和 1 项并发检查。模型只能使用 `read_file`、`write_file` 和 `list_dir`；该白名单会随隔离项目 ToolRegistry fork 保持，不允许 Worker 在子工作区重新获得 `execute_command`。验证器在模型结束后使用 JDK 编译器和隔离类加载器检查，不依赖模型自述。真实模型运行通过 `-Ddevcli.benchmark.saga=true` 显式启用，报告写入 `target/agent-benchmark/saga-run-*/saga-collaboration-benchmark.json`。

Krill AI `gpt-5.5` 单次有效运行结果：

| 模式 | 隐藏检查 | 完成率 | 耗时 |
| --- | ---: | ---: | ---: |
| 单 Agent | 27/30 | 90.0% | 192.8 秒 |
| Planner/Worker/Reviewer | 30/30 | 100.0% | 725.1 秒 |

Multi-Agent 在该场景提升 3 个检查、10 个百分点，并完成全部补偿、幂等和并发要求；代价是耗时增加 532.3 秒，为单 Agent 的 3.76 倍。单 Agent 未通过支付退款后活动授权状态、配送取消后活动配送状态，以及同一幂等键对应不同请求的处理。该结果首次证明当前编排链路在可拆分模块和最终集成任务上取得正确率收益，但目前只有 1 次运行，不能外推为统计稳定结论。首次运行因隔离 ToolRegistry fork 丢失测试白名单而允许 Worker 看到 `execute_command`，其 Multi-Agent 结果已作废，不纳入该表。

2026-08-09 使用 `gpt-5.6-terra` 重新执行三轮。最近一轮多智能体完整结束并通过 30/30，单 Agent 产物通过 28/30，但单 Agent 模型链路未完整结束；前两轮也出现单侧网络中断。因此本次没有形成双方均完整结束的有效配对结果，不能替换上表历史数据，也不能计算新的模式收益。

## 2026-07-16 公开集合首轮样本

首轮用于验证下载、适配、真实模型调用和官方指标对接，不代表完整集合成绩。模型与当前 `.env` 中的 Anthropic Messages 兼容配置一致。

| 集合 | 样本 | 首轮结果 | 指标性质 |
| --- | ---: | ---: | --- |
| LongMemEval Oracle Cleaned | 3 | 66.7% | normalized answer hit 代理指标；官方 judge 待执行 |
| LongBench v1 `passage_count` | 3 | 33.3% | 官方 count score |
| LongBench v1 `passage_retrieval_en` | 3 | 100.0% | 官方 retrieval score |
| RULER v1 `niah_single_1`，4K | 3 | 100.0% | 官方 string_match_all |

12 次模型调用没有请求失败。当前 Provider 没有返回 input token usage，报告会使用 DevCLI token 估算并写入 `input_tokens_estimated=true`，避免把缺失值误写成 0 成本。

同日使用 Krill AI `gpt-5.5` 重跑相同样本：LongMemEval normalized answer hit 为 66.7%，LongBench 原始平均为 16.7%，RULER 原始 string match 为 100%。该轮 4/12 次调用被端点以 cybersecurity 风险拦截；端点还会重复发送完整 content，导致 `8` 变成 `88`、`Paragraph 8` 重复两次、RULER 答案重复。除 LongMemEval 的成功样本外，本轮 LongBench 与 RULER 原始值暂不用于模型能力比较，需先完成 content 快照兼容后重跑。

SWE-bench Lite 已完成 300 条测试集、固定版本和官方 harness 接入。2026-07-16 的 `astropy__astropy-12907` 单样本生成了 predictions，但补丁只新增复现脚本，没有修改目标源码。官方 harness 已修复 fixtures 导入和 `namespace=none` 本地镜像构建参数；基础镜像构建连续两次因 Ubuntu archive 返回 HTTP 503 中断，因此当前没有有效 resolved 结果。最终成功率仍必须由 FAIL_TO_PASS 与 PASS_TO_PASS 得出，不能用非空补丁数量代替。

## 指标定义

- Recall@5：前 5 个结果覆盖的唯一相关目标数除以相关目标总数。
- MRR@5：第一个相关结果排名的倒数；前 5 个结果没有相关项时记 0。
- nDCG@5：按排名折损的相关性收益除以理想排序收益；重复命中同一相关目标只计一次。
- Agent 任务成功率：全部隐藏检查通过的任务数除以任务总数，且要求 LLM 执行流程正常完成。
- Memory 写入准确率：写入策略实际动作与预期 SAVE、SKIP、CONFIRM 一致的样本比例。
- Context Compression 保真率：压缩后问答仍包含预期关键实体的事实数除以事实总数。

## 复现命令

RAG 公共数据集：

```powershell
mvn -q "-Dtest=RagRetrievalBenchmarkIT" -DskipTests=false `
  "-Ddevcli.benchmark.rag=true" `
  "-Ddevcli.benchmark.rag.codesearchnet=true" `
  "-Ddevcli.benchmark.rag.codesearchnet.length=50" test
```

Memory：

```powershell
mvn -q "-Dtest=RealLlmMemoryBenchmarkIT" -DskipTests=false `
  "-Ddevcli.it.memory.provider=anthropic" test
```

Context Compression：

```powershell
mvn -q "-Dtest=RealLlmCompressionRetentionIT" -DskipTests=false `
  "-Ddevcli.it.compression.provider=anthropic" test
```

Agent 五任务：

```powershell
mvn -q "-Dtest=AgentCollaborationBenchmarkIT#compareSingleAgentWithMultiAgentOnOneTask" `
  -DskipTests=false `
  "-Ddevcli.benchmark.agent=true" `
  "-Ddevcli.benchmark.llm.maxAttempts=1" `
  "-Ddevcli.llm.retry.max.attempts=1" `
  "-Ddevcli.llm.call.timeout.seconds=180" `
  "-Ddevcli.llm.read.timeout.seconds=120" `
  "-Ddevcli.llm.max.output.tokens=2048" test
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

使用固定 Dockerfile 构建 Linux 官方 harness 后，可追加 `-Ddevcli.benchmark.swebench.evaluate=true`。Windows Python 缺少 `resource` 模块，不能直接运行官方 harness；必须使用 Linux Docker 或 Linux 主机。

聚合固定报告：

```powershell
mvn -q "-Dtest=BenchmarkReportAggregatorIT" -DskipTests=false `
  "-Ddevcli.benchmark.aggregate=true" `
  "-Ddevcli.benchmark.version=20260713_v1" test
```

若 Java 访问 HuggingFace 需要本地代理，应额外传入标准 JVM `https.proxyHost` 和 `https.proxyPort` 参数。报告默认写入 `target/benchmark-reports/`，聚合后的可提交 JSON、CSV 和数据清单写入 `Data/processed/` 与 `Data/manifest/`。

## 复现边界

- 真实 LLM、Embedding 和 Reranker 会产生费用，并受模型版本、端点负载和随机性影响。
- CodeSearchNet 当前只采样 50 条 Java test split 数据，适合项目阶段对比，不代表完整数据集成绩。
- Agent 当前只有 5 个受控任务，每种模式执行 1 次；结果适合工程验证，不应描述为统计稳定结论。
- LongMemEval 的 normalized answer hit 只是代理指标；只有官方 `evaluate_qa.py` judge 结果才能称为 LongMemEval accuracy。
- LongBench 和 RULER 必须同时写明实际子任务、上下文长度和样本量，不能外推到完整集合。
- SWE-bench 必须使用官方 Docker harness 的 resolved 结果，不能根据补丁非空、编译成功或模型自述判断成功。
- 公开简历表述应同时写明数据集、修订版本、样本量、子任务、模型和评测日期。
