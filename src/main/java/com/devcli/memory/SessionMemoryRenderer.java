package com.devcli.memory;

import java.util.*;
import static com.devcli.memory.SessionMemory.*;
import static com.devcli.memory.SessionEvidenceText.*;

/** 由不可变快照渲染消息投影，不修改会话状态。 */
final class SessionMemoryRenderer {
    private final SessionSnapshot snapshot;
    private final Map<String, String> taskState;
    private final List<ToolEvidence> recentToolResults;
    private final List<RagEvidence> ragEvidenceMemory;
    private final Set<String> runningSteps;
    private final String ledgerRestore;

    SessionMemoryRenderer(SessionSnapshot snapshot, List<ToolEvidence> evidence, List<RagEvidence> rag,
                          Set<String> runningSteps, String ledgerRestore) {
        this.snapshot = snapshot;
        this.taskState = snapshot.workState();
        this.recentToolResults = evidence;
        this.ragEvidenceMemory = rag;
        this.runningSteps = runningSteps;
        this.ledgerRestore = ledgerRestore;
    }

    private int retentionPriority(ToolEvidence evidence) {
        return evidence.importance + (runningSteps.contains(evidence.stepId) ? 100 : 0);
    }
    /**
     * 渲染为动态上下文的一段 Markdown。空内容返回空串。
     */
    String renderForPrompt() {
        return renderForPrompt(SessionView.FULL, DEFAULT_EVIDENCE_TOKEN_BUDGET);
    }

    /**
     * 按 Agent 角色渲染工作记忆派生视图，避免 Multi-Agent 三角色共享同一份运行态证据。
     */
    String renderForPrompt(SessionView view) {
        return renderForPrompt(view, DEFAULT_EVIDENCE_TOKEN_BUDGET);
    }

