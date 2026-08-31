# DevCLI 量化评测

> **测试资产状态（2026-08-30）**：本文件统一定义工程回归、诊断评测与正式公开成绩的边界。历史自建数字不得作为当前成绩；当前公开集合适配与日志诊断不等于已经接通官方评分。任何待接入项都不能通过修改文档变成“已完成”。操作细节见 [Runbook](benchmark-runbook.md)，简历取数顺序见 [公开基准对照与取数方案](../DevCLI-权威基准对照与取数方案.md)。

## 评测范围

本轮只评测四块：多智能体、上下文压缩、记忆、工具安全。RAG 暂不纳入本轮，避免把尚未接入官方 qrels 的内部检索结果写成公开成绩。每个条件只运行一轮；同一题目、同一模型、同一预算、同一数据版本做配对比较。现有入口的完成状态另见“公开集合接入状态”。

| 链路 | 数据来源 | 核心指标 |
| --- | --- | --- |
| RAG | 本轮不测试 | 不产出结果，不写入简历 |
| Agent | SWE-bench Multilingual Java；SWE-bench Lite 仅保留兼容适配 | 官方 resolved rate、FAIL_TO_PASS、PASS_TO_PASS |
| Memory | LongMemEval-S cleaned | 官方 LLM-judge accuracy；Oracle 仅为 reader 上限 |
| Context Compression / Long Context | LongBench v1；RULER v1 作为诊断 | 压缩开/关的官方逐任务分数与工程开销 |
| 工具治理 | AgentDojo 或 ToolSandbox 官方任务；无法协议映射时才使用明确标注的自建对抗集 | 攻击成功数/尝试数、越权副作用数、合法任务退化、误拒数 |

现有 [公开数据目录](../Config/public-benchmarks.json) 的历史登记不代表本轮执行状态。Multilingual Java 原始 parquet 和 LongMemEval-S cleaned 已落盘并校验；本轮输入、抽样与 SHA-256 另见 `Test/paired-context/luna-v1/manifest.json`、`Test/paired-memory/luna-v1/manifest.json`。实际结果与边界见 [本轮记录](benchmark-paired-run-20260830.md)。旧项目内能力评测只保留历史归档。

2026-08-30 的本地就绪检查验证了上述 4 个已登记资产的 SHA-256 与 harness 路径，并成功解析 Lite 5 条、LongMemEval Oracle 5 条和 LongBench 4 个任务共 20 条样本；报告位于 `target/benchmark-reports/public/public-dataset-readiness.json`。该检查没有调用模型或官方 evaluator，也没有验证未登记的正式目标集合。

## 工程测试顺序

在项目根目录使用 Java 17+ 与 Maven。Maven 默认跳过测试，`package` 成功不等于测试通过。下列命令不启用真实 benchmark 开关；单测可能启动本地模拟服务或子进程，不等于启动交互产品。

| 时机 | 命令 | 证明范围 |
| --- | --- | --- |
| 修改期间 | `mvn -q -DskipTests=false "-Dtest=XxxTest" test` | 仅对应类或方法；协议问题优先固定输入 |
| 常规回归 | `mvn -q -Pquick test` | 排除部分外部进程、网络/命令超时测试和 benchmark 包 |
| 执行/存储协议改动 | `mvn -q -Pprotocol-regression test` | Profile 内显式列出的契约测试 |
| 终端/HITL/渲染改动 | `mvn -q -Pphase16-smoke test` | 模拟终端兼容测试，不代替真实终端操作 |
| 修改全部完成后 | `mvn -q clean -DskipTests=false test` | 干净全量工程回归；`*IT` 真实评测需显式选择 |

本次指标与脚手架的定向入口：

```powershell
mvn -q -DskipTests=false "-Dtest=RetrievalMetricsTest,PublicBenchmarkDatasetTest,CodeSearchNetJavaDatasetAdapterTest,SweBenchDriverIsolationTest,PreReviewVerifierTest" test
```

通过标准是 Maven 退出码 0、Surefire failure/error 均为 0，跳过数量和原因单列。报告来自 `target/surefire-reports/`；定向测试会覆盖部分旧 XML，不能将混合目录总数冒充一次新全量。工程测试全部通过仍不能证明模型效果、安全零逃逸或官方 benchmark 成绩。

## 本轮四项实验设计（单轮配对）

### 共同固定条件

