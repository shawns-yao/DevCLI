# Interview Improvement Designs

## Status

This directory turns repeated interview feedback into concrete follow-up designs.

Each document follows the same structure:

- 暴露的问题
- 当前已有
- 不足
- 怎么修改
- 设计边界
- 验收标准
- 文字解释

## Documents

| No. | Document | Main Gap |
|-----|----------|----------|
| 01 | `01-multi-agent-consistency-design.md` | `Multi-Agent（多智能体）` consistency, stale context, worktree isolation |
| 02 | `02-code-rag-evaluation-fusion-design.md` | `Code RAG（代码检索增强生成）` fusion, RRF, benchmark |
| 03 | `03-javaparser-symbol-resolution-design.md` | `JavaParser（Java 语法树解析器）` symbol resolution and classpath |
| 04 | `04-memory-context-governance-design.md` | `Memory（记忆）` and `Context（上下文）` invalidation |
| 05 | `05-react-loop-tool-policy-design.md` | `ReAct（推理-行动）` loop control and tool policy |
| 06 | `06-reviewer-independent-retrieval-design.md` | `Reviewer（审查者）` independent retrieval profile |
| 07 | `07-mcp-governance-design.md` | `MCP（模型上下文协议）` governance, errors, resources, permissions |
| 08 | `08-sandbox-execution-security-design.md` | `Sandbox（沙箱）` and command execution security |
| 09 | `09-streaming-event-ui-design.md` | Streaming, TUI, Runtime API event model |
| 10 | `10-production-scale-durable-runtime-design.md` | production-scale indexing and durable Agent runtime |

## Priority

Recommended build order:

1. `ContextInvalidation（上下文失效）` + `WorkerContextManifest（Worker 上下文清单）`.
2. `ShadowIndex（影子索引）` + `IndexEpoch（索引版本）`.
3. `CodeRagBenchmark（代码 RAG 基准评估）` + `RRF（倒数排名融合）`.
4. `Worktree（工作树）` + `PatchSet（补丁集）`.
5. `ToolUsePolicy（工具调用策略）` + `ErrorFingerprint（错误指纹）`.
6. `ReviewerRetrievalProfile（审查者检索画像）`.
7. `SymbolSolver（符号求解器）` + `ClasspathEpoch（类路径版本）`.
8. `Sandbox（沙箱）` command profile and network policy.