    String renderForPrompt(SessionView view, int tokenBudget) {
        SessionView effectiveView = view == null ? SessionView.FULL : view;
        int effectiveBudget = Math.max(256, tokenBudget);
        StringBuilder sb = new StringBuilder();
        // 硬约束不参与裁剪；超出软预算时不再追加普通投影。
        appendProtectedConstraintSection(sb);
        String ledger = snapshot.taskLedger();
        String workPlan = AgentWorkPlan.render(taskState.get(AgentWorkPlan.STATE_KEY));
        if (!workPlan.isEmpty()) sb.append(workPlan).append("\n\n");
        if (!ledger.isEmpty()) {
            appendBudgeted(sb, ledger, effectiveBudget);
        }
        if (!taskState.isEmpty()) {
            StringBuilder section = new StringBuilder("### 当前任务状态\n\n");
            if (!snapshot.taskId().isBlank()) {
                section.append("- task_id: ").append(snapshot.taskId())
                        .append(snapshot.taskEnded() ? " (已结束)" : " (进行中)").append('\n');
            }
            for (Map.Entry<String, String> e : taskState.entrySet()) {
                if (AgentWorkPlan.STATE_KEY.equals(e.getKey())) continue;
                section.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
            appendBudgeted(sb, section.toString(), effectiveBudget);
        }
        if (!snapshot.modifiedFiles().isEmpty()) {
            StringBuilder section = new StringBuilder("### 已修改文件集合\n\n");
            for (String file : snapshot.modifiedFiles()) section.append("- `").append(file).append("`\n");
            appendBudgeted(sb, section.toString(), effectiveBudget);
        }
        List<ToolEvidence> rankedToolEvidence = new ArrayList<>();
        if (shouldRenderToolEvidence(effectiveView)) {
            rankedToolEvidence.addAll(recentToolResults);
            rankedToolEvidence.sort((left, right) -> {
                int byImportance = Integer.compare(retentionPriority(right), retentionPriority(left));
                return byImportance != 0 ? byImportance : Long.compare(right.sequence, left.sequence);
            });
            appendToolEvidence(sb, rankedToolEvidence.stream()
                    .filter(evidence -> retentionPriority(evidence) >= 70)
                    .toList(), "### 关键工具证据（精确实体来源）", effectiveBudget);
        }
        if (shouldRenderToolEvidence(effectiveView) && !snapshot.attemptDigests().isEmpty()) {
            StringBuilder section = new StringBuilder("### 已尝试但失败的方案\n\n");
            for (AttemptDigestSnapshot attempt : snapshot.attemptDigests()) {
                section.append("- ").append(truncate(attempt.digest(), 700).replace("\n", "; ")).append('\n');
            }
            appendBudgeted(sb, section.toString(), effectiveBudget);
        }
        if (shouldRenderVolatileFacts(effectiveView) && !snapshot.keyEvents().isEmpty()) {
            StringBuilder section = new StringBuilder("### 本会话已发生的关键事件（避免重复执行）\n\n");
            List<KeyEventSnapshot> ranked = new ArrayList<>(snapshot.keyEvents());
            ranked.sort((left, right) -> {
                int byImportance = Integer.compare(right.importance(), left.importance());
                return byImportance != 0 ? byImportance : Long.compare(right.sequence(), left.sequence());
            });
            for (KeyEventSnapshot fact : ranked) {
                section.append("- ").append(fact.description());
                appendOrigin(section, fact.agentId(), fact.stepId());
                section.append('\n');
            }
            appendBudgeted(sb, section.toString(), effectiveBudget);
        }
        if (shouldRenderToolEvidence(effectiveView) && !ragEvidenceMemory.isEmpty()) {
            StringBuilder section = new StringBuilder("### RAG 证据记忆（绑定 SymbolVersion）\n\n");
            List<RagEvidence> reversed = new ArrayList<>(ragEvidenceMemory);
            Collections.reverse(reversed);
            for (RagEvidence evidence : reversed) {
                section.append("- [").append(evidence.chunkType()).append(':').append(evidence.symbolName()).append("] ")
                        .append(evidence.filePath())
                        .append(" | symbolVersion=").append(evidence.symbolVersion())
                        .append(" | indexEpoch=").append(evidence.indexEpoch())
                        .append(" | classpathEpoch=").append(evidence.classpathEpoch());
                if (!evidence.query().isBlank()) {
                    section.append(" | query=").append(evidence.query());
                }
                section.append('\n');
            }
            appendBudgeted(sb, section.toString(), effectiveBudget);
        }
        if (!rankedToolEvidence.isEmpty()) {
            appendToolEvidence(sb, rankedToolEvidence.stream()
                    .filter(evidence -> retentionPriority(evidence) < 70)
                    .toList(), "### 普通工具证据（精确实体来源）", effectiveBudget);
        }
        return sb.toString().trim();
    }

    private static void appendToolEvidence(StringBuilder target, List<ToolEvidence> evidence,
                                           String heading, int tokenBudget) {
        if (evidence.isEmpty() || !appendBudgeted(target, heading, tokenBudget)) return;
        for (ToolEvidence item : evidence) {
            StringBuilder rendered = new StringBuilder("- **").append(item.toolName).append("**")
                    .append(" [").append(item.kind).append('/').append(item.importance).append("]");
            appendOrigin(rendered, item.agentId, item.stepId);
            if (item.originSequence > 0) {
                rendered.append(" [origin=").append(item.originSequence).append(']');
            }
            if (item.contextEpoch > 0) {
                rendered.append(" [context_epoch=").append(item.contextEpoch).append(']');
            }
            if (!item.argsJson.isBlank()) {
                rendered.append(" args: `").append(truncate(item.argsJson, 120)).append('`');
            }
            rendered.append('\n');
            appendRecoveryReference(rendered, item);
            rendered.append("  ```\n  ").append(truncate(item.result, TOOL_RESULT_RENDER_CHARS)
                    .replace("\n", "\n  ")).append("\n  ```\n");
            if (!appendBudgeted(target, rendered.toString(), tokenBudget)) break;
        }
    }

    static void appendRecoveryReference(StringBuilder target, ToolEvidence evidence) {
        if (evidence.artifact != null && !evidence.artifact.artifactRef().isBlank()) {
            target.append("  read_tool_result result_ref=")
                    .append(evidence.artifact.artifactRef()).append(" offset=0\n");
        }
    }

    private static boolean appendBudgeted(StringBuilder target, String section, int tokenBudget) {
        if (section == null || section.isBlank()) return true;
        int used = MemoryEntry.estimateTokens(target.toString());
        int remaining = tokenBudget - used;
        if (remaining <= 0) return false;
        String normalized = section.trim();
        String addition = target.isEmpty() ? normalized : "\n\n" + normalized;
        if (MemoryEntry.estimateTokens(addition) <= remaining) {
            target.append(addition);
            return true;
        }
        String prefix = target.isEmpty() ? "" : "\n\n";
        String marker = "\n[本区其余内容已按 Token 预算压缩]";
        int contentBudget = remaining
                - MemoryEntry.estimateTokens(prefix)
                - MemoryEntry.estimateTokens(marker);
        String fitted = fitWithinTokens(normalized, contentBudget);
        if (fitted.isBlank()) return false;
        target.append(prefix).append(fitted).append(marker);
        return false;
    }

    private static String fitWithinTokens(String value, int tokenBudget) {
        if (value == null || value.isBlank() || tokenBudget <= 0) return "";
        if (MemoryEntry.estimateTokens(value) <= tokenBudget) return value;
        int low = 0;
        int high = value.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (MemoryEntry.estimateTokens(value.substring(0, mid)) <= tokenBudget) low = mid;
            else high = mid - 1;
        }
        return value.substring(0, low).trim();
    }

