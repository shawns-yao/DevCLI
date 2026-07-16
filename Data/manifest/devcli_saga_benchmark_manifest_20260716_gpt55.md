# Saga 协作评测清单

- 评测日期：2026-07-16
- Provider：OpenAI-compatible
- 模型：gpt-5.5
- 有效运行：`target/agent-benchmark/saga-run-1784187900060/`
- 原始报告：`target/agent-benchmark/saga-run-1784187900060/saga-collaboration-benchmark.json`
- 聚合结果：`Data/processed/devcli_saga_benchmark_20260716_gpt55.json`
- 表格结果：`Data/processed/devcli_saga_benchmark_20260716_gpt55.csv`

## 场景

订单履约 Saga 包含库存、支付、配送、通知、审计和履约编排六个模块。公共 Java 契约只读。Planner/Worker/Reviewer 应将前五个模块拆为独立步骤，履约编排作为最终集成步骤。

## 验证范围

隐藏检查共 30 项：架构约束 3 项、模块行为 17 项、正常流程 4 项、反向补偿 3 项、幂等 2 项、并发 1 项。模型仅可使用 `read_file`、`write_file` 和 `list_dir`。工具白名单在隔离项目 ToolRegistry fork 中保持。模型结束后由 JDK 编译器和隔离类加载器执行隐藏验证。

## 有效结果

| 模式 | 隐藏检查 | 完成率 | 耗时 |
| --- | ---: | ---: | ---: |
| 单 Agent | 27/30 | 90.0% | 192.8 秒 |
| Planner/Worker/Reviewer | 30/30 | 100.0% | 725.1 秒 |

Planner/Worker/Reviewer 提升 10 个百分点，耗时增加 532.3 秒，为单 Agent 的 3.76 倍。

## 无效运行处理

首次运行中，测试侧工具注册表在隔离项目 fork 后退化为普通 ToolRegistry，使 Worker 重新看到 `execute_command`，并受到本机 Docker 镜像状态影响。该问题已修复并增加针对性测试。首次运行结果不纳入统计。

## 限制

当前只有一次有效真实模型运行，不具备统计稳定性。结果只能说明该 Saga 场景中出现正确率收益，不能外推为通用 Multi-Agent 优势。OpenAI 兼容端点仍存在完整 content 重复发送问题，但本次隐藏验证直接检查落盘代码，不依赖最终文本展示。