- 模型固定为 `gpt-5.6-luna`；角色模型、温度、最大输出、工具白名单和超时全部冻结。
- 每个条件使用同一批题目和同一顺序，只运行一轮；不把不同模型、不同题目或历史运行拼成对照。
- 记录 DevCLI 版本、数据集与 evaluator revision、题目 ID、输入/输出/缓存 Token、墙钟时间、错误分类和原始报告路径。
- “成功率”只作为官方 evaluator 的完整性字段，不作为简历主指标；对外只使用配对差值、质量保持、成本和副作用指标。

### 1. 多智能体：SWE-bench Multilingual Java 43 题

**条件**：同一 43 题分别运行 `solo` 与 `delegate`；不测试固定 `plan` 流水线。每题从同一 base commit 开始，使用官方 patch/eval 流程。

**主输出**：

- **净修复测试数**：新增通过的 `FAIL_TO_PASS` 数减去退化的 `PASS_TO_PASS` 数；
- **非回归保持率**：保持通过的 `PASS_TO_PASS` 数/原 `PASS_TO_PASS` 数；
- **修复效率**：净修复测试数/十万 Token，并记录每个净修复测试的耗时；
- **委派有效率**：产生可归并证据或补丁的委派次数/全部委派次数；
- **返工与冲突**：重复修复次数、PatchSet 拒收次数、文件冲突次数；
- `delegate - solo` 的逐题净质量、Token、耗时和成本差值。

F2P/P2P 以官方测试 ID 为单位，可能是方法，也可能是类，不能把日志里的方法数量替换成官方分母。跨题优先报告逐题覆盖率差的宏平均；“净修复测试数/十万 Token”是补充工程指标，不是 SWE-bench 官方指标。须记录实际 `delegate_task` 调用次数；仅启用工具但没有调用，不能解释为子 Agent 协作收益。网关没有可核实单价时只报 Token，不把内置估价当账单。

**简历准入**：只有官方 report、逐题配对结果和成本数据同时存在，才允许写“委派后每十万 Token 的净修复测试数提高 X、非回归保持率为 Y、返工下降 Z”；不能只写自定义 `resolved`、任务成功率或单模式耗时。

### 2. 上下文压缩：LongBench v1 + RULER v1

**条件**：同一任务、同一长度桶分别运行“原始上下文”和“启用 DevCLI Compactor”；压缩器必须真正接入被测请求链路，不能把上下文直接交给模型。

本轮 LongBench 固定 200 题：`qasper` 34、`hotpotqa` 34、`qmsum` 33、`trec` 33、`passage_retrieval_en` 33、`lcc` 33，覆盖六类任务。按固定种子与题目索引 SHA-256 排序选取，禁止按分数换题。两组将同一原文按 8000 字符分成相同消息；压缩组调用生产 `ConversationHistoryCompactor`，阈值 8192、尾部预算 2048 个项目估算 Token，回答端记录 API 实际 Token。条件顺序交替。短样本不触发压缩也保留。

这是“单次摘要后的 reader 对照”，不测工具回读恢复、增量多轮压缩、整个 Agent 的任务终态。它采用官方数据和未修改 `eval.py`，但消息格式改为相同分块，因此不是 LongBench 官方原始单提示词排行榜提交。RULER 属于待执行的独立诊断，不得把 LongBench 200 题写成 RULER 已完成。

**主输出**：

- LongBench 官方逐任务 `F1`、`ROUGE-L` 或 `Accuracy` 的差值；RULER 官方 `string-match` 差值；
- `Token Reduction = 1 - compressed_input_tokens / raw_input_tokens`；
- `Quality Retention = compressed_score / raw_score`，并同时报告绝对分数差，避免只报节省率；
- **有效压缩效率**：每减少一万输入 Token 对应的官方质量损失；
- **关键信息保留**：RULER 检索命中和 LongBench QA/摘要分数按长度桶的保持率；
- 摘要调用额外 Token、上下文超限次数、端到端延迟差。

总成本必须另算 `1 - (压缩摘要输入+摘要输出+回答输入+回答输出)/(原始回答输入+回答输出)`。回答输入下降不代表单次总 Token 下降。不同任务分数分开报告，不把 F1、ROUGE-L、分类准确率和代码相似度混成一个质量百分比。API 错误按完整分母保留；双侧调用正常的配对另作诊断并列出排除 ID，不替代全体结果。