    private static void appendOrigin(StringBuilder target, String agentId, String stepId) {
        if ((agentId == null || agentId.isBlank()) && (stepId == null || stepId.isBlank())) return;
        target.append(" [来自 ");
        if (agentId != null && !agentId.isBlank()) target.append(agentId);
        if (stepId != null && !stepId.isBlank()) {
            if (agentId != null && !agentId.isBlank()) target.append('/');
            target.append(stepId);
        }
        target.append(']');
    }

    /**
     * 压缩后恢复 messages 的结构化短上下文。
     *
     * <p>这里不复用完整 {@link #renderForPrompt()}，避免把大段工具输出再次写回
     * conversationHistory。恢复段只保留可定位实体：文件路径、未完成子任务、短工具引用和 RAG epoch。
     */
    String renderForPostCompactRestore() {
        return renderForPostCompactRestore(SessionView.FULL);
    }

    String renderForPostCompactRestore(SessionView view) {
        SessionView effectiveView = view == null ? SessionView.FULL : view;
        StringBuilder sb = new StringBuilder();
        String workPlan = AgentWorkPlan.render(taskState.get(AgentWorkPlan.STATE_KEY));
        if (!workPlan.isEmpty()) sb.append(workPlan).append("\n\n");
        if (shouldRenderToolEvidence(effectiveView)) {
            appendRecentFileSection(sb);
        }
        appendProtectedConstraintSection(sb);
        appendOpenTaskSection(sb);
        if (shouldRenderToolEvidence(effectiveView)) {
            appendKeyToolReferenceSection(sb);
            appendRagEpochSection(sb);
        }
        return sb.toString().trim();
    }

    private void appendRecentFileSection(StringBuilder sb) {
        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        for (ToolEvidence ev : recentToolResults) {
            if (!"read_file".equals(ev.toolName) && !"write_file".equals(ev.toolName)
                    && !"edit_file".equals(ev.toolName)) {
                continue;
            }
            String path = extractPath(ev.argsJson);
            if (path.isBlank()) {
                continue;
            }
            files.remove(path);
            files.put(path, ev.toolName);
        }
        if (files.isEmpty()) {
            return;
        }
        appendSectionBreak(sb);
        sb.append("### 最近读写文件\n\n");
        List<Map.Entry<String, String>> entries = new ArrayList<>(files.entrySet());
        Collections.reverse(entries);
        for (Map.Entry<String, String> entry : entries) {
            sb.append("- ").append(entry.getValue()).append(": `").append(entry.getKey()).append("`\n");
        }
    }

