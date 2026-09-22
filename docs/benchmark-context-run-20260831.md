# Coding Agent 上下文实验记录（2026-08-31）

## 范围与固定条件

先完成上下文实验，记忆实验不重跑，暂不运行固定 Plan 与 RAG。模型固定为 `gpt-5.6-luna`，日志中的 `provider=openai` 表示现有 OpenAI-compatible 渠道，模型名称是渠道别名，不保证底层供应商身份。

- 冒烟测试：公开 SWE-bench Multilingual Java 的 `apache__druid-13704`，仅 `solo-compact`。
- 实验压缩阈值为 12,000 Token；实际历史阈值还需扣除工具定义，本次为 11,182 Token。不是默认窗口效果评测。
- 2 轮 continuation 复用同一个 `AgentSessionRuntime`；每轮预算 1,000,000 Token、100 次迭代、最大输出 8,192 Token，不重新创建 Agent。
- 若成功触发摘要压缩，再用独立工作区运行同题 `solo-raw` / `solo-compact`，交给固定官方 harness 评分。每个条件一次，不挑最好结果。
- 不把多个 Issue 合并到一个工作区；模型工作区只含题面与 base commit，不包含官方参考补丁或测试补丁。

## 记录修复

生成脚本通过字段名解析 driver 头部，兼容 `continuationRounds`；记录每次压缩判断与每轮结束时的真实历史 Token，聚合 `peak_history_tokens`。`input_tokens/output_tokens` 保留 Agent 调用口径，`summary_*_tokens` 单列，`total_*_tokens` 为两者之和；`estimated_cost_cny` 仅覆盖 Agent 调用，不能解释为含摘要的总费用。未收到用量的失败请求不能解释为零成本。

每个条件写入冻结 `run-spec.json`，记录数据 SHA-256、源码及脚本指纹、base commit、模型、轮数、阈值和预算。旧结果缺少这些字段时拒绝直接复用，不通过补写新来源字段把历史运行伪装成新运行。

503、模型不可用、认证、限流、网络及超时故障单列为 `external_failure`，同时设置 `valid_sample=false`。Agent 终态模型故障不会继续额外 continuation；批次遇到外部故障停止，不自动重新生成。上下文超限独立记录，不作为外部网络故障排除。官方评分入口拒收旧来源协议，并展示原始、排除、缺失及有效样本数。

## 冒烟记录

首次批次 `Test/swebench-multilingual-java/luna-context-smoke-20260831-12k/` 因隔离检查发现旧驱动在禁用召回前仍初始化默认记忆目录而主动中断。未直接查询用户记忆数据库，也未核查其内容或变更；不能宣称旧初始化没有接触默认存储。该批次保留日志和结果，原始 1 条、执行中断 1 条、有效 0 条，不计质量或成本收益。

修复后 driver 在创建 Agent/MemoryManager 前，把记忆目录显式设为各条件输出目录下的 `memory/`；模型只访问独立代码工作区，记忆数据库不进入补丁。日志使用 `memoryScope=isolated`，不再输出工作区绝对路径。

修复后批次为 `Test/swebench-multilingual-java/luna-context-smoke-isolated-20260831-12k/`。观察到 3 次成功压缩；首次历史窗口从 16,668 降至 9,866 Token。7 次有返回用量的摘要调用累计输入 24,700、输出 2,941 Token，不包含终止时在途请求的未知成本。达到阈值诊断目标后主动结束，不作为完整解题样本或官方质量成绩。

该冒烟的 149,531 字节补丁全部来自 `.devcli/microcompact_message_outputs/` 运行证据，没有业务代码变更。当前导出脚本已排除两类 microcompact 运行目录，但保留原始证据。不能用补丁大小代表代码完成程度。

## 配对预注册

在查看任何配对结果前，固定同题、Luna、12K 阈值、2 轮 continuation、每轮 300,000 Agent Token / 24 次迭代、单次最大输出 8,192 Token，顺序为 `solo-raw` 再 `solo-compact`。摘要用量单列并计入总 Token 指标；Agent 预算不是包含所有摘要请求的硬计费上限。

批次目录：`Test/swebench-multilingual-java/luna-context-paired-20260831-12k/`；两侧使用独立代码工作区、运行主目录和记忆目录。当前脚本冻结源码后独立编译，避免其他构建覆盖在途实验的 class 文件。较低阈值的单题结果不能泛化为默认长上下文收益。

## 验证与限制

- 定向测试：4 个 Java 测试类，隔离修复后重新执行 68 项，0 失败、0 错误、0 跳过。独立报告保存在 `Test/context-observability-20260831/java-reports/`。
- 定向测试（记录解析，不计项目功能指标）：3 组检查通过，覆盖历史头部、窗口/摘要统计、外部失败识别。
- 定向测试（官方评分入口分母门禁）：对首次中断批次调用评分脚本，输出 `original=1 excluded=1 missing=0 valid=0`，没有启动官方评分容器或生成质量成绩。
- 定向检查（补丁导出）：通过当前 `Export-ModelPatch` 对真实冒烟工作区导出，排除运行缓存后补丁为 0 字节。
- 静态检查：PowerShell 语法、UTF-8 无 BOM 和 `git diff --check` 通过。
- 尚无本轮有效 raw/compact 官方配对，不宣称压缩节省 Token 或保持修复质量。
- 未运行全量回归、默认阈值大样本测试、记忆重测或 AgentDojo；未提交、未推送。

## 涉及文件

| 文件 | 内容 |
| --- | --- |
| `src/main/java/com/devcli/memory/ConversationHistoryCompactor.java` | 判断、摘要调用与错误日志 |
| `src/test/java/com/devcli/eval/SweBenchDriver.java` | 真实会话指标、外部故障停止续跑、隔离记忆 |
| `src/test/java/com/devcli/eval/SweBenchDriverIsolationTest.java` | 真实会话外部故障定向回归 |
| `scripts/swe-bench-multilingual-java.ps1` | 来源校验、冻结构建、窗口与成本汇总、隔离配置、缓存排除 |
| `scripts/score-swebench-official.ps1` | 官方评分入口的来源与样本分母校验 |
| `TODO.md`、本文件 | 当前状态、实验协议、原始结果与限制 |

工作区包含此前的未提交修改；此表不代表清空、重写或提交其他修改。
