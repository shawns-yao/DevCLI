# DevCLI

DevCLI 是一个面向 Java 后端开发者的终端 Agent CLI。它可以在命令行中通过自然语言驱动代码阅读、生成、调试、重构、命令执行和仓库检索。

核心能力：

- ReAct（推理-行动）主循环：支持多轮工具调用、结果回灌和流式输出。
- Plan-and-Execute（规划执行）：通过 `/plan` 生成任务计划并按依赖执行。
- Multi-Agent（多智能体）：通过 `/team` 使用 Planner / Worker / Reviewer 协作执行复杂任务。
- RAG（检索增强生成）：基于 JavaParser、SQLite 向量存储、关键词召回和代码关系图谱检索仓库实现。
- Memory（记忆）：支持当前会话工作记忆、长期记忆和强约束记忆。
- MCP（Model Context Protocol）：可接入外部 MCP server，动态注册工具和 resources。
- HITL（Human-in-the-Loop）：危险操作可开启人工审批、路径限制和审计日志。
- Browser / Web：支持 web_search、web_fetch，以及 Chrome DevTools MCP 浏览器操作。
- Image Input（图片输入）：支持本地图片和剪贴板图片作为模型输入。

## Requirements

- Java 17+
- Maven 3.8+
- Node.js / npm，只有使用默认 Chrome DevTools MCP 时需要
- 至少一个 LLM API Key：
  - `GLM_API_KEY`
  - `DEEPSEEK_API_KEY`
  - `STEP_API_KEY`
  - `KIMI_API_KEY` 或 `MOONSHOT_API_KEY`

Embedding（向量检索）默认使用 Ollama：

- Ollama 本地服务：`http://localhost:11434`
- 默认模型：`nomic-embed-text:latest`

如果不使用本地 Ollama，可以在 `.env` 中配置远程 embedding provider。

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

编辑 `.env`，至少填写一个模型 API Key：

```bash
GLM_API_KEY=your_api_key_here
# 或
DEEPSEEK_API_KEY=your_api_key_here
# 或
STEP_API_KEY=your_api_key_here
# 或
KIMI_API_KEY=your_api_key_here
```

如果使用默认本地 embedding：

```bash
ollama pull nomic-embed-text:latest
ollama serve
```

构建 jar：

```bash
mvn clean package
```

当前 Maven artifact 仍为 `paicli-1.0-SNAPSHOT.jar`，运行命令：

```bash
java -jar target/paicli-1.0-SNAPSHOT.jar
```

也可以直接用 Maven 启动：

```bash
mvn clean compile exec:java -Dexec.mainClass="com.paicli.cli.Main"
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
```

## Configuration

### LLM

DevCLI 会从 `.env` 或系统环境变量读取模型配置。

常用配置：

```bash
GLM_API_KEY=your_api_key_here
GLM_MODEL=glm-5.1

DEEPSEEK_API_KEY=your_api_key_here
DEEPSEEK_MODEL=deepseek-v4-flash

STEP_API_KEY=your_api_key_here
STEP_MODEL=step-3.5-flash

KIMI_API_KEY=your_api_key_here
KIMI_MODEL=kimi-k2.6
```

运行时可用 `/model` 切换已配置的 provider。

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

