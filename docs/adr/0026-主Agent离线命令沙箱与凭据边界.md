# ADR 0026：主 Agent 离线命令沙箱与凭据边界

状态：已接受，2026-10-17。修订 ADR 0014 的命令启用范围与 ADR 0021 的主 Agent 主机规则放行。

## 问题与已核对链路

execute_command 经 ToolExecutionCore 调用命令后端。此前只有 ISOLATED_PROJECT 请求沙箱，主 Agent 直接在主机启动进程；ProcessBuilder 继承全部环境，命令输出直接写入工具结果归档。命令输出没有进入敏感内容审批，分页读取不能补救已经保存的原文。read_file 已检查完整文件快照并对返回区间审查，不另建文件读取机制。

## 决定

- 主 Agent 与子任务默认请求既有命令沙箱。后端仍由 DEVCLI_COMMAND_SANDBOX_MODE 选择，默认 Docker，Windows 原生后端仍需显式配置；没有启动失败后自动回退主机的路径。
- 主 Agent 复用 WorkspaceExecutionSession、工作区物化与 PatchSet，不给进程新增访问真实项目的授权。使用现有凭据排除、路径保护、租约、版本闸门及提交锁。命令产生的普通变更经检查后回写；不引入第二套 Agent 循环或审查流程。
- 非零退出仍可包含有效文件变更，合法变更正常回写，命令结果保留失败状态。取消、超时或输出不完整不提交。文件删除须走 delete_files 的文件清单及哈希审批；命令候选包含删除时整批不提交。构建产物和排除目录不回写。
- 审批依据 providesIsolation 判断实际隔离能力，删除仅区分宿主位置的 executesOnHost 接口。AppContainer 虽在 Windows 主机运行，仍属于隔离后端；HOST_RESTRICTED/HOST_WARN 不属于操作系统隔离。
- hard_deny、soft_deny、命令硬策略、删除等逐次确认仍先生效。真正隔离的命令随后直接执行，不再调用分类器。项目切换和审批中修改隔离边界会拒绝调用。
- 仅主 Agent 可显式请求 unsandboxed=true。脱离沙箱的主 Agent 命令在真实项目执行，必须本次人工批准；规则、权限模式、任务授权与历史批准不能覆盖，不能记住此批准。配置为主机白名单后端的主 Agent 命令同样逐次确认。
- 用户本轮选择先做离线隔离。网络需求通过上述明确主机授权处理，不放开容器网络、不新增域名代理或系统网络策略，也不宣称主机联网受到域名限制。
- 主机命令及 Docker 客户端环境中，移除名称含 KEY、TOKEN、SECRET、PASSWORD、PASSWD、CREDENTIAL、AUTH、COOKIE 的变量，大小写不敏感。devcli.command.env.allow 优先于 DEVCLI_COMMAND_ENV_ALLOW，使用逗号分隔的明确变量名额外放行，不接受通配符或值。不把放行项自动注入容器；原生启动器继续构造独立最小环境。
- 命令输出在既有 4 Mi 字符配额内捕获完整快照，复用 SensitiveContentPolicy 检查敏感字段、常见令牌、私钥等；脱敏后才写归档、计算哈希、生成预览和 result_ref。这样分页和流读取块边界不会绕过检测。内存有明确上限，不按无界行缓存；超过配额终止并标记结果不完整。

## 限制与验收边界

环境名称过滤不扫描任意变量值，也不识别所有编码形式。明确放行的变量会交给相应主机进程；不可用它传递模型凭据。沙箱工作区只排除已知凭据文件，不能保证任意源码中没有秘密。文件读取继续允许用户单次批准原文。

现有原生后端拒绝访问 .git，Docker 的工作区也不保证可访问宿主 Git 元数据；需要真实仓库元数据的命令应明确请求主机执行。node_modules、target 等不进入工作区，离线依赖通过已有工具链及依赖目录授权提供。这是安全边界，不能用自动主机回退掩盖兼容问题。

镜像必须提前准备，运行时保持 --pull never。既有默认 Maven 镜像为 JDK 17、不含 npm；本仓库构建要求 JDK 21，须用现有镜像配置明确提供匹配工具链，不把默认镜像称为通用编码环境。

本轮遵循用户要求先实现、不运行测试；编译与静态差异检查分别报告。Docker 启动不等于主 Agent 真实链路通过，未运行 Maven、Git、npm 沙箱兼容验收，也未运行原生 ACL/禁网验证。后续须从真实 CLI 入口核对这些行为，不能用替身或配置存在宣称隔离有效。

AppContainer 的网络能力模型参见 [Microsoft：Launch an AppContainer](https://learn.microsoft.com/en-us/windows/win32/secauthz/implementing-an-appcontainer)。当前启动器不授予网络 capability；能力开放本身不能替代本项目尚未实现的域名出口策略。