    private void appendOpenTaskSection(StringBuilder sb) {
        String ledger = ledgerRestore;
        if (taskState.isEmpty() && ledger.isBlank()) {
            return;
        }
        appendSectionBreak(sb);
        sb.append("### 未完成子任务状态\n\n");
        if (!ledger.isBlank()) {
            sb.append(ledger).append('\n');
        }
        for (Map.Entry<String, String> entry : taskState.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
    }

    private void appendKeyToolReferenceSection(StringBuilder sb) {
        if (recentToolResults.isEmpty()) {
            return;
        }
        appendSectionBreak(sb);
        sb.append("### 关键工具结果引用\n\n");
        List<ToolEvidence> reversed = new ArrayList<>(recentToolResults);
        Collections.reverse(reversed);
        LinkedHashSet<String> renderedMicrocompactReferences = new LinkedHashSet<>();
        int count = 0;
        for (ToolEvidence ev : reversed) {
            if (count >= 5) {
                break;
            }
            String microcompactKey = microcompactReferenceKey(ev.result);
            if (!microcompactKey.isBlank() && !renderedMicrocompactReferences.add(microcompactKey)) {
                continue;
            }
            sb.append("- **").append(ev.toolName).append("**");
            if (!ev.argsJson.isBlank()) {
                sb.append(" args: `").append(truncate(ev.argsJson, 120)).append('`');
            }
            String result = renderToolResultReference(ev.result);
            if (!result.isBlank()) {
                sb.append(" -> ").append(result);
            }
            sb.append('\n');
            appendRecoveryReference(sb, ev);
            count++;
        }
    }

    private static String renderToolResultReference(String result) {
        if (result == null || result.isBlank()) {
            return "";
        }
        if (!MicrocompactBoundary.isBoundary(result)) {
            return truncate(result, 240).replaceAll("\\s+", " ").trim();
        }
        String toolCallId = MicrocompactBoundary.toolCallId(result);
        String originalChars = MicrocompactBoundary.originalChars(result);
        String storedPath = MicrocompactBoundary.storedPath(result);
        StringBuilder sb = new StringBuilder("microcompact tool_result");
        if (!toolCallId.isBlank()) {
            sb.append(" toolCallId=").append(toolCallId);
        }
        if (!originalChars.isBlank()) {
            sb.append(" originalChars=").append(originalChars);
        }
        if (!storedPath.isBlank()) {
            sb.append(" storedPath=").append(storedPath);
        }
        return sb.toString();
    }

    private static String microcompactReferenceKey(String result) {
        if (!MicrocompactBoundary.isBoundary(result)) {
            return "";
        }
        String storedPath = MicrocompactBoundary.storedPath(result);
        if (!storedPath.isBlank()) {
            return "storedPath=" + storedPath;
        }
        String toolCallId = MicrocompactBoundary.toolCallId(result);
        return toolCallId.isBlank() ? "" : "toolCallId=" + toolCallId;
    }

    private void appendRagEpochSection(StringBuilder sb) {
        if (ragEvidenceMemory.isEmpty()) {
            return;
        }
        appendSectionBreak(sb);
        sb.append("### RAG 证据 epoch\n\n");
        List<RagEvidence> reversed = new ArrayList<>(ragEvidenceMemory);
        Collections.reverse(reversed);
        for (RagEvidence evidence : reversed) {
            sb.append("- [").append(evidence.chunkType()).append(':').append(evidence.symbolName()).append("] ")
                    .append(evidence.filePath())
                    .append(" | symbolVersion=").append(evidence.symbolVersion())
                    .append(" | indexEpoch=").append(evidence.indexEpoch())
                    .append(" | classpathEpoch=").append(evidence.classpathEpoch());
            if (!evidence.query().isBlank()) {
                sb.append(" | query=").append(evidence.query());
            }
            sb.append('\n');
        }
    }

    private static void appendSectionBreak(StringBuilder sb) {
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
    }

    private static boolean shouldRenderVolatileFacts(SessionView view) {
        return view == SessionView.FULL || view == SessionView.PLANNER || view == SessionView.WORKER;
    }

    private void appendProtectedConstraintSection(StringBuilder sb) {
        if (snapshot.protectedConstraints().isEmpty()) return;
        appendSectionBreak(sb);
        sb.append("### 用户硬约束\n\n");
        for (String constraint : snapshot.protectedConstraints()) {
            sb.append("- ").append(constraint).append('\n');
        }
    }

    private static boolean shouldRenderToolEvidence(SessionView view) {
        return view == SessionView.FULL || view == SessionView.WORKER || view == SessionView.REVIEWER;
    }
}
