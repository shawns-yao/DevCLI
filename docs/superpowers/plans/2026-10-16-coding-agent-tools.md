# 编码工具与无交互入口实施方案

状态：已完成；日期：2026-10-16。

目标：按用户确定的顺序收敛入口，支持构建循环、批量替换、文件名查找和脚本调用。

执行方式：当前会话顺序实施，不启动子代理。沿用 Java 17、Maven、现有工具管线、命令服务、权限规则和 AgentSessionRuntime。

1. 删除 search_code 默认注册及模型提示，保留显式索引和检索组件；核对上一轮四项收尾不再重复实现。
2. HostWarnCommandPolicy 白名单命令仅在显式 allow 命中且无更严格规则时免主机审批；加入 execute_command.timeout（秒），通过执行器声明贯通工具期限和命令进程期限，可覆盖默认批次上限，不新增配置或后台执行。
3. edit_file 增加 replace_all；精确匹配失败时复用 SeekSequence 的行匹配规则，仍拒绝默认多义匹配。新增 glob，复用 ProjectFileLister 和项目路径围栏。
4. 新增独立 PrintCommandLauncher，通过 Main 的 -p 分支装配现有会话运行时，stdout 只输出一次 JSON，未授权操作拒绝，不读取 stdin；trace 增加缓存命中率。
5. 按功能更新注释、AGENTS.md、README 与必要 ADR。先运行对应定向测试，再运行已授权 quick 回归；涉及入口时补对应交互回归。按功能提交并推送，保留 pelican-bicycle.html。

验收范围：用模型/命令服务替身验证规则、期限、编辑、文件发现和入口协议；使用临时文件与 SQLite。未运行真实模型任务时不宣称完整能力或缓存收益。

验证：定向 95 项通过（含实际主机命令审批/超时、Main 参数错误子进程）；quick 共 2198 项，0 失败、0 错误、21 跳过；phase16-smoke 共 103 项通过。替身模型验证不代表真实模型端到端能力，缓存收益未做 A/B。
