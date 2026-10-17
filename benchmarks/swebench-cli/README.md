# 固定 Java 真实任务回归

20 个任务来自官方 [SWE-bench Multilingual](https://huggingface.co/datasets/SWE-bench/SWE-bench_Multilingual/tree/846e647b9f33c0b51b739d005d13d85493c9af09)，冻结修订为 `846e647b9f33c0b51b739d005d13d85493c9af09`。原始 Parquet 共 300 条，其中列明的 Java 仓库 43 条。文件 SHA-256 为 `92abca7cb527b41a9f66d03a26ce441ff7319e3a49f985998fd56be4bb9b08b2`。

按 manifest 的仓库顺序轮询，每个仓库按 instance_id 升序取题，取满 20 条；不根据模型输出或通过率筛选。`v1/cases.jsonl` 只含原始问题、仓库、base_commit 和任务 ID，不包含参考补丁、测试补丁或 hints。每题问题原文保持不变。数据集标注 MIT，源仓库代码沿用各自许可。

## 入口

执行器仅用 Python 标准库；每题通过 `java -jar <jar> -p <原始问题>`，工作目录是该题 base_commit 的真实 Git 仓库。没有绕过 Main、AgentSessionRuntime、权限或工具管线，也不直接调用 Agent 内部方法。

```powershell
# 无网络、无模型调用、无目录写入；只校验固定输入与入口计划
python benchmarks/swebench-cli/run.py --dry-run

# 下面两步仅在另行允许实际回归后执行
python benchmarks/swebench-cli/run.py --prepare-only --output target/swebench-cli/batch-001
# 在各题 workspace 中，通过正常 CLI 确认项目是否可信并配置所需窄权限
python benchmarks/swebench-cli/run.py --jar target/devcli-16.1.0-SNAPSHOT.jar --output target/swebench-cli/batch-001
```

准备阶段只从 GitHub 取原始仓库和固定提交；不运行项目或模型。准备失败时保留已准备目录，另建批次重做，不自动重试。运行阶段拒绝不同的 base_commit、初始源码改动和重复使用已执行批次。

调用继承当前进程的 provider、模型、凭据与权限配置，不复制凭据到工作区，不自动信任项目、不关闭审批。`-p` 遇到尚需人工确认的操作会拒绝；需要主机命令时，先在正常 CLI 确认项目信任与窄 allow 规则。测试项目应来自本清单的固定版本。

## 结果与评分

- 默认每题 1800 秒，可显式传 `--timeout`；不重试，不排除失败题。超时终止 CLI 进程组或 Windows 进程树。
- 预设 `N=20, E=0, V=20`。`run.json` 的 cli_completed / 20 只表示 CLI 返回完成状态；clone、Git 或进程启动失败单列为设施故障。JSON 无效、进程失败与超时单独记录。
- 每题保存 stdout JSON、stderr、模型生成的 Git 补丁和 runtime 日志。原始输出保留，不用模型文字伪造补丁；新文件通过 intent-to-add 纳入 diff。
- `predictions.jsonl` 使用官方预测字段，可交给固定版本官方 harness。`model_name_or_path` 是带 JAR 哈希的运行标签；实际 provider/model 配置另行记录，不把该标签当模型版本。
- 本脚本不计算 resolved、质量分数或测试通过率；须用同一冻结数据的官方 harness 结果。CLI 完成不等于解决任务；评分时仍以 V=20 为分母，不能丢弃无输出或外部失败。

产物放 `target/swebench-cli/`，不提交。JAR SHA-256、固定样本、入口、超时、原始输出与失败归因共同构成一次批次记录。完整付费模型任务本轮未执行；dry-run 只证明输入校验和编排计划可用。