**简历准入**：只接受“Token 减少 + 官方质量保持”的成对结果；单独的压缩比例或模型直测分数不能写成项目收益。

### 3. 记忆：LongMemEval V1（固定 200 题配对）

**条件**：LongMemEval-S cleaned 固定版本 `98d7416c24c778c2fee6e6f3006e7a073259d48f`，按题型和是否可回答分层选 200 题。基线读取最近 5 个会话，实验组使用生产 `MemoryManager.retrieveRelevant/buildContextForQuery` 与隔离 `LongTermMemory`。两组限制 5 个会话和 16384 个估算 Token。另加 BM25 作为更强的检索基线，避免仅证明“能搜索比只看最近内容好”。

本轮测试的是完整会话导入后的关键词检索和回答效果；未测向量通道、自动记忆抽取/审核晋升、完整 `SessionMemory` 流程，不能泛称完整记忆系统消融。输入只含角色、正文、日期、会话 ID，答案与 `answer_session_ids` 只交给评分器。使用独立测试目录中的 SQLite，不读取用户长期记忆。原始日期保留在正文，入库时间整体平移以保留相对年龄；官方源中存在晚于问题时间的会话，不自行删去，见批次审计。

**主输出**：

- 官方 LLM-judge Accuracy 的配对差值；
- **Evidence Recall@5 / Precision@5 / MRR@5**：使用官方所需会话 ID；Recall 分母为所需会话数，Precision 分母固定为 5，MRR 按首个相关会话位置；
- 检索排序与实际注入分别评分；Recall 排序到注入的下降反映预算截断损失；全证据覆盖率检验多会话问题是否找全；
- 200 题中的 12 道不可回答题不进入证据 Recall 分母，进入回答/拒答评分；
- “命中但回答错误”可由检索和 judge 交叉计算；不能仅由命中推断模型实际使用了证据；
- 官方会话标注不是逐条事实的时效标签，因此本轮不报未经标注的“错误/过期记忆注入率”；非 gold 会话也不必然完全无用；
- **记忆收益效率**：每增加一万输入 Token 带来的官方 judge 分数变化；
- 每题记忆检索 Token、检索延迟和额外模型调用次数；
- 按 `temporal reasoning`、`multi-session`、`knowledge update` 分项只作诊断，不替代总 Accuracy。

**简历准入**：必须同时有关闭/开启两组的官方 judge 结果和记忆副作用数据；`166/200` 这类单独模型分数不算项目效果，也不能外推到完整 LongMemEval。

当前网关 judge 使用 `gpt-5.6-luna`，只复用固定版本 `evaluate_qa.py` 的提示词、参数及 yes/no 判定，不能声称使用了官方 `gpt-4o-2024-08-06` judge，也不能和其排行榜直接比较。空响应、网络错误、非法裁决单列，不改成“回答错误”。

### 4. 工具安全：AgentDojo 官方任务（接入未完成）

**条件**：同一任务集分别运行“在同一隔离沙箱内旁路 DevCLI 治理”和“完整权限、审批、审计、沙箱、隔离工作区链路”；baseline 不得接触主工作区。若官方任务无法映射到 DevCLI 工具协议，停止对外宣称公开 benchmark，改用带标签的自建对抗集。

官方源码已固定为 `089ed468cf3ed0322acc66b0211f26d9d90dbf60`。尚需把官方 `FunctionsRuntime` 工具执行完整接到 `ToolExecutionPipeline`，并继续使用官方环境状态的攻击/效用判定。禁止把“所有写工具一律拒绝”的模拟结果当作安全效果；必须同时测合法任务损失。AgentDojo 的模拟外部服务不覆盖项目文件回滚，不能用其 ASR 证明文件系统零污染。接入完成前不调用安全评测模型，不产出安全数字。

**主输出**：

- 攻击成功数/攻击尝试数（ASR）及启用治理后的下降百分点；
- 越权写入、未授权命令、主项目污染、取消后残留副作用的实际计数；
- **安全效用保持率**：启用治理后的合法任务官方效用/旁路治理时的合法任务官方效用；
- 合法任务误拒数/合法请求数，以及回滚成功数/应回滚数；
- **安全开销**：每个合法任务增加的 Token、延迟和人工审批次数；
- 每类攻击的证据链：策略拒绝、审批拒绝、沙箱拒绝或 PatchSet 拒收。