- 用户级：`~/.paicli/mcp.json`
- 项目级：`.paicli/mcp.json`

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
PAICLI_RENDERER=inline
```

可选值：

- `inline`：默认，底部状态栏、行内工具块、行内 diff。
- `lanterna`：三栏全屏 TUI。
- `plain`：纯文本输出。

如果终端不支持底部状态栏：

```bash
PAICLI_NO_STATUSBAR=true
```

### Runtime API

DevCLI 可以以本地 Runtime API 方式启动：

```bash
PAICLI_RUNTIME_API_KEY=your_local_api_key \
java -jar target/paicli-1.0-SNAPSHOT.jar serve --http --port 8080
```

请求头：

```text
Authorization: Bearer your_local_api_key
```

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

进入 Plan-and-Execute：

```text
/plan 重构订单模块，把校验逻辑从 Controller 下沉到 Service，并补充测试
```

进入 Multi-Agent：

```text
/team 检查认证模块的安全问题，修复高风险项并补充测试
```

Multi-Agent：Planner 拆 DAG 并提取 `acceptance_criteria`，Worker 做实现，Reviewer 做硬检查后的质量审查。验收点会前置注入 Worker，并由 Reviewer 用 `criteria_results` 逐条验证；critical/high 失败或缺少覆盖强制不通过。三角色注入 role-scoped WorkingMemory：Planner 看任务状态 + 关键事件，Worker 看完整上下文，Reviewer 看任务状态 + 工具证据。Reviewer 前会自动执行 Java 编译硬检查，失败直接打回；Reviewer JSON 采用 `functional_correctness` / `integration_completeness` / `code_quality` 三层评分，未达阈值强制不通过。

## Commands

常用命令：

| Command | Description |
|---------|-------------|
| `/help` | 查看帮助 |
| `/model` | 查看或切换模型 |
| `/plan` | 使用 Plan-and-Execute 执行下一条任务 |
| `/team` | 使用 Multi-Agent 协作执行任务 |
| `/index` | 为当前仓库建立 RAG 索引 |
| `/search <query>` | 检索代码库 |
| `/graph <class>` | 查看代码关系图谱 |
| `/memory` | 查看记忆状态 |
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
| `/browser connect` | 连接可复用 Chrome 会话 |
| `/clear` | 清空当前对话 |
| `/exit` | 退出 |

## Built-in Tools

内置工具：

| Tool | Description |
|------|-------------|
| `read_file` | 读取文件 |
| `write_file` | 写入文件 |
| `list_dir` | 列出目录 |
| `execute_command` | 执行短时 shell 命令 |
| `create_project` | 创建基础项目结构 |
| `search_code` | 检索代码库 |
| `web_search` | 搜索互联网 |
| `web_fetch` | 抓取已知 URL 并提取正文 |
| `revert_turn` | 回滚最近 turn 的改动 |
| `mcp__{server}__{tool}` | MCP server 动态工具 |
| `mcp__{server}__read_resource` | 读取 MCP resource |

同一轮模型返回多个工具调用时，DevCLI 会并行执行可并行的工具，并按原始顺序把结果回灌给模型。

工具调用可靠性：工具定义以 JSON Schema 约束参数类型、必填项、枚举值和未知字段；`ToolRegistry` 在真实执行前通过 `json-schema-validator` + 本地兜底校验内置工具与 MCP 工具参数，非法 JSON、类型错误、空必填、非法枚举、pattern/minimum 等 schema 约束失败会以 `工具参数校验失败` 回传给模型修正。危险工具仍走 HITL 审批、策略拦截和 AuditLog；工具错误会回灌给模型继续纠偏，最终答复必须基于工具证据。

## Memory

DevCLI 的上下文分为四层：

- `ConversationHistory（对话历史）`：真实 LLM messages，由压缩器治理窗口。
- `WorkingMemory（工作记忆）`：当前会话工具证据、任务状态和临时事实，不跨会话持久化。
- `LongTermMemory（长期记忆）`：跨会话稳定事实，SQLite 持久化，支持检索注入；写入前经过 `LongTermMemoryPolicy` 打分，显式低敏偏好/项目事实可自动保存，敏感或中等置信信息要求确认，临时闲聊和低复用信息跳过。
- `StickyMemory（强约束记忆）`：通过 `/save --pin` 保存，每轮全量注入 system prompt。

保存长期事实：

```text
/save 这个项目使用 Java 17
```

保存强约束：

```text
/save --pin 默认用简体中文回答
```

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
src/main/java/com/paicli/
├── agent/       Agent, PlanExecuteAgent, SubAgent, AgentOrchestrator
├── cli/         Main, CliCommandParser
├── context/     ContextProfile, ContextMode, TokenUsageFormatter
├── memory/      MemoryManager, WorkingMemory, LongTermMemory, StickyMemory
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

## Notes

- 不要提交 `.env`、真实 API Key、`target/` 产物。
- MCP OAuth、sampling 和 server 自动重启仍是后续增强方向。
- Runtime API 默认只建议监听本机地址，并强制配置本地 API Key。
