# 公开评测数据来源清单

- 批次：`public_benchmark_sources_20260716_v1`
- 下载日期：2026-07-16
- 原始文件目录：`Data/raw/public-benchmarks/`
- 原始文件不纳入 Git；仓库只提交本清单、固定版本配置、适配器和聚合结果
- 固定配置：`Config/public-benchmarks.json`

## SWE-bench Lite

- 官方数据：`SWE-bench/SWE-bench_Lite`
- 数据修订：`69611d31007e1c6731db8bd5b5c3f2d33f5bab6e`
- 测试集：300 条
- 本地文件大小：1,111,559 字节
- SHA-256：`f46f2e3f003f2552932393da4b223e1e0456a2c71eba8b73ae58f29646c1278b`
- 官方 harness：`SWE-bench/SWE-bench`
- harness 修订：`f7bbbb2ccdf479001d6467c9e34af59e44a840f9`
- 评分方式：官方 Docker harness 执行 FAIL_TO_PASS 与 PASS_TO_PASS
- 2026-07-16 单样本：`astropy__astropy-12907` 已生成 predictions；模型补丁只新增复现脚本，没有修改目标源码，不能视为修复成功
- 官方评分状态：harness 的 fixtures 导入与 `namespace=none` 本地构建参数已修正；基础镜像构建连续两次因 Ubuntu archive 返回 HTTP 503 中断，当前没有有效 resolved 结果
- 许可说明：官方 harness 使用 MIT；数据实例对应的源仓库可能采用不同许可

## LongMemEval

- 官方数据：`xiaowu0162/longmemeval-cleaned`
- 数据修订：`98d7416c24c778c2fee6e6f3006e7a073259d48f`
- 采用文件：`longmemeval_oracle.json`
- 样本数量：500 条
- 本地文件大小：15,388,478 字节
- SHA-256：`821a2034d219ab45846873dd14c14f12cfe7776e73527a483f9dac095d38620c`
- 官方 harness 修订：`9e0b455f4ef0e2ab8f2e582289761153549043fc`
- 评分方式：先生成 `question_id + hypothesis`，再运行官方 `evaluate_qa.py` 的 LLM judge
- 当前首轮报告中的 normalized answer hit 只作为无 judge 的代理指标，不能写成官方 LongMemEval accuracy
- 未下载 `longmemeval_s_cleaned.json`（277,383,467 字节）和 `longmemeval_m_cleaned.json`（2,737,100,077 字节），避免在首轮适配时引入无必要的大文件

## LongBench v1

- 官方数据：`THUDM/LongBench`
- harness 修订：`2e00731f8d0bff23dc4325161044d0ed8af94c1e`
- 数据包大小：113,932,529 字节
- SHA-256：`cb45b11a4133c6bc1d6a44b0f8e701335ff1e543195db1103472e575857f7f64`
- 首轮真实评测子集：`passage_count`、`passage_retrieval_en`
- 评分方式：复用官方 prompt；计数和段落检索按官方数字匹配指标计算
- LongBench v2 `data.json` 当前约 465 MB，首轮没有下载

## RULER v1

- 官方仓库：`NVIDIA/RULER`
- 当前官方 v1 流程分支：`rulerv1-ns`
- 分支修订：`e8bbff677ca2c239640dc90f93310dcf32408c93`
- 本地归档大小：6,706 字节
- SHA-256：`7fba5482e36b25368037a57526f409b115285c1b233e45b2a8f5058707029d36`
- 数据生成器来源：主分支修订 `38da79d79519ef87aa46ae804f838e1eab7f86d7`
- 首轮任务：`niah_single_1`
- 长度：4,096 token
- 随机种子：42
- 样本数量：3
- tokenizer：`cl100k_base`
- 评分方式：官方 `string_match_all`
- Windows 下旧版 `prepare.py` 使用 shell 拼接含换行模板，子进程失败时仍可能返回退出码 0；本批次直接调用官方 `niah.py` 参数入口，并检查生成文件长度

## 复现边界

- 真实模型结果受模型版本、服务端实现、限流和采样参数影响。
- SWE-bench 必须以官方 Docker harness 报告为准，不能根据生成补丁是否非空判断成功。
- LongMemEval 必须区分代理命中率和官方 LLM judge accuracy。
- LongBench 与 RULER 只汇报实际运行的任务名称、上下文长度和样本数量，不能外推到完整集合。
