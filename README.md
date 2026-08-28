# DevCLI

[![CI](https://github.com/shawns-yao/DevCLI/actions/workflows/ci.yml/badge.svg)](https://github.com/shawns-yao/DevCLI/actions/workflows/ci.yml)

![DevCLI startup demo](images/Snipaste_2026-05-20_16-57-44.png)

DevCLI 是一个面向 Java 后端开发者的终端 Agent CLI。它可以在命令行中通过自然语言驱动代码阅读、生成、调试、重构、命令执行和仓库检索。

ReAct 主循环、Plan 多 Agent 编排、MCP 协议客户端、上下文压缩、RAG 检索与终端渲染全部自行实现，不依赖 Spring AI、LangChain4j 等 Agent 框架。

## Project Snapshot

| 项 | 数值 |
| --- | --- |
| 主源码 | 308 个文件 / 51,683 行，26 个顶层模块 |
| 测试 | 232 个文件 / 34,510 行，1344 个用例全部通过 |
| 语言与构建 | Java 17 + Maven，产出单一可执行 jar |
| 迭代 | 230 次提交（2026-04 起） |

量化评测以公开数据集和官方评分器为准。旧的项目内 CLI、订单 Saga、Checkout、记忆、压缩、并发和合成 RAG 结果已退役，只保留在 [Benchmark Evaluation](docs/benchmark-evaluation.md) 的历史归档中，不作为当前能力或简历数字。

安装与启动见 [Install](#install) 与 [Startup](#startup)。

## Implementation Status

**已实现**

- 默认主 Agent 按需调用独立子 Agent；显式 `/plan` 保留 Planner、Worker、Reviewer DAG 编排，串行或并行由依赖与资源冲突决定。
- RAG（检索增强生成）：JavaParser 切分、SQLite 向量存储、关键词召回、代码关系图谱、RRF（倒数排名融合）与 CrossEncoderReranker（交叉编码器重排）。
- 三层记忆：`conversationHistory + RollingSummary` 管理当前线程上下文，`SessionMemory` 管理当前任务状态与证据，`LongTermMemory` 保存跨任务稳定事实；支持 Token 预算、作用域召回、持久晋升队列和隔离 Curator。
- MCP（Model Context Protocol）：手写 JSON-RPC 2.0 客户端，支持 stdio 与 Streamable HTTP，动态注册工具与 resources。
- Skill：jar 内置、用户级与项目级三层加载，`load_skill` 按需展开，allowedTools 白名单约束后续工具调用。
- 安全模型：HITL（人工审批）、路径围栏、命令快速拒绝与 JSONL 审计链。
- 隔离工作区与 PatchSet（补丁集）、文件级资源租约、跨步骤过期写入屏障、工具证据出处标记。
- Prompt 分层组装（jar 内置 / 用户级 / 项目级覆盖），system prompt 只承载会话级稳定内容以保证前缀缓存命中。
- 多模型运行时切换（Anthropic / OpenAI 兼容 / GLM / DeepSeek / StepFun / Kimi）。
- 联网与浏览器：`web_search`、`web_fetch` 正文提取，以及经 Chrome DevTools MCP 的浏览器操作与调试实例登录态复用。
- 两种终端渲染器：inline 流式（默认）与 plain；旧 lanterna/tui 配置在兼容期映射到 inline。

**部分实现（MVP）**

- LSP（语言服务器协议）诊断注入：仅实现协议子集，编辑后回灌编译诊断。
- Git Side-History 快照与回滚：turn 粒度快照与 `/restore`，尚未覆盖全部编辑入口。
- 后台任务与 Runtime API：共用 SQLite RunStore 与本地 HTTP/SSE 端点，仅监听回环地址。
- 图片输入：本地路径、file URL 与剪贴板图片。
- SWE-bench Lite：已产出官方格式 predictions JSONL，官方 harness 尚未跑出有效 resolved 结果。

**未实现**

- 符号级 Worker 上下文清单：当前过期写入屏障为文件级，拦不住「改方法签名 + 另一文件改调用方」的语义冲突。
- 上下文失效事件主动推送：当前为写入时惰性检测，不中断运行中的 Worker。
- per-Worker worktree（工作树）物理隔离。
- Reviewer 独立检索策略：与 Worker 共用同一套召回。
- MCP OAuth 授权与 `sampling/createMessage`。

## Feature Overview

DevCLI 的目标不是做一个普通聊天壳，而是把“模型、工具、代码仓库、记忆、审批、终端交互”串成一个本地开发工作流。顶层控制流只有默认 ReAct 与显式 Plan 两类；系统不根据任务内容静默切换：

- `ReAct`：默认主 Agent 边执行边选择工具，也可通过 `delegate_task` 委派 explorer、planner、worker、reviewer。是否委派、何时汇总由主模型决定，不强制经过固定三角色流水线；主 Agent 负责最终验收。
- `Plan`：通过 `/plan` 进入。Planner 生成 DAG 和可验证验收标准，计划 Reviewer 先检查语义闭环，Worker 在隔离工作区执行节点，Pre-Review 做硬验证，产物 Reviewer 根据真实证据验收；只有审查通过且 PatchSet 无冲突时才修改主项目。节点串行或并行由 DAG 就绪状态和资源冲突分波决定，不再由用户选择配置。

围绕这些路径，DevCLI 提供以下能力：

- `ToolRegistry（工具注册表）`：统一管理内置工具、MCP 动态工具和 resource 读取工具；工具调用通过分阶段中间件执行取消检查、存在性检查、Skill 权限、JSON Schema 参数校验、HITL、策略、审计和结果尺寸治理。内置 Provider 直接返回带状态、错误码和重试语义的结构化结果，命令非零退出、参数错误、策略拒绝、超时和取消不再依赖文本识别。
- `RAG（检索增强生成）`：用 JavaParser 切分 Java 代码，结合 SQLite 向量存储、关键词召回、代码关系图谱、RRF（倒数排名融合）、symbol-aware boost（符号感知加权）和 CrossEncoderReranker（交叉编码器重排），把相关类、方法、调用链注入模型上下文。
- `Memory（记忆）`：按线程上下文、任务工作状态和跨任务事实分层。九类上下文信息由六段 `RollingSummary` 与 `SessionMemory` 中的待办任务、当前工作、下一步动作共同提供；长期事实通过显式保存或隔离 Curator 晋升进入 SQLite。
- `Prompt（提示词分层）`：base、personality、mode、approval、project_context、skills、context_mgmt、handoff 分层组装，支持 jar 内置、用户级和项目级覆盖。
- `Skill（技能）`：`load_skill` 按需加载完整指引；已加载 Skill 的允许工具白名单会限制后续工具调用，压缩后恢复保留 context、allowedTools 和内容摘要。
- `MCP（Model Context Protocol）`：支持 stdio / streamable HTTP MCP server，动态加载工具和 resources，并把 MCP server 状态、日志、重启能力暴露给 CLI。
- `HITL（Human-in-the-Loop）`：危险工具和敏感页面操作进入人工审批；审批前先过策略层，策略拒绝的操作不能靠用户批准绕过。
- `Snapshot（快照）`：通过 Side-Git 在 turn 前后保存快照，支持回滚最近一轮变更，并按 `devcli.snapshot.max` 自动裁剪旧快照，降低 Agent 自动改文件的风险。
- `Renderer（渲染器）`：默认 inline 模式提供底部状态栏、行内 thinking、工具块和 diff；plain 用于无 ANSI、重定向和自动化环境。
- `Runtime API + RunStore`：本地 HTTP API 暴露 threads / branches / turns / events；CLI turn、Runtime turn 和后台任务共用 `runtime.db` 中的 Run 生命周期。同一 thread 的 turn 按提交顺序串行执行，不同 thread 可并行。
- `Session Tree（会话树）`：CLI 的 `/session` 与 Runtime branch 共用持久事件树；切换分支只重建模型上下文，不恢复或修改工作区文件。`/branch` 是兼容别名。
- `RunContext（运行上下文）`：每次交互、后台任务或无头 turn 绑定独立项目路径、取消令牌和资源生命周期；预先创建的线程不会读取其他任务的取消状态，无头 Agent 结束后会关闭本次创建的工具与记忆资源。
- `AgentExecutionEngine（执行引擎）`：ReAct、Plan task 和 SubAgent 共用同一套取消、预算、LLM 调用、工具消息回灌和异常控制流程；每次模型采样具有稳定请求标识和独立取消边界，重复请求会替换并取消旧请求。
- `ExecutionGraph（执行图）`：Plan 与 Multi-Agent 共用依赖就绪判断、最终集成调度、缺失依赖和环检测，避免两条编排路径各自实现 DAG 规则。
- `OrchestrationProfile + OrchestrationWaveExecutor（编排配置与波次执行器）`：公开 Plan 固定启用 Worker、Reviewer 和 checkpoint；波次执行器按 DAG 与资源冲突使用有界线程池，并统一异常归属、独立输出缓冲与稳定顺序归并。STANDARD 仅保留为内部兼容实现，不再进入 CLI 路由。
- `ExecutionArtifact（执行产物）`：Plan `Task`、Multi-Agent `ExecutionStep` 和 checkpoint 共用状态、输出、摘要、修改资源、错误、尝试次数与时间戳；checkpoint 协议版本 8 额外保存验收方式、验证器和适用节点，并保存 PatchSet 写前日志、稳定子代理身份、步骤分配、消息游标、有界失败尝试摘要和已消耗的在位重做额度，拒绝未来版本。
- `Workspace + PatchSet（隔离工作区与补丁集）`：副作用任务通过可替换后端物化隔离目录；Git 仓库默认使用原生 worktree 并叠加当前脏文件、删除文件、未跟踪及被忽略文件（常见 `.env`、凭据和密钥文件会过滤），非 Git 目录优先使用文件系统级写时复制，不支持时回退有界复制；PatchSet 逐文件流式哈希，只把变更文件内容载入内存，并校验内容哈希、保留可执行标记；JVM 公平锁与跨进程文件锁共同串行化补丁预检、应用、Git worktree 元数据操作和 checkpoint 终态。
- `Image Input`：支持 `@image:` 本地路径、file URL 和剪贴板图片，图片会做尺寸、格式和大小处理后进入模型输入。

## Architecture

主执行链路：

```text
Main
├── Agent                  # 默认主 Agent，直接执行或按需委派
│   └── DelegationSession  # 独立上下文、角色能力、共享预算、隔离工作区
└── AgentOrchestrator      # /plan；Planner / Worker / Reviewer
    ├── ExecutionGraph             # DAG 校验与就绪节点
    ├── MultiAgentBatchExecutor    # Worker 分配、资源分波与公平锁
    └── OrchestrationWaveExecutor # 有界并发与稳定输出归并

各路径共享：
├── ToolRegistry           # 内置工具 + MCP 工具 + resources
├── MemoryManager          # 上下文、任务投影、长期记忆的薄协调门面
├── SnapshotService        # turn 前后快照
├── PromptAssembler        # 分层 prompt 组装
├── Renderer               # inline / plain
└── McpServerManager       # MCP server 生命周期
```

关键边界：

- 所有内置 LLM Provider 使用统一 `LlmException` 错误模型，区分认证、限流、过载、超时、网络、参数、上下文超限、内容过滤、服务端、响应格式和主动取消。限流、过载、超时、网络和 5xx 按指数退避与 jitter 有界重试；已取消请求和已经输出流式内容的请求不重试，避免重复正文或工具调用。SubAgent 会把标准错误码和 `retryable` 标记保留到编排层，瞬时故障判断不依赖具体网络错误文案。
- `ConversationHistoryCompactor（对话历史压缩器）` 按 Token 预算治理 LLM messages 窗口。`microcompact` 处理单条超大消息；首次摘要使用 Map-Reduce，后续通过生命周期增量操作维护六段 `RollingSummary`：主要请求与意图、关键技术概念、文件和代码、踩过的坑和修复、问题解决过程、逐条用户消息。摘要提交前经过运行时语义守卫，默认每 5 次成功压缩执行一次生命周期 GC。
- 本地 `@path` 和 MCP resource 在展开阶段按剩余 Token 预算选择内联或不可变快照引用；内容型请求由程序强制回读，后续“里面/该文件/附件”等跨轮追问复用最近引用。元数据问题不强制读取；错误路径、读取失败或快照哈希变化达到两次后失败关闭。
- `SessionMemory（工作记忆）` 是当前任务共享运行投影，通过统一、幂等的事件入口维护 WorkState、EvidenceJournal、待办任务、当前工作和下一步动作；ReAct、Plan 与 Team 使用 taskId 轮换，并按角色与 Token 预算生成上下文视图。
- `LongTermMemory（长期记忆）` 保存跨任务稳定事实。任务结束时，脱敏且限长的任务快照先写入 SQLite 晋升队列，再由空工具、无历史记忆的隔离 Curator 输出 `SAVE / CONFIRM / SKIP`。自动 `SAVE` 必须为 HIGH 且能解析出快照原文，落库为 `CURATED`；人工确认后为 `REVIEWED`。检索按 `scope_type/scope_key` 隔离并融合关键词与向量结果；实际注入只更新召回观测，用户确认或同值重复显式保存才刷新新鲜度并分档延长 TTL。来源原文以脱敏快照和 SHA-256 固化，不依赖原会话保留。
- `PathGuard（路径围栏）` 负责限制文件访问不逃逸项目根。
- `ToolEffect + ToolAccessScope（工具副作用能力）` 由执行管线强制：非隔离分析任务只获得只读能力，隔离任务才允许项目写入和主机命令；MCP 缺失只读注解或声明 destructive/openWorld 时按外部副作用处理。工具参数先转换为稳定语义指纹，字段顺序、查询大小写、Unicode 等价字符和冗余空白不再绕过停滞检测；正则 pattern 保持大小写敏感，避免错误缓存命中；成功的只读结果会短期缓存，任何副作用执行都会清空缓存。
- `ResourceLeaseManager（资源租约管理器）` 在 `/plan` 并行执行时拦截 `write_file` / `edit_file`，同一文件只能被一个运行中步骤写入；并行工具线程会继承步骤租约归属，任务结束后释放租约。`ToolRegistry` 托管共享后台清理器，project fork 复用同一线程，最后一个注册表关闭后停止；周期可通过 `DEVCLI_RESOURCE_LEASE_CLEANUP_INTERVAL_SECONDS` 调整。
- `PatchSet（补丁集）` 是隔离结果进入主项目的唯一文件回写边界：JVM 公平锁和 `~/.devcli/locks/project-commit/` 下的跨进程文件锁覆盖预检、应用和 checkpoint 终态；构建阶段流式计算哈希，未变化文件不读取完整内容。协议版本 8 在应用前保存目标哈希与原文件备份，并保存验收元数据及适用节点、原步骤对应 Worker/Reviewer 身份、按步骤归属的有界 AttemptDigest、在位重做次数和失败现场；恢复时按最终哈希提升完成、继续待执行或自动回滚，同时保持原步骤分配和原重做额度。Reviewer 拒绝、任务失败、用户取消、前置哈希冲突、非普通文件覆盖或路径/链接逃逸都会阻止整批应用。
- `CommandGuard（命令防线）` 是危险命令快速拒绝层，不替代 HITL 和路径策略。
- `HitlToolRegistry（审批工具注册表）` 位于真实工具执行前，保证危险操作先经过审批和策略判定。

## Requirements

- Java 17+
- Maven 3.8+
- Node.js / npm，只有使用默认 Chrome DevTools MCP 时需要
- 至少一个 LLM API Key；默认 provider 是 Anthropic Messages 原生接口：
  - `ANTHROPIC_AUTH_TOKEN`（Claude / Anthropic Messages 兼容端点，可配 `ANTHROPIC_BASE_URL` / `ANTHROPIC_MODEL` / `ANTHROPIC_MAX_TOKENS`）
  - `OPENAI_API_KEY`（OpenAI 官方或兼容端点，可配 `OPENAI_BASE_URL` / `OPENAI_MODEL`）
  - `GLM_API_KEY`
  - `DEEPSEEK_API_KEY`
  - `STEP_API_KEY`
  - `KIMI_API_KEY` 或 `MOONSHOT_API_KEY`

Embedding（向量检索）默认使用 Ollama：

- Ollama 本地服务：`http://localhost:11434`
- 默认模型：`nomic-embed-text:latest`

如果不使用本地 Ollama，可以在 `.env` 中配置远程 embedding provider。
`/index` 建立 RAG 索引时会按文件批量生成 chunk embedding；批量请求失败或返回数量异常时，会逐条降级处理并保留成功 chunk，避免单个批次故障导致整文件索引丢失。

RAG 检索默认使用 keyword + semantic + bounded graph 的 RRF（倒数排名融合），再叠加 symbol-aware boost（符号感知加权），最后调用
Cross-Encoder（交叉编码器）做二阶段 rerank。默认 rerank endpoint 是本地 Docker
暴露的 `http://localhost:8000/v1/rerank`；不可用时会自动降级回 RRF 结果。

## Install

克隆仓库：

```bash
git clone https://github.com/shawns-yao/DevCLI.git
cd DevCLI
```

复制配置文件：

```bash
cp .env.example .env
```

编辑 `.env`，默认填写 Anthropic Messages 配置：

```bash
ANTHROPIC_AUTH_TOKEN=your_api_key_here
ANTHROPIC_BASE_URL=https://api.anthropic.com
ANTHROPIC_MODEL=claude-sonnet-4-20250514
ANTHROPIC_MAX_TOKENS=8192
```

也可以改填 `OPENAI_API_KEY`、`GLM_API_KEY`、`DEEPSEEK_API_KEY`、`STEP_API_KEY` 或 `KIMI_API_KEY`，运行时用 `/model` 切换 provider。

如果使用默认本地 embedding：

```bash
ollama pull nomic-embed-text:latest
ollama serve
```

构建 jar：

```bash
mvn clean package
```

运行命令：

```bash
java -jar target/devcli-1.0-SNAPSHOT.jar
```

也可以直接用 Maven 启动：

```bash
mvn clean compile exec:java -Dexec.mainClass="com.devcli.cli.Main"
```

## Startup

启动后会进入交互式终端。README 中展示的品牌输出使用 DevCLI：

```text
██████╗  ███████╗██╗   ██╗
██╔══██╗ ██╔════╝██║   ██║
██║  ██║ █████╗  ██║   ██║    DevCLI
██║  ██║ ██╔══╝  ╚██╗ ██╔╝    ReAct · Plan · Team · MCP · RAG
██████╔╝ ███████╗ ╚████╔╝
╚═════╝  ╚══════╝  ╚═══╝

Tips for getting started:
1. Type / for commands and Tab completion
2. Ask coding questions, edit code or run commands
3. Attach context with @path or @image:

* 你好

> 你好
DevCLI: 你好，我在。可以直接描述要阅读、修改或运行的任务。
```

## Configuration

### LLM

DevCLI 会从 `.env` 或系统环境变量读取模型配置。

常用配置：

```bash
ANTHROPIC_AUTH_TOKEN=your_api_key_here
ANTHROPIC_BASE_URL=https://api.anthropic.com
ANTHROPIC_MODEL=claude-sonnet-4-20250514
ANTHROPIC_MAX_TOKENS=8192

OPENAI_API_KEY=your_api_key_here
OPENAI_MODEL=gpt-4o
OPENAI_BASE_URL=https://api.openai.com/v1
# 中转站如要求渠道/分组，可选配
OPENAI_CHANNEL=Other
OPENAI_GROUP=Other

GLM_API_KEY=your_api_key_here
GLM_MODEL=glm-5.1

DEEPSEEK_API_KEY=your_api_key_here
DEEPSEEK_MODEL=deepseek-v4-flash
DEEPSEEK_BASE_URL=https://api.deepseek.com

STEP_API_KEY=your_api_key_here
STEP_MODEL=step-3.5-flash

KIMI_API_KEY=your_api_key_here
KIMI_MODEL=kimi-k2.6
```

未显式切换时默认使用 `anthropic` provider；运行时可用 `/model` 切换已配置的 provider。

统一重试默认最多 3 次，初始退避 500ms、上限 8s、jitter 0.2，可通过 `DEVCLI_LLM_RETRY_MAX_ATTEMPTS`、`DEVCLI_LLM_RETRY_INITIAL_DELAY_MS`、`DEVCLI_LLM_RETRY_MAX_DELAY_MS`、`DEVCLI_LLM_RETRY_JITTER_RATIO` 调整。

长期记忆 TTL 可通过 `DEVCLI_MEMORY_TTL_DAYS` 设置统一值，或使用 `DEVCLI_MEMORY_TTL_FACT_DAYS`、`DEVCLI_MEMORY_TTL_FEEDBACK_DAYS`、`DEVCLI_MEMORY_TTL_SUMMARY_DAYS` 按类型覆盖。只读工具缓存默认 128 条、30 秒，可通过 `DEVCLI_TOOL_RESULT_CACHE_MAX_ENTRIES` 和 `DEVCLI_TOOL_RESULT_CACHE_TTL_SECONDS` 调整。

### Embedding

默认：

```bash
EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=nomic-embed-text:latest
EMBEDDING_BASE_URL=http://localhost:11434
```

如果使用远程 embedding 服务：

```bash
EMBEDDING_PROVIDER=openai
EMBEDDING_MODEL=text-embedding-3-small
EMBEDDING_BASE_URL=https://api.openai.com/v1
EMBEDDING_API_KEY=your_api_key_here
```

### Rerank

默认：

```bash
RERANK_ENABLED=true
RERANK_PROVIDER=openai
RERANK_MODEL=BAAI/bge-reranker-v2-m3
RERANK_BASE_URL=http://localhost:8000/v1
```

如果本地 Docker rerank 服务不可用，检索会降级到 RRF 结果，不中断 Agent。

### Web Search

支持 `zhipu`、`serpapi`、`searxng`：

```bash
SEARCH_PROVIDER=zhipu
ZHIPU_SEARCH_ENGINE=search_std

# 或
SERPAPI_KEY=your_serpapi_key_here

# 或
SEARXNG_URL=http://localhost:8888
```

### MCP

MCP 配置文件：

- 用户级：`~/.devcli/mcp.json`
- 项目级：`.devcli/mcp.json`

MCP server 的 `readOnly` 注解默认不可信。每个 server 可通过 `trustReadOnlyAnnotations: true` 显式信任服务端注解，或用 `readOnlyTools` 配置本地只读工具允许列表；`deniedTools` 中的工具不会注册。`destructive` 或 `openWorld` 工具始终不能降级为只读，本地允许列表配置错误会扩大能力边界。

DevCLI 在默认配置缺失时会创建 Chrome DevTools MCP 示例配置：

```json
{
  "mcpServers": {
    "chrome-devtools": {
      "command": "npx",
      "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"]
    }
  }
}
```

手动配置远程 MCP server 示例：

```json
{
  "mcpServers": {
    "remote": {
      "url": "https://example.com/mcp",
      "headers": {
        "Authorization": "Bearer ${REMOTE_TOKEN}"
      }
    }
  }
}
```

### Renderer

默认使用 inline 流式终端界面：

```bash
DEVCLI_RENDERER=inline
```

可选值：

- `inline`：默认，底部状态栏、行内工具块、行内 diff。
- `plain`：纯文本输出。
- 旧 `lanterna`、`tui` 和 `DEVCLI_TUI=true`：兼容映射到 `inline`。

如果终端不支持底部状态栏：

```bash
DEVCLI_NO_STATUSBAR=true
```

### Runtime API

DevCLI 可以以本地 Runtime API 方式启动：

```bash
DEVCLI_RUNTIME_API_KEY=your_local_api_key \
java -jar target/devcli-1.0-SNAPSHOT.jar serve --http --port 8080
```

请求头：

```text
Authorization: Bearer your_local_api_key
```

Runtime API 默认仅绑定 `127.0.0.1`。HTTP 请求线程和 Agent 执行线程隔离；Agent 执行池默认 `2` 个线程、队列 `64`，可通过
`-Ddevcli.runtime.api.turn.threads` / `-Ddevcli.runtime.api.turn.queue` 调整。队列满时返回 `429 {"error":"runtime_busy"}`。长 thread 默认在历史达到 32,000 token 后持久化压缩检查点，可通过 `DEVCLI_RUNTIME_CHECKPOINT_TRIGGER_TOKENS` 或 `-Ddevcli.runtime.checkpoint.trigger.tokens` 调整，最小值为 4,000。

## Usage

启动后直接输入自然语言任务：

```text
* 帮我阅读这个项目的启动入口，并说明主要执行流程
```

让 Agent 修改代码：

```text
* 修复 UserService 中空指针问题，并补充对应单元测试
```

附加本地文件或目录上下文：

```text
* 阅读 @src/main/java/com/example/UserService.java，找出潜在 bug
* 根据 @docs/api.md 更新 Controller 参数校验
```

附加图片：

```text
* 分析 @image:/absolute/path/screenshot.png 里的报错
```

进入 Plan：

```text
/plan 重构订单模块，把校验逻辑从 Controller 下沉到 Service，并补充测试
```

Plan 使用 Multi-Agent 链路：Planner 拆 DAG 并提取 `acceptance_criteria`。每条标准必须声明 `test_signal`、`verification_method=TOOL|HUMAN`、`verifier` 和 `applies_to`；适用范围只能引用有效节点或 `FINAL`。普通节点只接收直接相关标准，Final integration 重新检查全部标准。计划先经过确定性结构与可执行性预检，再由独立、无工具上下文的 Reviewer 对照原始目标检查需求、节点和验收标准的覆盖关系；critical/high 标准还必须生成反例输入及预期失败信号。可通过 `DEVCLI_TEAM_REVIEWER_PROVIDER` / `DEVCLI_TEAM_REVIEWER_MODEL` 指定不同于 Planner 的评审模型；显式配置不可用时失败关闭，未配置时保持兼容并沿用主模型。语义拒绝会带结构化问题退回 Planner 有界修复，Reviewer 协议错误则失败关闭。机器评审通过后才展示给用户，可选择执行、补充后重规划或取消；非交互环境遇到人工标准时失败关闭。Worker 在步骤级隔离工作区实现，`PreReviewVerifier` 在同一隔离目录执行硬检查。Reviewer 再以独立产物评审上下文读取真实隔离产物，使用 `criteria_results` 逐条核对；TOOL 标准的声明验证器必须在本轮真实成功工具调用中出现，人工标准只能保持待确认。审查通过后生成 PatchSet，只有全量冲突预检通过才写回主项目。未完成 checkpoint 恢复前会重新执行计划语义评审。

Planner 输出允许在 JSON 前后出现少量说明，编排器会提取首个完整计划对象；无法解析、DAG 无效或出现“检查空工作区后再实现”这类阻塞性纯检查步骤时，会清空 Planner 历史并携带失败原因请求结构化修复。默认最多修复 2 次，可通过 `DEVCLI_TEAM_PLANNER_REPAIR_MAX_ATTEMPTS` 或 `-Ddevcli.team.planner.repair.max.attempts` 调整，取值范围 `[0, 3]`。空工作区属于合法输入，必要检查必须并入实现步骤并采用“若不存在则创建”的语义。Worker 最终文本为空时不再直接判失败：本轮存在 `SUCCESS` 工具证据则生成结构化执行摘要进入 Reviewer；没有成功证据时先执行一次强制协议修复，明确要求代码任务调用 `write_file` 并做最小验证、分析任务调用读取工具取得真实证据；该次 LLM 请求同时按步骤类型强制具体工具：文件写入与集成步骤选择 `write_file`，命令步骤选择 `execute_command`，其他步骤选择 `list_dir`；Anthropic Messages 映射为命名 `tool_choice`，OpenAI-compatible 映射为命名 function choice。FILE_WRITE / INTEGRATION 步骤出现成功 `write_file` 批次后直接以结构化证据结束当前 Worker 执行；强制修复中的指定工具也采用同一规则，不再请求模型生成收尾文本。Provider 忽略命名工具选择时，执行引擎追加一次严格 JSON 工具信封请求；只接受完整 JSON、目标工具名和对象参数，随后仍通过工具参数校验与权限管线执行，不解析 reasoning、Markdown 或代码围栏。工具失败时继续进入下一轮纠正，最终仍没有成功工具证据才判失败。

并行 Worker 数量默认 `2`，可通过 `DEVCLI_TEAM_WORKERS` 环境变量或 `-Ddevcli.team.workers` 系统属性调整（取值夹在 `[1, 8]`，非法值回退默认）。同一依赖批次内相互独立的步骤由 `MultiAgentBatchExecutor` 按 Worker 池大小并行执行；涉及相同写资源的步骤先分入不同执行波次，同一 Worker 通过公平锁避免历史竞争。`OrchestrationWaveExecutor` 统一使用有界线程池、异常归属、独立输出缓冲和稳定顺序归并。每个 Plan 执行波次会记录 `peakConcurrency`、墙钟耗时、步骤累计耗时和 `parallelismFactor` 到 trace，便于用真实任务计算并行利用率和加速效果。同批次使用冻结的 ForkContext，批次内步骤不会读取其他并行步骤中途产生的上下文；确有数据依赖的步骤必须通过 DAG dependency 进入后续波次。任务文本、流式状态、修改文件、摘要与错误统一封装为 `PlanTaskExecutionResult`。Reviewer 默认最多执行 2 轮，通常对应“读取证据 + 输出 JSON 审查”，可通过 `DEVCLI_TEAM_REVIEWER_MAX_ITERATIONS` 或 `-Ddevcli.team.reviewer.max.iterations` 调整到 `[1, 8]`；达到上限视为可恢复 Reviewer 故障，普通步骤仍要求 Pre-Review 硬检查实际通过才可降级。

隔离工作区默认开启，可通过 `DEVCLI_WORKSPACE_ISOLATION_ENABLED=false` 或 `-Ddevcli.workspace.isolation.enabled=false` 临时关闭；默认目录为项目下的 `Temp/devcli-workspaces`，可用 `-Ddevcli.workspace.dir=/path/to/workspaces` 覆盖。物化后端默认 `auto`：项目根是 Git 仓库时使用原生 worktree，共享 Git 对象并叠加当前工作区状态；非 Git 目录优先使用文件系统级写时复制。Linux 使用强制 reflink，现代 Windows 只在 ReFS 上启用系统块克隆；能力探测失败、克隆失败或内容校验不一致时清理部分结果并回退复制。可通过 `DEVCLI_WORKSPACE_BACKEND=git|cow|copy|auto` 显式选择。worktree 物化后会删除排除目录和符号链接，关闭时通过 Git 注销，崩溃残留元数据在后续创建前 prune。创建前会清理超过 24 小时且没有活动文件租约的孤儿目录，TTL 可用 `DEVCLI_WORKSPACE_ORPHAN_TTL_HOURS` 或 `-Ddevcli.workspace.orphan.ttl.hours` 调整。复制等待默认最多 300 秒，可用 `DEVCLI_WORKSPACE_COPY_TIMEOUT_SECONDS` 调整；超时或中断会取消复制线程，不再无限等待。隔离任务的 `execute_command` 和 Pre-Review 默认进入 Docker，使用无网络、只读根文件系统、能力清空和资源上限；Docker 不可用时默认失败关闭。Windows 裸机可显式设置 `DEVCLI_COMMAND_SANDBOX_MODE=HOST_WARN` 或 `-Ddevcli.command.sandbox.mode=HOST_WARN`，此模式不会自动回退，仅允许 Maven 离线执行 `clean/validate/compile/test-compile/test/package/verify`、`javac` 和只读 Git 子命令，拒绝命令行指定的任意 Maven 插件、发布阶段、命令串、管道、重定向、网络工具和写入型 Git 操作，并在工具结果和 Pre-Review 前输出风险提示。`HOST_WARN` 不是操作系统级沙箱，项目 POM 已绑定的插件仍可能产生主机副作用。默认镜像为 `maven:3.9.9-eclipse-temurin-17`，必须提前拉取，可通过 `DEVCLI_COMMAND_SANDBOX_IMAGE` 覆盖。写时复制后端设计见 `docs/filesystem-cow-workspace-design.md`。

失败恢复采用「在位重做」而非平行重规划：失败步骤保持原 id/依赖在 DAG 原位换思路重做（默认 1 次，带上次失败反馈），恢复始终长在原 DAG 上、通过依赖关系看到已完成成果。Reviewer 重试和 redo 用尽后保持失败终态，最终结果显式列出失败步骤、两类额度、最后原因、checkpoint ID 和人工处理选项，不自动改写整张图。协议版本 8 固化验收方式、验证器和适用节点，并恢复原 Worker 绑定、消息游标、有界失败尝试摘要、重做次数和失败现场；恢复注入按步骤隔离，避免把其他 Worker 排除的方案错配到当前步骤。旧协议缺失适用节点时迁移为 `FINAL`；缺失验证方式时迁移为人工验收。保存失败、回滚不完整、身份拓扑损坏或未来协议版本都会停止 resume。

常见任务写法：

```text
* 找出登录接口的完整调用链，并指出鉴权在哪里发生
* 检查最近一次改动有没有引入空指针、路径逃逸或命令执行风险
* 根据 @README.md 和 @src/main/java/com/devcli/cli/Main.java 更新启动说明
* 运行相关测试，失败时定位根因并修复
* 分析 @image:C:/tmp/error.png 中的报错截图，并给出修复路径
```

如果输入以 `/` 开头，CLI 会优先按命令解析；未识别命令会在 CLI 层报错，不回退给 Agent 当自然语言执行。

## Commands

常用命令：

| Command | Description |
|---------|-------------|
| `/help` | 查看帮助 |
| `/model` | 查看或切换模型 |
| `/plan` | 使用 Planner、Worker、Reviewer 编排执行任务 |
| `/plan resume [id]` | 从 checkpoint 恢复中断的 Plan 任务 |
| `/index` | 为当前仓库建立 RAG 索引 |
| `/search <query>` | 检索代码库 |
| `/graph <class>` | 查看代码关系图谱 |
| `/memory` | 查看记忆状态 |
| `/memory organize` | 生成长期记忆整理计划，不修改记忆 |
| `/memory organize apply` | 应用程序判定为低风险的整理项 |
| `/memory export` | 导出可读 Markdown 记忆审计快照（只读，不回写） |
| `/memory pending` | 查看等待人工确认的长期记忆候选 |
| `/memory confirm <id>` | 确认并保存长期记忆候选 |
| `/memory reject <id>` | 拒绝长期记忆候选 |
| `/memory clear` | 清空长期记忆 |
| `/save <fact>` | 保存长期事实 |
| `/save --pin <fact>` | 保存强约束事实，每轮全量注入 |
| `/mcp` | 查看 MCP server 状态 |
| `/mcp restart <name>` | 重启 MCP server |
| `/mcp logs <name>` | 查看 MCP server stderr 日志 |
| `/hitl on` | 开启人工审批 |
| `/hitl off` | 关闭人工审批 |
| `/policy` | 查看策略层状态 |
| `/audit [N]` | 查看最近 N 条审计日志 |
| `/snapshot` | 查看 Side-Git 快照状态 |
| `/trace [list\|<runId>]` | 查看最近一次运行的结构化执行追踪（默认）、最近运行列表或指定 run 时间线 |
| `/browser connect` | 连接可复用 Chrome 会话 |
| `/session status` | 查看当前持久会话与分支 |
| `/session tree` | 查看持久会话树 |
| `/session fork <name> [eventId]` | 从当前或指定事件创建分支 |
| `/session use <branch>` | 切换持久分支，只切换模型上下文 |
| `/clear` | 创建无继承历史的新根分支，旧历史保留 |
| `/exit` | 退出 |

命令补全：

- `/model` 支持 provider 补全。
- `/mcp` 支持 server 名称和子命令补全。
- `/skill` 支持 skill 名称和子命令补全。
- `@path` 支持本地文件、目录和 MCP resource mention 补全。
- `@image:` 支持本地图片路径补全。

## Built-in Tools

内置工具：

| Tool | Description |
|------|-------------|
| `read_file` | 读取文件 |
| `write_file` | 写入文件 |
| `edit_file` | 唯一匹配替换文件片段；空替换可用于删除 |
| `list_dir` | 列出目录 |
| `execute_command` | 执行短时 shell 命令 |
| `create_project` | 创建基础项目结构 |
| `search_code` | 检索代码库 |
| `grep_code` | 实时精确搜索当前工作区文本 |
| `web_search` | 搜索互联网 |
| `web_fetch` | 抓取已知 URL 并提取正文 |
| `save_memory` | 保存长期记忆 |
| `confirm_memory` | 确认或拒绝敏感长期记忆候选 |
| `list_memory` | 只读列出长期记忆 |
| `revert_turn` | 回滚最近 turn 的改动 |
| `mcp__{server}__{tool}` | MCP server 动态工具 |
| `mcp__{server}__read_resource` | 读取 MCP resource |

同一轮模型返回多个工具调用时，DevCLI 会并行执行可并行的工具，并按原始顺序把结果回灌给模型。

工具调用可靠性：工具定义以 JSON Schema 约束参数类型、必填项、枚举值和未知字段；`ToolRegistry` 在真实执行前通过 `json-schema-validator` + 本地兜底校验内置工具与 MCP 工具参数，非法 JSON、类型错误、空必填、非法枚举、pattern/minimum 等 schema 约束失败会以 `工具参数校验失败` 回传给模型修正。默认工具定义只注入内置核心工具和已激活 MCP 工具；ReAct、Plan 和 Multi-Agent turn 开始前会按当前用户输入预激活匹配到的 MCP 工具；`search_tools` 使用工具索引缓存，MCP 工具注册、卸载或替换后自动失效重建，命中的 MCP 工具会激活到后续工具定义。未知工具调用会返回 `search_tools` 引导和 query 示例，便于模型在工具集合变化或 MCP 工具未命中时重新检索可用工具。危险工具仍走 HITL 审批、策略拦截和 AuditLog；工具错误会回灌给模型继续纠偏，最终答复必须基于工具证据。

工具边界：

- `read_file` / `write_file` / `edit_file` 必须通过路径策略校验；写入与编辑单文件上限均为 5MB，内容未变化时不会推进上下文版本。
- `execute_command` 面向短时命令，不适合托管长期后台服务。
- `grep_code` 是实时精确文本搜索，适合类名、方法名、配置键、错误文本和固定字符串片段；`search_code` 保持 keyword + semantic + bounded graph 混合检索，适合自然语言理解、调用链和概念查询。
- `web_fetch` 适合已知 URL；遇到 SPA 或防爬限制时再切浏览器/MCP。
- `create_project` 只创建基础模板，不替代完整脚手架。
- MCP 工具名统一暴露为 `mcp__{server}__{tool}`，resource 读取暴露为虚拟工具；带 destructive/openWorld annotations 的 MCP 工具会强制逐次 HITL 审批，不复用全部放行缓存。
- MCP 工具结果进入尺寸治理后会附带折叠分类；中等输出标记 `INLINE_TRUNCATED`，超大输出落盘预览标记 `PERSISTED_PREVIEW`。

## Memory

DevCLI 的记忆系统覆盖九类上下文信息：

- `RollingSummary` 保存六类历史背景：主要请求与意图、关键技术概念、文件和代码、踩过的坑和修复、问题解决过程、逐条用户消息。
- `SessionMemory` 保存三类实时任务信息：待办任务、当前工作、下一步动作。

三层存储分别承担不同生命周期：

| 层级 | 存储 | 内容 | 生命周期 |
| --- | --- | --- | --- |
| 短期上下文 | `conversationHistory + RollingSummary` | 当前线程原始消息与六段历史摘要 | 当前线程 |
| 工作记忆 | `SessionMemory` | WorkState、EvidenceJournal、待办任务、当前工作、下一步动作 | 当前任务 |
| 长期记忆 | `LongTermMemory` | 用户偏好、项目约定、稳定事实、决策、流程和经验 | 跨任务持久化 |

### 短期上下文

- 原始消息与摘要共同受 Token 预算控制。
- 单条超大消息先由 `microcompact` 处理。
- 首次摘要使用 Map-Reduce，后续按主题、生命周期、重要性、修订号和证据引用增量维护。
- 生命周期 GC 负责合并、覆盖、过期和裁剪摘要条目。

### 工作记忆

- WorkState 保存目标、计划、步骤状态、用户约束、根因、修改文件和测试状态。
- EvidenceJournal 保存工具证据、修改资源和失败尝试摘要，并按重要性与 Token 预算裁剪。
- Planner、Worker、Reviewer 从同一任务投影读取各自需要的视图。
- 事件携带 agent、step、逻辑序列和 `context_epoch`，用于幂等处理、来源校验和迟到证据拒绝。

### 长期记忆

- SQLite 保存事实主体、作用域、证据、修订关系、生命周期和召回统计，是长期记忆事实源。
- 向量库保存可重建的语义索引；关键词检索可独立工作。
- 任务结束时，`TaskMemorySnapshot` 将脱敏、限长的必要信息写入 `MemoryPromotionQueue`。
- `IsolatedMemoryCurator` 只读取本次任务快照，不加载工具、MCP、Skill、文件、命令、网络工具、历史记忆或子 Agent。
- Curator 输出 `SAVE`、`CONFIRM` 或 `SKIP`；`CONFIRM` 候选通过 `/memory pending`、`/memory confirm <id>` 和 `/memory reject <id>` 处理。
- 项目与仓库事实按 `scope_type/scope_key` 隔离；全局事实和用户偏好可以跨项目召回。
- 只有真正注入模型上下文的条目才增加 `recallCount` 并更新 `lastRecalledAt`；这两个字段只用于观测，不参与续期、刷新新鲜度或使用次数提权。
- 初始 TTL 固定；用户确认、同值重复显式保存等强验证信号累计 `validatedUseCount`，并按使用档位延长 TTL；到期条目进入软归档。

相邻规则系统不属于记忆层：

- `RuleContext`：加载 `DEVCLI.md` 和 `/rule add` 的强约束并每轮注入；`/rule list` 和 `/rule remove` 负责管理。待分类的 pinned facts 单独展示，不进入规则注入。
- RAG 检索默认把 keyword / semantic / graph、RRF、rerank、最终选择和降级状态写入本机 JSONL 审计记录，不保存代码正文。普通 CLI 会话归档默认关闭；启用后 ReAct 保存脱敏模型消息，Plan / Team 保存顶层输入输出，不保存图片正文与 reasoning，并按保留期限自动清理。

保存长期事实：

```text
/save 这个项目使用 Java 17
```

保存强约束：

```text
/save --pin 默认用简体中文回答
```

长期记忆写入：

- 用户明确执行 `/save` 或调用 `save_memory` 时，低敏稳定事实按显式写入协议保存；如果内容仍然包含明显临时或低复用信号，策略返回确认态。
- 个人偏好、项目约定、常用路径和长期身份属性通过 `reason_code` 记录写入原因。
- 普通用户消息通过任务晋升协议筛选；独立 Curator 只读取当前任务的脱敏快照。
- 当信息涉及 token、密码、手机号、地址等敏感内容时，默认要求确认或跳过。
- “今天临时这样做”“这次先用某个文件名”等低复用信息只留在当前任务的 `SessionMemory`。
- Curator 判断不确定时写入 `AWAITING_CONFIRMATION`，使用 `/memory pending`、`/memory confirm <id>` 或 `/memory reject <id>` 非阻塞处理。
- 命中主题键（如 JSON 库选型）的新事实写入时，同主题当前事实进入软失效并建立修订关系；没有主题键的事实按独立条目保存。

## RAG

初始化代码索引：

```text
/index
```

检索代码：

```text
/search 订单创建流程在哪里
```

查看代码关系：

```text
/graph OrderService
```

`search_code` 支持以下模式：

- `auto`
- `general`
- `call_chain`
- `definition`
- `error_trace`
- `config`

调用链场景可设置 `graph_depth`，范围 `0-3`。

RAG 索引内容：

- Java 类、方法、字段、注解、import 和包名。
- 方法体文本和关键上下文片段。
- 调用关系、实现关系、继承关系和依赖关系。
- 文件路径、起止行号、chunk 名称、语义向量、`IndexEpoch（索引版本）`、`SymbolVersion（符号版本）` 和 `ClasspathEpoch（类路径版本）`。

索引阶段会按文件批量生成 chunk embedding；批量请求失败或返回数量异常时，自动逐条降级处理并跳过单个失败 chunk。

`search_code` 的 keyword 通道保持 SQLite 索引实现，继续参与 RRF 融合和失效事实管理；`grep_code` 是独立的实时精确检索工具，不替代 `search_code`。

RAG 检索流程：

1. 根据 query 选择 `auto/general/call_chain/definition/error_trace/config` 模式。
2. 语义向量召回候选代码块。
3. 关键词和路径信号补充召回。
4. 需要调用链时扩展代码关系图谱。
5. 使用 RRF（倒数排名融合）合并多路结果，并叠加 symbol-aware boost（符号感知加权）。
6. 默认调用 CrossEncoderReranker（交叉编码器重排）做二阶段排序；服务不可用时保留 RRF 结果。

如果 embedding 服务不可用，DevCLI 会把语义召回降级为空、保留关键词和结构化检索路径继续融合，
不让整条检索失败；并在 `search_code` 结果开头显式标注"语义检索服务不可用，本次已降级"，
不把降级结果伪装成完整 RAG。

## MCP

MCP server 启动后会动态刷新工具和 resources：

- `stdio` server 通过本地命令启动；Windows 会按 `PATH` / `PATHEXT` 解析 `npx.cmd` 等命令包装器。
- `streamable_http` server 通过远程 HTTP 地址连接。
- server 启动默认不阻塞首屏超过配置的等待时间；超时 server 会保持 `STARTING` 并在后台继续初始化。
- MCP 工具快照按 server 记录工具数量、schema 指纹和生命周期版本；server 启动成功或 tools/list_changed 刷新会推进生命周期版本。
- MCP 连接事件在进程内记录 STARTING / READY / ERROR / DISABLED / RECONNECTING / TOOLS_CHANGED，便于 CLI 和 Runtime 后续消费。
- MCP 工具发现缓存会保留 server、生命周期版本、工具数量、工具名、schema 指纹和发现时间；server 禁用后仍保留上一轮发现元数据。
- MCP server 启动失败后会后台自动重连，默认最多 3 次；可用 `DEVCLI_MCP_RECONNECT_MAX_ATTEMPTS`、`DEVCLI_MCP_RECONNECT_INITIAL_DELAY_MILLIS`、`DEVCLI_MCP_RECONNECT_MAX_DELAY_MILLIS` 调整。
- MCP `tools/call` 会自动携带 `_meta.progressToken`；server 返回同 token 的 `notifications/progress` 时，DevCLI 会把最近进度摘要追加到工具结果。
- MCP 工具输出被截断或落盘预览时会在返回给模型的文本中标记折叠分类，便于后续工具搜索和错误引导识别结果形态。
- `/mcp` 可以查看状态，`/mcp logs <name>` 可以查看 stderr，`/mcp restart <name>` 可以重启指定 server。

MCP 安全边界：

- 动态工具同样进入 JSON Schema 参数校验。
- 敏感工具进入 HITL 审批。
- 策略层拒绝优先级高于用户批准。
- MCP resource mention 展开前会经过资源缓存和读取工具。

## Runtime API

Runtime API 适合把 DevCLI 接入本地脚本、编辑器插件或自动化系统。核心端点包括：

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/v1/threads` | `POST` | 创建 thread |
| `/v1/threads/{id}/turns` | `POST` | 提交一轮 Agent 输入，异步执行 |
| `/v1/threads/{id}/events` | `GET` | 以 SSE 格式回放事件 |
| `/v1/threads/{id}/branches` | `GET/POST` | 列出或创建持久事件分支 |
| `/v1/threads/{id}/branches/{branchId}/activate` | `POST` | 切换活动分支 |

事件类型：

- `turn.started`
- `reasoning.delta`
- `message.delta`
- `tool.calls`
- `tool.results`
- `turn.completed`
- `turn.failed`
- `turn.rejected`
- `thread.checkpoint.created`
- `thread.checkpoint.failed`

模型流、工具调用、工具结果和 turn 生命周期统一使用强类型 `RunEvent`。CLI Renderer 通过适配器消费同一事件流，Runtime API 将事件投影为带 `schema_version: 2` 的稳定 JSON 后写入 SSE；远程客户端不需要解析终端文本。工具参数在协议中保持 JSON 对象，工具结果包含结构化状态、错误码、重试标记、耗时、展示意图和图片数量，不包含图片正文。

默认只绑定本机地址 `127.0.0.1`，并要求 API Key。HTTP 请求线程与 Agent turn 执行线程隔离，turn 队列满时返回 `429 runtime_busy`。

同一 thread 的多个 turn 由 `RuntimeSessionTurnRunner` 复用会话运行时；进程恢复时从 SQLite 读取最新压缩检查点，并完整重放检查点之后的已完成 turn。没有检查点时重放全部已完成 turn。检查点保存压缩消息窗口与恢复元数据；事件日志仍是事实来源，失败或被拒的 turn 不进入模型上下文。

## Hooks

DevCLI 支持 `agent`、`turn`、`message` 和 `tool execution` 四层生命周期 Hook：

- `agent_start` / `agent_end`
- `turn_start` / `turn_end`
- `message_start` / `message_end`
- `tool_execution_start` / `tool_execution_end`

Hook 配置按 id 合并，项目级覆盖用户级：

1. `~/.devcli/hooks.json`
2. `<project>/.devcli/hooks.json`

也可以通过 `DEVCLI_HOOKS_FILE` 或 `-Ddevcli.hooks.file` 指定单一配置文件。配置使用 `schemaVersion: 1`，模板位于 `Config/hooks.example.json`。

Hook 不直接执行任意 shell 或 HTTP 请求，而是调用已注册工具，因此继续经过 ToolEffect、能力范围、参数校验、HITL、策略和审计管线。READ_ONLY / LOCAL_CONTEXT Hook 会被强制收窄到只读能力；其他副作用必须同时配置 `allowSideEffects: true`、启用 HITL，并且目标工具具有逐次审批策略，否则拒绝执行。

Hook 在对应生命周期点同步、按配置顺序执行，确保事件顺序可复现；工具自身的超时和取消机制继续生效。`failureMode: warn` 只记录警告，不改变 Agent 终态；`failureMode: required` 会通过统一 Agent 失败出口终止当前执行。参数字符串支持 `${event}`、`${project}`、`${run_id}`、`${iteration}`、`${tool_name}`、`${tool_call_id}` 和 `${status}` 占位符。

## Safety

DevCLI 是本地 Agent CLI，不提供容器或虚拟机级沙箱。安全机制包括：

- HITL（人工审批）
- PathGuard（路径围栏）
- CommandGuard（危险命令快速拒绝）
- AuditLog（审计日志）
- Side-Git snapshot（回滚快照）

开启 HITL：

```text
/hitl on
```

查看审计：

```text
/audit 20
```

安全执行顺序：

```text
LLM tool call
→ JSON Schema 参数校验
→ HitlToolRegistry
→ ToolRegistry
→ PathGuard / CommandGuard
→ AuditLog
→ 实际工具执行
```

这意味着：

- 参数不合法时不会进入审批，更不会执行。
- 用户不能批准策略层已经拒绝的操作。
- 文件写入和命令执行会留下审计记录。
- Side-Git snapshot 可用于回滚最近 turn 的文件改动，并按保留上限自动裁剪旧快照；裁剪后由 JGit `autoGC` 按原生阈值决定是否后台维护。

## Renderer And Interaction

默认 inline renderer 面向日常终端使用：

- 启动首屏展示模型、MCP、Skill、ReAct 状态和 getting-started tips。
- 输入行支持 slash 命令、`@path`、`@image:`、敏感词和危险 shell 片段高亮；`/help` 直接显示完整命令列表。
- ReAct 执行期间可继续输入后续任务：普通文本进入容量为 8 的会话内 FIFO 队列，`/now <任务>` 取消当前轮次并优先执行新任务，空闲时直接执行；`/cancel` 只取消当前轮次。取消后最多等待执行线程退出 5 秒，未退出时停止接收新任务，避免两个轮次并发修改会话状态；任务结束时未提交的输入会保留为下一次编辑草稿。Plan、Multi-Agent 或启用 HITL 时继续保持单一终端输入所有权。
- 底部状态栏显示当前 phase、模型、上下文百分比、token、cost、elapsed、cwd。终端误判为 dumb 时可用 `DEVCLI_TERMINAL_FORCE_ANSI=true` 强制启用。
- 重定向输入默认使用 UTF-8，旧式 Windows 控制台可通过 `DEVCLI_TERMINAL_ENCODING=GBK` 覆盖。
- plain 与 inline 审批都复用主 LineReader，避免审批输入与主提示符争抢标准输入。
- LLM reasoning 会进入 live thinking 区，正文输出前会收敛为完整引用块。
- 工具调用以紧凑块展示，文件写入会展示 diff。

plain renderer 适合 CI、日志或不支持 ANSI 的终端。Lanterna 不再有生产启动入口；旧配置会输出迁移提示并使用 inline。

## Benchmark Evaluation

项目提供 RAG、Agent、Memory 和 Context Compression / Long Context 四类量化评测。正式结果只接受公开数据集原始任务、固定版本、SHA-256、官方 harness/evaluator 和完整原始报告。公开集合接入状态与复现边界见 `docs/benchmark-evaluation.md`；历史自建测试不再作为正式评测入口。

评测原始报告默认写入 `target/benchmark-reports/` 和 `target/agent-benchmark/`。聚合器会生成可提交的 JSON、CSV 与数据清单到 `Data/processed/` 和 `Data/manifest/`。完整方法、命令、基线结果和适用边界见 `docs/benchmark-evaluation.md`。

历史 benchmark 数字已从 README 移除。旧测试的方法、结果和废弃原因统一记录在 `docs/benchmark-evaluation.md` 的“历史自建评测归档”章节；新会话不得将其当作当前结果。

## Tests

常规快速回归：

```bash
mvn test -Pquick
```

针对性测试：

```bash
mvn test -Dtest=AgentOrchestratorTest -DskipTests=false
```

全量测试：

```bash
mvn test -DskipTests=false
```

默认 `mvn clean package` 会跳过测试，优先产出可手工验收的 jar。

## Project Layout

```text
src/main/java/com/devcli/
├── agent/       Agent, PlanExecuteAgent, SubAgent, AgentOrchestrator, PlanCoordinator, ReviewCoordinator, CheckpointCoordinator, StepExecutionCoordinator, OrchestrationRunState, OrchestrationNarrative
├── cli/         Main, CliCommandParser
├── context/     ContextProfile, ContextMode, TokenUsageFormatter
├── memory/      MemoryManager, SessionMemory, LongTermMemory, CompactionSummaryCache, RuleContext
├── mcp/         McpServerManager, McpClient, resources, transport
├── plan/        Planner, ExecutionPlan, Task
├── policy/      PathGuard, CommandGuard, AuditLog
├── prompt/      PromptAssembler, PromptContext
├── rag/         CodeIndex, CodeRetriever, VectorStore, CodeChunker
├── render/      Renderer, InlineRenderer, PlainRenderer
├── snapshot/    SideGitManager, SnapshotService
├── tool/        ToolRegistry
└── web/         SearchProvider, WebFetcher, HtmlExtractor
```