**简历准入**：安全结果必须写攻击分母、合法请求分母和环境；“测试中 0 次逃逸”不能单独表述为普遍安全保证。

### 单轮结果表

每个实验只生成一张配对汇总表，至少包含：`condition`、`dataset_revision`、`sample_ids`、`official_metric`、`baseline`、`treatment`、`delta_pp`、`input_tokens`、`output_tokens`、`wall_ms`、`副作用/失败计数`、`raw_report`。单轮结果只能描述本批题目上的观测差异，不做显著性结论。

### 简历数字筛选

允许写：官方 evaluator 产出的配对质量差值、质量保持率、Token Reduction、返工/冲突/副作用下降，以及对应的样本量和数据集版本。

不允许写：只跑一次的单模式 Accuracy、模型直测分数、`200 题完成`、自定义 `resolved`、未接入项目功能的 Oracle/LongBench 分数，或没有 baseline 的“提升”。

### 执行前硬门

本轮实验只有在以下条件满足后才允许烧真实模型额度：

- **Agent**：`solo` 与 `delegate` 都导出同一题目的 predictions，并由固定版本官方 SWE-bench evaluator 生成 report；脚本日志判定不算正式结果，固定 `plan` 不参与本轮。
- **Memory**：关闭组和开启组都经过同一入口，开启组确实调用 `SessionMemory + LongTermMemory`；直接把 Oracle 历史交给模型只能算模型基线。
- **Compression**：关闭组和开启组都经过同一请求构造链路，开启组确实调用 `ConversationHistoryCompactor`；当前 LongBench/RULER 直测结果不能算压缩收益。
- **Safety**：先完成 AgentDojo/ToolSandbox 工具协议映射并保留官方任务 ID；映射失败则只报告明确标注的自建对抗实验，不写公开 benchmark 名称。

若任一硬门不满足，本轮只做离线接入检查，不调用真实模型、不生成简历数字。

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

| 链路 | 实际行为 | 正式评测缺口 |
| --- | --- | --- |
| RAG | `RagRetrievalBenchmarkIT` 用 docstring/code 配对构建合成 Java 工程，比较 semantic 与完整检索 | 固定资产与人工分级标注、官方 nDCG、独立消融开关；现有 MRR@5 不能改名为完整 MRR |
| Agent | `SweBenchDriver` 跑 solo/delegate/plan；PowerShell 脚本用日志与 `meta.expect` 自行判定；另有 Lite 官方 harness 封装 | Multilingual 官方 predictions/report 接入、数据 revision、完整运行元数据 |
| Memory | `PublicLongContextBenchmarkIT` 把 Oracle 历史直接交给 `LlmClient.chat()`，导出 hypotheses | S-cleaned、真实 MemoryManager 写入/检索、开/关对照及官方 judge |
| Compression | 同一 IT 直接调用模型，默认只测 LongBench `passage_count`/`passage_retrieval_en` 和 RULER `niah_single_1` | 实际经过 DevCLI 压缩器、未压缩基线、token/成本埋点及官方评分 |
| Safety | 多个现有单元测试覆盖参数、权限、冲突等情况 | 带期望标签的对抗清单、合法请求对照、攻击尝试分母与分类报告 |

`PublicLongContextBenchmarkIT` 的 `longbench_official_metric_average`、`ruler_official_string_match_average` 是现有字段名，不是已经运行官方 evaluator 的证明。LongBench 非上述两类任务的评分回退为包含命中，不能当作官方 F1/ROUGE-L。每条正式链路都应先验证一个样本的完整输入与官方输出，再扩大规模。

## 指标定义

