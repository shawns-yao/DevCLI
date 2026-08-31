# 2026-08-30 配对评测记录

本轮排除 RAG 和 plan；每个条件只尝试一次，不挑选最好结果，不为改善分数重跑失败题。模型请求名为第三方网关 `gpt-5.6-luna`，不能据此验证底层官方型号。

## 状态

| 实验 | 已验证的进展 | 尚欠内容 |
| --- | --- | --- |
| 多智能体 | Druid 13704 的 Luna delegate 模式补丁由未修改官方 Docker harness 判为 resolved；F2P 1/1、P2P 2/2。solo 空补丁由官方报告单列 | Java 43题配对未完成；缺实际委派调用证据，不能把配置差异归因于协作 |
| 压缩 | LongBench 已完成163/200个双侧结果并按官方逐任务评分；RULER 已开始并按要求暂停 | LongBench有37题未形成双侧结果，不补跑已中断条件；RULER仅保留已完成条件，未出最终分数 |
| 记忆 | 官方S-cleaned的200题检索、reader和Luna兼容网关judge均已完成 | 190/200有双侧裁决，173对两侧reader均成功；完整双侧诊断显示记忆组91胜/6负/76平，仍不能替代官方GPT-4o榜单 |
| 安全 | AgentDojo官方源码固定并审阅工具接口 | 模拟工具与项目权限管线尚未接通，不产出ASR或零逃逸数字 |
| RULER补充诊断 | 官方 NIAH 两任务、8K/32K 共200题已运行199个双侧配对并按官方 `string_match_all` 计分 | `niah_single_1-32768-0049` 的 compact 被中断且不重试；仅覆盖两类 NIAH，不是完整13任务榜单 |

## 记忆检索结果

LongMemEval-S cleaned 200题按题型及可回答性分层抽样。其中188题有可评分的答案证据；12题是不可回答题，另由reader/judge评测。单位是官方 `answer_session_ids` 标注的会话，不是“5条事实”。

| 同一188题 | 最近5会话 | BM25 | 项目关键词记忆检索 |
| --- | ---: | ---: | ---: |
| Evidence Recall@5 | 13.33% | 85.82% | 81.44% |
| Precision@5 | 5.43% | 31.91% | 29.89% |
| MRR@5 | 0.1066 | 0.8350 | 0.8083 |
| 所需会话全部找齐 | 3.19% | 75.00% | 69.68% |

结论：项目关键词记忆检索明显强于近期会话窗口，但 Recall@5 比 BM25 低4.38个百分点，不能宣称优于通用检索方案。没有测向量召回、自动事实抽取或审核晋升，不把本结果泛化成整个记忆系统的能力。

实际注入reader后，项目 Evidence Recall 降到76.76%，相比排序81.44%损失4.67个百分点；16K估算Token预算下，召回不等于注入。纯检索耗时 P50 2.09ms、P95 3.88ms，不含入库、嵌入或模型回答。

reader效果配对使用固定版本官方 `evaluate_qa.py` 的提示词和 yes/no 判定逻辑，但judge改用网关可用的 `gpt-5.6-luna`。200题中190题有两侧可判定标签；其中173题两侧reader均正常完成。173题完整reader配对为记忆组91胜、6负、76平；按题型的胜负差为 knowledge-update `13-2`、multi-session `19-3`、temporal-reasoning `28-0`。这组数字是功能对照诊断，不是官方LongMemEval leaderboard accuracy；网关请求失败、reader失败和judge失败均保留为未知，不当成错误答案。

完整reader配对的输入Token合计：最近会话基线 `1,870,762`，项目记忆 `2,183,479`；加上输出后总Token分别为 `1,880,480` 与 `2,193,193`（仅含两侧均成功的173对，不含检索、judge与失败请求）。因此不能声称记忆降低成本；本轮能支持的结论是回答质量配对差异与证据 Recall/MRR 同时报告。

## RULER 配对结果

