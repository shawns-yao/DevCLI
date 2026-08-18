# Checkout 协作评测清单

- 日期：2026-08-15
- Provider：OpenCode Go 的 DeepSeek-compatible endpoint
- 模型：`deepseek-v4-flash`
- 状态：无效配对运行，不用于模式优劣结论
- 原始报告：`target/agent-benchmark/checkout-run-1786763914976/checkout-collaboration-benchmark.json`
- 聚合结果：`Data/processed/devcli_checkout_benchmark_20260815_deepseek_v4_flash_invalid.json`
- 表格结果：`Data/processed/devcli_checkout_benchmark_20260815_deepseek_v4_flash_invalid.csv`

## 场景与验收

场景是多租户结账 Saga。模块包括访问策略、库存、支付、配送、通知 Outbox、审计和最终编排。公共契约明确了输入格式、租户边界、服务级与请求级幂等、线程安全、故障注入、严格逆序补偿以及活动状态与历史状态的语义。

隐藏验证共 38 项：架构 3 项、访问边界 2 项、库存 5 项、支付 4 项、配送 4 项、通知 3 项、审计 2 项、正常流程 4 项、补偿 4 项、拒绝边界 2 项、幂等 3 项、并发与租户隔离 2 项。

两种模式使用相同任务说明、初始工作区、模型、Provider、`read_file` / `write_file` / `list_dir` 白名单和编译后独立验证器。单 Agent 不再强制指定首个工具，避免仅该模式向 DeepSeek thinking endpoint 发送不兼容的 named `tool_choice`。

## 可复现命令

以下脚本只把 `.env` 的 OpenCode 连接信息映射到本进程，不输出密钥：

```powershell
$pairs = @{}
Get-Content -LiteralPath '.env' | ForEach-Object {
  if ($_ -match '^\s*([^#=][^=]*)=(.*)$') {
    $pairs[$matches[1].Trim()] = $matches[2].Trim()
  }
}
$env:DEEPSEEK_API_KEY = $pairs['OpenCode_API_KEY']
$env:DEEPSEEK_MODEL = $pairs['OpenCode_MODEL']
$env:DEEPSEEK_BASE_URL = $pairs['OpenCode_BASE_URL']

mvn -q "-Dtest=CheckoutCollaborationBenchmarkIT#compareSingleAndMultiAgentOnCheckoutSaga" `
  -DskipTests=false `
  "-Ddevcli.benchmark.checkout=true" `
  "-Ddevcli.it.checkout.provider=deepseek" `
  "-Ddevcli.llm.retry.max.attempts=1" `
  "-Ddevcli.llm.call.timeout.seconds=360" `
  "-Ddevcli.llm.read.timeout.seconds=180" `
  "-Ddevcli.llm.max.output.tokens=4096" test
```

## 结果与有效性

| 模式 | 有效 | 隐藏检查 | 耗时 | 调用数 | 报告 Token |
| --- | --- | ---: | ---: | ---: | ---: |
| 单 Agent | 否 | 1/38 | 365.3 秒 | 2（1 次失败） | 2,975 |
| Planner/Worker/Reviewer | 是 | 38/38 | 630.6 秒 | 31 | 568,279 |

单 Agent 的第二次模型调用发生传输超时，因此没有形成有效配对，不能用 `38/38` 对 `1/38` 说明 Team 优于单 Agent。此前另一次单 Agent 尝试因 DeepSeek thinking 模式不支持 named `tool_choice` 而无效；该协议差异已在夹具中消除，但本轮仍受网络超时中断。

## 结论边界

本次只证明 Team 链路可以在这个有模块边界、最终集成、权限边界、补偿、幂等和并发要求的任务中完整交付。它的代价是 31 次模型调用和 630.6 秒耗时。有效比较至少还需要双方正常完成的多轮配对运行，并增加单文件、高耦合重构和不同复杂度任务以降低场景偏置。