- CodeSearchNet Challenge：按固定官方 evaluator 与 0–3 人工相关性标签计算 nDCG；Java 子集规模从实际清单统计。CodeXGLUE MRR 是另一套检索协议，不混用名称、候选集或分母。
- 内部 Recall@5：前 5 个结果覆盖的唯一相关目标数/相关目标总数；MRR@5 是逐查询首个相关项排名倒数的宏平均，前 5 名没有相关项记 0。二值 nDCG@5 只用于当前内部协议，不能替代官方分级 nDCG。
- Agent resolved rate：官方 report 中 resolved 的 issue 数/事先冻结的 issue 总数；F2P/P2P 保存逐测试 ID 与结果，不用 Maven 类级测试总数替代。运行环境故障单列，但不事后删除失败样本抬高成功率。
- LongMemEval accuracy：只采用官方 judge 标签，以有效问题清单为分母；记录缺失预测与失败。`normalizedAnswerHit` 是本地代理，不称为官方准确率。Oracle、S-cleaned 和 M-cleaned 必须分列。
- LongBench / RULER：按固定官方 evaluator 的任务映射评分，逐任务、长度分桶报告，不把不同任务的本地包含命中当官方分数。LongBench `length` 的词数/字符数不等于 tokenizer token 数。
- 功能收益：同一问题、模型和预算比较开/关功能后的分数差。分数先统一为 0–100 后，差值单位为百分点；得分保持率为 after/before，基线为 0 时不定义。
- 压缩：Token 节省率 = 1 - after/before；压缩倍数 = before/after，单位为倍。记录相同 tokenizer 下前后消息 token，以及额外摘要调用的 Token、成本、次数和耗时，不把节省率写成压缩倍数。
- 工程成本：分别记录输入、输出、缓存输入 Token，缓存量可能是输入量的子集，禁止盲目相加。估算费用不是账单费用；别名未知或用量缺失应标记 unknown，不能当真实 0 成本。现有脚本缺失 usage 时写 0，汇总前必须核对日志。
- 时间：现有 `wall_ms` 是 Driver 内部运行时间，不包含 DevCLI 编译和评分容器；脚本 startedAt/finishedAt 包含 Java 启停。正式报告应另记 Agent、harness 和全流程耗时，失败运行也保留。
- 安全：正确拦截数/应拦截数，误拒数/合法请求数，逃逸成功数/攻击尝试数；写明覆盖范围和环境。“测试中 0/N 次逃逸”不等于普遍不可逃逸。

所有配对实验固定任务 ID、数据版本、功能开关、每角色模型、预算和重复次数。报告逐样本结果和聚合值；二值 resolved 可用配对检验，分数/成本差可用按任务聚类的配对 bootstrap，不能将同题重复试验当独立题目。单题或少量冒烟不支持“显著优于”。当前尚无统一统计聚合器。

### 正式报告必填字段

数据集/variant、数据与评测器 revision/SHA-256、样本 ID 清单、语言/子任务、模型及角色映射、预算/开关、DevCLI commit 与 dirty diff 哈希、镜像 digest、prompt/patch 哈希、开始结束时间、逐题官方 verdict、F2P/P2P、Token 来源、估算费用及价格来源、失败分类、原始报告路径。记录脱敏配置，不保存密钥或本机仓库私有路径。

当前三模式 `results.jsonl` 只有 commit/base、模式、部分开关、Driver 耗时、用量、类级测试摘要和自定义判定。主模型及部分环境仅见 `*-run.log`，各角色配置、官方报告与 dirty diff 等尚未完整记录，因此不能宣称上述字段已经落盘。

## 复现命令

RAG 派生语料诊断（会调用 Embedding，可能调用 rerank；不是 Challenge 官方成绩）：

```powershell
mvn -q "-Dtest=RagRetrievalBenchmarkIT" -DskipTests=false `
  "-Ddevcli.benchmark.rag=true" `
  "-Ddevcli.benchmark.rag.codesearchnet.corpus=1000" `
  "-Ddevcli.benchmark.rag.codesearchnet.queries=200" `
  "-Ddevcli.benchmark.rag.codesearchnet.seed=20260809" test
```

上面的在线 rows 来源未固定 revision，仅供诊断。可用 `-Ddevcli.benchmark.rag.codesearchnet.file=<rows.json>` 指定本地固定文件，但还需要另外记录来源、SHA-256；固定文件本身不会将自定义协议变成官方协议。旧 `codesearchnet.length` 参数没有被当前实现读取。

现有目录中的公开数据完整性与适配检查（不包含尚未登记的 Multilingual/S-cleaned/Challenge）：

```powershell
mvn -q "-Dtest=PublicBenchmarkReadinessIT" -DskipTests=false `
  "-Ddevcli.benchmark.public.datasets=true" `
  "-Ddevcli.benchmark.public.sample.limit=5" test
```

LongMemEval Oracle、LongBench 与 RULER 模型直测（收费诊断，不经过项目记忆或压缩模块）：

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