本轮使用官方 RULER 生成脚本产生的 NIAH 数据，并复用官方 `postprocess_pred` 与 `string_match_all`；只覆盖 `niah_single_1`、`niah_multikey_2` 两个任务，长度为 8192 和 32768，各50题。共199/200题形成双侧结果，缺失题目不重试。

| 任务/长度 | raw | compact | 质量差值 | 回答输入减少 | 含摘要总Token变化 |
| --- | ---: | ---: | ---: | ---: | ---: |
| single/8192 | 100.0 | 100.0 | 0.0 | 0% | 约0% |
| single/32768 | 100.0 | 100.0 | 0.0 | 94.45% | 增加11.21% |
| multikey/8192 | 98.0* | 100.0* | 0.0（完整49对） | 0% | 约0% |
| multikey/32768 | 100.0 | 6.0 | -94.0 | 88.15% | 增加34.28% |

`*` 含一侧请求错误；完整49对的 raw/compact 均为100.0。结论：当前 Compactor 在单键 32K 上保持质量但增加总 Token；在多键 32K 上虽然大幅缩短回答输入，却丢失大部分关键答案，不能作为简历上的压缩收益。该结果不是完整 RULER leaderboard 成绩，也不是原始 RULER 全13任务覆盖。

数据审计：全部可回答题的gold会话ID均存在于对应候选集合；200题的历史中共有547个会话时间晚于问题时间。该现象来自原始集合，未删题、未更改正文日期。时间相对平移与完整会话导入是适配边界，时效能力仍需单独验证。

## 可复核资产

- LongMemEval-S：revision `98d7416c24c778c2fee6e6f3006e7a073259d48f`；SHA-256 `d6f21ea9d60a0d56f34a05b609c79c88a451d2ae03597821ea3d5a9678c3a442`；与官方LFS元数据一致。
- LongBench：未修改的 `THUDM-LongBench-2e00731/LongBench/eval.py`；每个原始任务文件、输入、gold和源代码指纹记入 `Test/paired-context/luna-v1/manifest.json`。
- 记忆检索逐题记录：`Test/paired-memory/luna-v1/evidence-pairs.json`；BM25逐题对照：`bm25-pairs.json`；汇总分别为 `evidence-summary.json`、`bm25-summary.json`。
- Druid官方报告：`Test/swebench-multilingual-java/gpt-5.6-luna-single-round/official/logs/run_evaluation/luna-delegate-druid13704/devcli-delegate-gpt-5.6-luna/apache__druid-13704/report.json`；复用镜像digest `sha256:3451b80cd2d12999eafe56d69156bb2d982bf86590cb48f89dcbbcdebba6c3bf`，没有本轮自定义Maven挂载补丁。
- AgentDojo：revision `089ed468cf3ed0322acc66b0211f26d9d90dbf60`，本轮仅接入审查。

## 不能使用的旧数字

根目录三个 `devcli-*-gpt-5.6-luna.*gate*.json` 的JavaParser评分文件误标模型。原始 `solo-run.log`、`delegate-run.log` 实际模型均为 `gpt-5.6-sol`。保留原始报告，不把它们混入本轮Luna结果；旧Maven挂载适配还使用了可写缓存，不能写成只读缓存。

旧Oracle的166/200及旧narrativeqa的33.63均是模型直测，不是本次功能收益。任何单题resolved、空补丁分母、源集合/条件不齐全的结果，都不能变成“项目显著提升”。API失败和中断保留现场，不当成0 Token，也不自动再次付费。

## 官方来源

- [SWE-bench Multilingual](https://huggingface.co/datasets/SWE-bench/SWE-bench_Multilingual)
- [LongBench评分器](https://github.com/THUDM/LongBench/blob/2e00731f8d0bff23dc4325161044d0ed8af94c1e/LongBench/eval.py)
- [LongMemEval官方协议](https://github.com/xiaowu0162/LongMemEval)
- [S-cleaned固定版本](https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/tree/98d7416c24c778c2fee6e6f3006e7a073259d48f)
- [AgentDojo固定版本](https://github.com/ethz-spylab/agentdojo/tree/089ed468cf3ed0322acc66b0211f26d9d90dbf60)
