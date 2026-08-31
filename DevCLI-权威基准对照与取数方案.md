# DevCLI 公开基准对照与取数方案（2026-08-30 复核）

> 本文件是取数计划，不是测试完成报告。指标定义、执行入口与当前限制统一以 [量化评测规范](docs/benchmark-evaluation.md) 为准，操作说明见 [Benchmark Runbook](docs/benchmark-runbook.md)。对外成绩必须有固定数据/评测器版本、SHA-256、样本清单、模型配置与原始报告；自建用例和自定义协议必须单独标注。

---

## 一、总览：四个简历重点 ↔ 权威基准

| 简历条目 | 权威基准（主） | 辅助/诊断 | 权威主指标 | 数据语言 |
| --- | --- | --- | --- | --- |
| ① 多智能体/执行内核 | **SWE-bench Multilingual**（Java 子集 43 个） | Multi-SWE-bench / SWE-bench-java-verified | **resolved %**、FAIL_TO_PASS / PASS_TO_PASS | Java |
| ② 上下文压缩 | **LongBench v1** | **RULER v1**（诊断有效上下文长度） | 官方逐任务分数；压缩开/关配对差值 | 英/中/代码 |
| ③ 分层记忆 | **LongMemEval-S cleaned (V1)** | Oracle 仅作 reader 上限 | 官方 LLM-judge **accuracy**及能力分项 | 英文会话 |
| ④ RAG 检索 | **CodeSearchNet Challenge 的 Java 人工相关性标注子集** | 项目自定义 Recall@5 / MRR@5 | 官方 **nDCG**，不得与 CodeXGLUE MRR 混用 | Java |
| ⑤ 工具治理/安全 | **自建对抗用例集**（必须标注自建） | 当前单元测试 | 拦截率、误拒率、逃逸成功数/尝试数 | 按项目用例 |

上述公开基准均不能仅凭文件存在就标记为“已接入官方评测”。当前能力接入差距见第四节。

---

## 二、逐个权威说明

### ① Agent 编码能力 —— SWE-bench 家族（务必分清，项目快照这里有错）

**关键纠正：SWE-bench Lite 的 300 个任务来自 Python 项目，不是 Java 子集。** 本地 `Config/public-benchmarks.json` 只登记了 `swebench-lite`，不能把它的结果写成 Java issue 修复成绩。本轮 Java 评测目标是 SWE-bench Multilingual；其他集合不混入同一个分母。

| 基准 | 出品方 | 规模 / Java 数量 | 语言 | 评测 | 备注 |
| --- | --- | --- | --- | --- | --- |
| **SWE-bench Multilingual**（推荐，项目 AGENTS.md 原定目标） | 官方 SWE-bench 团队，2025-05 发布 | 300 任务 / 42 仓库，**Java 43** | 9 语言（C/C++/Go/Java/JS/TS/PHP/Ruby/Rust） | 与 SWE-bench 同协议，官方 Docker harness 跑 F2P/P2P | HF：`SWE-bench/SWE-bench_Multilingual`；官网 swebench.com/multilingual.html |
| Multi-SWE-bench | bytedance-research | 独立数据集；如采用需重新固定版本与筛选清单 | 多语言 | 采用该项目官方协议 | 备选，当前未接入，不引用动态榜单数字 |
| SWE-bench-java-verified | Java 专用集合 | 如采用需另行核验固定版本 | Java | 采用该项目官方协议 | 备选，当前未接入 |