`SweBenchDriver` 的 `solo` / `delegate` / `plan` 用于比较编排方式，但运行前仍需冻结各角色模型与预算。三种模式统一使用
`read_file`、`write_file`、`edit_file`、`list_dir`、`grep_code`、`execute_command` 和
`read_tool_result` 白名单，并关闭长期记忆注入；`delegate` 仅额外开放 `delegate_task`。
驱动不开放 `search_code`，避免本机残留索引成为未记录变量。新增工具不会自动进入评测面。驱动沿用生产 `DOCKER`，显式配置 `HOST_WARN` 时才使用主机白名单。主模型、Java、沙箱、HTTP 协议与 Maven 仓库配置类型写入 Driver 日志，而非完整写入 JSONL；正式对照仍需显式冻结各角色模型与预算，不能只假定都与主模型相同。

三模式脚本诊断入口（需要运行权限与模型预算，当前不是官方 Multilingual report 链路）：

```powershell
pwsh scripts/swe-bench-run.ps1 -Instance Temp/runs/jp -Mode solo -M2 C:\Document\Maven\repository -SkipEval
pwsh scripts/swe-bench-run.ps1 -Instance Temp/runs/jp -Mode solo,delegate,plan -M2 C:\Document\Maven\repository
```

`-SkipEval` 只跳过评分容器，Agent 的命令沙箱仍默认依赖 Docker。输出在 `<Instance>/runs-<时间戳>/`；`meta.expect` 与 Maven 日志判定只用于快速诊断。正式 Multilingual 取数须先增加 predictions 导出与固定版本官方 evaluator/report 接入，不能只换 image 或把 Lite 命令的名字改成 Java。

使用固定 Dockerfile 构建 Linux 官方 harness 后，可追加 `-Ddevcli.benchmark.swebench.evaluate=true`。Windows Python 缺少 `resource` 模块，不能直接运行官方 harness；必须使用 Linux Docker 或 Linux 主机。

若 Java 访问 HuggingFace 需要本地代理，应额外传入标准 JVM `https.proxyHost` 和 `https.proxyPort` 参数。Java IT 报告默认写入 `target/benchmark-reports/`，三模式脚本报告写入 Instance 下的 runs 目录；旧的跨自建任务聚合器已删除，正式结果按各自官方格式单独汇总。

## 复现边界

- 真实 LLM、Embedding 和 Reranker 会产生费用，并受模型版本、端点负载和随机性影响。
- CodeSearchNet 接入仍需用官方相关性标注和固定样本重新验证，旧 50 条适配性数字已撤销。
- Agent 比较使用同一清单上的 solo / delegate / plan 配对；不混用旧版本或人工取出的 Worker 补丁替代失败流水线的实际交付。
- LongMemEval 的 normalized answer hit 只是代理指标；只有官方 `evaluate_qa.py` judge 结果才能称为 LongMemEval accuracy。
- LongBench 和 RULER 必须同时写明实际子任务、上下文长度和样本量，不能外推到完整集合。
- SWE-bench 必须使用官方 Docker harness 的 resolved 结果，不能根据补丁非空、编译成功或模型自述判断成功。
- 公开简历表述应同时写明数据集、修订版本、样本量、子任务、模型和评测日期。

## 官方来源

- [SWE-bench Multilingual](https://www.swebench.com/multilingual.html)：300 题、Java 43 题；[官方 CLI](https://www.swebench.com/SWE-bench/reference/cli/) 定义运行和 report，实际运行需固定兼容的评测器版本。
- [CodeSearchNet Evaluation](https://github.com/github/CodeSearchNet#evaluation)：nDCG 与人工分级标注；[CodeXGLUE NL-code-search-Adv](https://github.com/microsoft/CodeXGLUE/tree/main/Text-Code/NL-code-search-Adv) 是独立 MRR 协议。
- [LongMemEval](https://github.com/xiaowu0162/LongMemEval)：Oracle/S/M 数据与官方 judge，不能将 reader 上限等同端到端记忆效果。
- [LongBench 任务表](https://github.com/THUDM/LongBench/blob/main/LongBench/task.md)：21 任务共 4,750 条，MultiFieldQA-en 为 150，LCC/RepoBench-P 各 500，其余多数为 200；[官方 evaluator](https://github.com/THUDM/LongBench/blob/main/LongBench/eval.py) 定义逐任务指标。
- [RULER](https://github.com/NVIDIA/RULER)：任务、长度与官方评测配置；当前本地仅接入一个生成任务的诊断读取。
