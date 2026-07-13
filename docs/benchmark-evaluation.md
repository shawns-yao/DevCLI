# DevCLI 量化评测

## 评测范围

评测框架覆盖四条核心链路：

| 链路 | 数据来源 | 核心指标 |
| --- | --- | --- |
| RAG | CodeSearchNet Java 公共 test split；项目内合成调用链集合 | Recall@5、MRR@5、nDCG@5 |
| Agent | 5 个带隐藏检查的 Java CLI 编程任务 | 任务成功率、隐藏检查完成率、隐藏失败率、去重缺陷率 |
| Memory | 25 条写入策略样本、12 条语义召回与注入查询 | 写入准确率、低价值拦截率、Recall@5、注入命中率 |
| Context Compression | 230k token 长会话、18 条分层事实、5 次真实压缩 | 事实保真率 |

CodeSearchNet 属于公开数据集。Agent、Memory 和 Context Compression 当前使用可复现的项目内受控任务，不应描述为公开数据集结果。后续接入 SWE-bench Lite、LongMemEval 或 RULER 时，应作为独立数据集版本记录，避免与当前结果混合。

## 2026-07-13 基线结果

运行环境：Java 21、Windows、Anthropic Messages 兼容端点，实际模型 `glm-5.2`；Embedding 使用 `Qwen/Qwen3-Embedding-4B`，Reranker 使用 `Qwen/Qwen3-Reranker-8B`。

### RAG

CodeSearchNet Java 公共 test split 采样 50 条，Top-K 固定为 5：

| 指标 | Semantic baseline | DevCLI 路由后检索 |
| --- | ---: | ---: |
| Recall@5 | 1.0000 | 1.0000 |
| MRR@5 | 0.9700 | 0.9900 |
| nDCG@5 | 0.9779 | 0.9926 |

文档型 definition 查询按查询形态进入 semantic route，避免关键词融合和交叉编码器对长文档描述引入排序噪声；短符号查询仍保留 keyword-first、图扩展和 rerank。

### Memory

| 指标 | 结果 |
| --- | ---: |
| 写入准确率 | 96.0% |
| 低价值信息拦截率 | 100.0% |
| Recall@5 | 91.7% |
| 上下文注入命中率 | 91.7% |

唯一失败样本来自稳定项目事实被写入策略误判为 SKIP，说明写入策略仍可能漏存非用户偏好类稳定事实。

### Context Compression

初始上下文约 236,230 token，在 230k 生产阈值下完成 5 次真实压缩；18 条事实通过压缩后问答验证，17 条保留成功，事实保真率为 94.4%。

| 难度层 | 通过数 |
| --- | ---: |
| EASY | 4/5 |
| MEDIUM | 5/5 |
| HARD_ENTITY | 5/5 |
| HARD_OVERRIDE | 3/3 |

失败样本是早期语言偏好事实。覆盖覆盖关系、实体细节和后写覆盖前写事实均全部保留。

### Agent

每个模式执行同一组 5 个任务，任务成功要求 LLM 执行完成且全部隐藏检查通过：

| 模式 | 任务成功率 | 隐藏检查平均完成率 | 平均隐藏失败率 |
| --- | ---: | ---: | ---: |
| 单 Agent | 20.0%（1/5） | 20.0% | 80.0% |
| Planner/Worker/Reviewer | 0.0%（0/5） | 0.0% | 100.0% |

该结果不能作为 Multi-Agent 优势写入简历。失败证据显示两个系统性问题：Planner 有时违反仅输出 JSON 的协议，导致计划无法解析；部分计划把空工作区检查拆成前置步骤，空结果被判定为步骤失败，后续实现任务全部跳过。单 Agent 仅 `ordermvc` 通过 15/15 隐藏检查，其余任务没有形成可编译交付物。当前数据用于暴露架构缺陷，不用于质量宣传。

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

Agent：

```powershell
mvn -q "-Dtest=AgentCollaborationBenchmarkIT#compareSingleAgentWithMultiAgentOnOneTask" `
  -DskipTests=false `
  "-Ddevcli.benchmark.agent=true" `
  "-Ddevcli.benchmark.llm.maxAttempts=1" test
```

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
- Memory 与 Compression 属于项目内受控评测，不能冒充 LongMemEval、RULER 或 LongBench 官方成绩。
- 公开简历表述应同时写明数据集、样本量、Top-K、模型和评测日期。