- **权威指标**：`% Resolved = resolved 实例数 / 总实例数`；每个实例必须 FAIL_TO_PASS（原失败测试补丁后通过）且 PASS_TO_PASS（原通过测试不回归），**只能由官方 Docker harness 判定**，不能凭"补丁非空/编译通过/模型自述"判成功。
- **对照方法**：同一批 Java issue、同一 base、冻结各角色模型和预算，配对运行 solo / delegate / plan；报告每模式 resolved 实例数/N，保存 F2P/P2P 逐测试结果，不把测试数当 issue 数。
- **当前限制**：`swe-bench-run.ps1` 直接执行 `eval.sh` 后自行解析日志；即使镜像来自官方，其 `resolved` 字段仍是自定义判定。`SweBenchOfficialHarness` 目前硬编码 Lite。三模式正式结果需要接入固定版本的 Multilingual 官方 evaluator 并读取 report。
- **取数顺序**：先单题单模式核验 predictions → 官方 report，再做单题三模式，之后扩大到预先选定的 10–20 题或全部 43 题。重复次数和费用上限在运行前确定。
- 来源：[SWE-bench Multilingual 官方说明](https://www.swebench.com/multilingual.html)、[SWE-bench 官方数据集](https://www.swebench.com/SWE-bench/guides/datasets/)、[官方 CLI](https://www.swebench.com/SWE-bench/reference/cli/)。

### ② 上下文压缩 —— LongBench v1（主）+ RULER（诊断）

**LongBench v1**（THUDM 官方，本地登记的就是 v1，revision 2e00731）
- 21 个任务、6 大类，共 4,750 条。多数任务为 200 条，但 `MultiFieldQA-en` 为 150 条，`LCC` 和 `RepoBench-P` 各 500 条。具体指标按官方逐任务映射，包含 F1、ROUGE-L、Accuracy 和代码 Edit Similarity，不能统一成 Accuracy。
- **压缩评测推荐选这几个子任务**（最能暴露"压缩后是否丢约束"）：
  - `PassageRetrieval-en/zh`（合成，Accuracy）：长文中定位目标段落——对应"工具结果/证据被压掉后还找不找得到"；
  - `PassageCount`（合成，Accuracy）：长文段落计数——对应计数类约束保留；
  - 再加 1 个 QA（如 HotpotQA，F1）测语义保真。
- 另有 LongBench-E（按 0–4k/4–8k/8k+ 均匀采样），可用来画"不同输入长度下压缩前后得分曲线"。
- **别和 LongBench v2 混**：当前本地适配为 v1；v2 是另一套任务与协议，未接入前不作为当前成绩。
- **当前限制**：`PublicLongContextBenchmarkIT` 直接调用模型，不经过 DevCLI 压缩器；当前仅有两个英文合成子任务的专用评分实现，其他子任务回退为包含命中，不能作为官方 QA/F1/ROUGE-L。正式实验需实际调用压缩器并用固定版本官方 evaluator 评分。
- 来源：[LongBench 官方任务统计](https://github.com/THUDM/LongBench/blob/main/LongBench/task.md)、[官方评测入口](https://github.com/THUDM/LongBench/blob/main/LongBench/eval.py)。

**RULER v1**（NVIDIA，arXiv 2404.06654，COLM 2024）——定位是**诊断**不是主成绩
- **4 大类 13 任务**：Retrieval（NIAH 单针/多针/多值）、Multi-hop Tracing（变量追踪）、Aggregation（常见/高频词提取）、QA；**合成数据、长度可配（4K/8K/16K/32K/64K/128K）**，指标 **string-match accuracy**。
- 用法：固定任务，对比"不压缩 vs 每压缩 N 轮"在 4k→32k 的 string-match 曲线，找到压缩导致的有效上下文长度衰减点——这比单一分数更能讲清你压缩机制的价值。
- 当前适配只读取生成的 `niah_single_1` 样本，不代表已支持全部 13 个任务。长度必须同时记录 tokenizer、随机种子、任务配置与实际 token 数。
- 来源：[RULER 官方仓库](https://github.com/NVIDIA/RULER)、[RULER 论文](https://arxiv.org/abs/2404.06654)。

**压缩工程指标（待埋点）**：压缩倍数 = 压缩前 token / 压缩后 token，单位为倍；Token 节省率 = 1 - 压缩后/压缩前，单位为百分比。二者不得混写。另记录官方得分差、摘要调用 Token/成本、触发阈值、压缩次数和耗时；前后使用相同 tokenizer，基线为零时不计算比值。

### ③ 分层记忆 —— LongMemEval（V1）

- 官方提供 **500 道问题、5 项核心能力**：信息抽取、多会话推理、知识更新、时序推理、弃权。不同版本历史长度不同，不能把 S/M 的长度套在 oracle 上。
- **权威指标**：使用固定版本 `src/evaluation/evaluate_qa.py` 的 LLM-judge accuracy，记录 judge 模型与提示词版本。`normalizedAnswerHit` 只是项目代理指标，不是官方 accuracy，也不能将空答案包含匹配作为正确答案。
- 本地登记的是 `longmemeval_oracle.json`。它只适合作为提供相关历史的 reader 上限，不能代替 `longmemeval_s_cleaned.json` 的端到端记忆评测；简历必须写清 variant，不能仅写“LongMemEval”。
- **待接入对照**：每题使用独立记忆空间，按时间顺序导入历史，再回答问题；写入阶段禁止提供测试问题、gold answer 或 answer session ID。比较真实长期记忆开/关，必要时增加全文直给和 Oracle 基线；最后使用相同官方 judge，按题目 ID 配对比较。
- LongMemEval-V2 是另一套 agentic-memory 协议，本地未接入，不引用其规模或成绩。
- 来源：[LongMemEval 官方仓库和评测步骤](https://github.com/xiaowu0162/LongMemEval)、[官方数据](https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned)、[论文](https://arxiv.org/abs/2410.10813)。

### ④ RAG 检索：区分 CodeSearchNet Challenge 与 CodeXGLUE

- **CodeSearchNet Challenge 官方主指标是 nDCG**，使用人工 0–3 级相关性标注；官方说明包含 99 个通用查询，实际 Java 查询数量应从固定版本标注清单统计。不要把另一种过滤/采样协议的 test/corpus 数量直接当作 Challenge 规模。
- **CodeXGLUE 的 NL-code-search-Adv 使用 MRR，但属于独立协议**。采用时必须核实语言、候选集、过滤与函数名处理方式，不能把项目 Java 数据结果改名为 CodeXGLUE 成绩。
- 当前 `RagRetrievalBenchmarkIT` 把 docstring/code 配对转换为合成 Java 工程，只有 semantic 与完整检索两路；`TOP_K=5`、二值 gold 和本地 nDCG 不能代替官方人工分级 nDCG。在线 rows 请求也没有固定 revision，正式运行前必须改用固定来源文件并记录 SHA-256。
- **待接入消融**：固定查询、候选集、标注、模型和预算，比较 keyword-only、dense-only、RRF、RRF+graph、RRF+graph+rerank；逐项记录开关及实际降级状态。当前代码没有这套五档独立开关，不能直接用现有命令宣称完成。
- 来源：[CodeSearchNet 官方 Evaluation](https://github.com/github/CodeSearchNet#evaluation)、[CodeXGLUE NL-code-search-Adv](https://github.com/microsoft/CodeXGLUE/tree/main/Text-Code/NL-code-search-Adv)。

### ⑤ 工具治理与安全：项目自建对抗集

本轮采用项目自建对抗集，不声称安全领域不存在公开基准，也不把现有单测包装成公开榜成绩：
- 用例来源（项目里已有对应测试，可直接统计扩写）：`ToolExecutionPipelineTest`、`ToolCapabilityTest`、`CommandGuardTest`、`PathGuardTest`、`ApprovalPolicyTest`、`ToolResultCacheTest`、`ToolInvocationFingerprintTest`、`StaleWriteBarrierIntegrationTest`、`ContextVersionLedgerContractTest`。
- 覆盖类别：非法/越界工具参数、路径逃逸（`../`、符号链接）、危险命令注入、MCP destructive/openWorld 工具、并发写同一文件、只读工具副作用、缓存投毒。
- 自报指标（标注“自建 N 条用例”）：正确拦截数/应拦截数、合法请求误拒数/合法请求数、逃逸成功数/明确的逃逸尝试数、错误合并数/冲突尝试数。模型一次自纠率需要真实模型或明确标注的录制回放，不能由参数校验单测替代。目标为零不等于已验证为零。

---

## 三、必须统一的口径

1. **swebench-lite 是 Python（300），不是 Java**：测 Java 编码 Agent 要换/补 SWE-bench Multilingual（Java 43）等，简历不能出现"SWE-bench Lite Java"。
2. **指标随协议确定**：CodeSearchNet Challenge 用官方 nDCG；CodeXGLUE 检索用其 MRR 协议；本地 MRR@5 不等于未截断 MRR，二值 nDCG@5 也不等于 Challenge 的分级 nDCG。
3. **模型分数不等于项目功能收益**：记忆与压缩必须实际经过对应模块，并和同题同模型基线配对；官方 judge/evaluator 与本地代理指标分列。
4. **现有脚本结果不是官方 report**：直接运行官方镜像与 `eval.sh` 仍不足以把自定义日志判定称为官方 resolved。

---

## 四、接入状态与取数顺序

| 链路 | 当前能运行什么 | 正式取数前缺什么 |
| --- | --- | --- |
| Agent | 三模式 Driver 与 Docker 日志冒烟 | Multilingual 固定清单、官方 predictions/report、完整运行元数据 |
| RAG | CodeSearchNet 派生语料的两路检索诊断 | 官方标注与候选集、分级指标、五档消融、固定文件哈希 |
| Memory | Oracle 历史直给模型、hypotheses 导出 | S-cleaned、真实记忆写入/检索、开关对照、官方 judge |
| Compression | 两个 LongBench 子任务与单个 RULER 任务的模型直测 | DevCLI 压缩器调用、前后 token/成本、官方评分 |
| Safety | 现有确定性单元测试 | 固定对抗清单、合法请求对照、攻击尝试分母与分类汇总 |

1. 先按 [量化评测规范](docs/benchmark-evaluation.md) 完成定向、Quick、协议、终端和干净全量回归；这些结果证明工程回归，不是公开能力成绩。
2. 每条公开链路先做 1 个样本的输入到报告校验，确认实际调用了被测功能，再扩大样本量。失败样本保留，不以更换题目掩盖失败。
3. 接入完成后优先评测 RAG，再做 Memory 与 Compression；真实 Embedding、rerank、回答模型、摘要模型和 judge 都可能产生费用，先确认预算。
4. SWE-bench 先单题单模式、再单题三模式，最后执行预先冻结的 10–20 题或全部 Java 43 题配对；不直接把 10–20 题当作首次链路冒烟。
5. 同一任务不同模式共享抽样清单与模型配置，报告失败分类、全部样本、耗时和 Token；统计方法及回填字段见规范。以上待接入项不是本次文档修改已经完成的功能。

> 每条结果落表：数据集与 variant | 数据/评测器 revision 与 SHA-256 | 样本 ID 清单 | 模型及角色配置 | 被测功能开关 | 指标分子/分母 | 对照基线 | 运行日期 | 失败分类 | 原始报告路径。报告齐全后才回填 [简历草稿](DevCLI-项目经历-简历定稿.md) 的【】占位。
