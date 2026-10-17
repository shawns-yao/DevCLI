package com.devcli.memory;

import com.devcli.rag.RagEvidencePayload;
import com.devcli.rag.RagEvidenceSideChannel;
import com.devcli.tool.ToolSideChannel;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.devcli.memory.SessionMemory.*;

/** 解析已有 RAG 工具证据；状态写入仍交给 SessionMemory。 */
final class SessionRagEvidenceParser {
    private final Consumer<RagEvidence> evidenceSink;
    private final Consumer<String> factSink;
    private final Consumer<String> pruneVersion;
    SessionRagEvidenceParser(Consumer<RagEvidence> evidenceSink, Consumer<String> factSink, Consumer<String> pruneVersion) {
        this.evidenceSink = evidenceSink;
        this.factSink = factSink;
        this.pruneVersion = pruneVersion;
    }
    // 避免在 Windows 路径 C:\Users\... 时被盘符冒号截断
    private static final Pattern SEARCH_RESULT_HEADER = Pattern.compile(
            "^\\s*\\d+\\. \\[(\\w+):([^\\]]+)] \\(相似度: ([^)]+)\\) (.+)$");

    private static final Pattern SEARCH_RESULT_EVIDENCE = Pattern.compile(
            "^\\s*evidence: symbolVersion=([^,]+), (?:indexEpoch=([^,]+), )?classpathEpoch=(.+)$");

    private static final Pattern SEARCH_RESULT_NEGATIVE_FACT = Pattern.compile("^\\s*negativeFact: (.+)$");

    /** 从 negativeFact 行提取被失效的旧 symbolVersion，用于即时清理对应的过期 RAG 证据。 */
    private static final Pattern NEGATIVE_FACT_OLD_SYMBOL_VERSION = Pattern.compile("oldSymbolVersion=([^,\\s]+)");

    void record(String toolName, String argsJson, String result,
                                            List<ToolSideChannel> sideChannels) {
        if (!"search_code".equals(toolName) || result == null || result.isBlank()) {
            return;
        }
        boolean typedPayloadPresent = false;
        if (sideChannels != null) {
            for (ToolSideChannel sideChannel : sideChannels) {
                if (sideChannel instanceof RagEvidenceSideChannel ragEvidence) {
                    typedPayloadPresent = true;
                    recordRagEvidencePayload(ragEvidence.payload());
                }
            }
        }
        if (typedPayloadPresent) {
            return;
        }
        RagEvidencePayload.Payload payload = RagEvidencePayload.extract(result);
        if (!payload.evidence().isEmpty() || !payload.negativeFacts().isEmpty()) {
            recordRagEvidencePayload(payload);
            return;
        }
        recordLegacyRagEvidenceIfPresent(argsJson, result);
    }

    private void recordRagEvidencePayload(RagEvidencePayload.Payload payload) {
        if (payload == null) {
            return;
        }
        for (RagEvidencePayload.Evidence evidence : payload.evidence()) {
            evidenceSink.accept(new RagEvidence(
                    evidence.filePath(),
                    evidence.symbolName(),
                    evidence.chunkType(),
                    evidence.symbolVersion(),
                    evidence.classpathEpoch(),
                    evidence.indexEpoch(),
                    evidence.query(),
                    evidence.similarity(),
                    Instant.now()));
        }
        for (RagEvidencePayload.NegativeFact negativeFact : payload.negativeFacts()) {
            String rendered = negativeFact.renderForMemory();
            factSink.accept("NegativeFact（负向事实）: " + rendered);
            pruneVersion.accept(negativeFact.oldSymbolVersion());
        }
    }

    private void recordLegacyRagEvidenceIfPresent(String argsJson, String result) {
        String query = extractQuery(argsJson);
        String[] lines = result.split("\\R");
        PendingRagEvidence pending = null;
        for (String line : lines) {
            Matcher headerMatcher = SEARCH_RESULT_HEADER.matcher(line);
            if (headerMatcher.matches()) {
                pending = new PendingRagEvidence(
                        headerMatcher.group(1).trim(),
                        headerMatcher.group(2).trim(),
                        safeParseDouble(headerMatcher.group(3).trim()),
                        headerMatcher.group(4).trim());
                continue;
            }
            Matcher evidenceMatcher = SEARCH_RESULT_EVIDENCE.matcher(line);
            if (pending != null && evidenceMatcher.matches()) {
                evidenceSink.accept(new RagEvidence(
                        pending.filePath,
                        pending.symbolName,
                        pending.chunkType,
                        evidenceMatcher.group(1).trim(),
                        evidenceMatcher.group(3).trim(),
                        evidenceMatcher.group(2) == null ? "none" : evidenceMatcher.group(2).trim(),
                        query,
                        pending.similarity,
                        Instant.now()));
                pending = null;
                continue;
            }
            Matcher negativeFactMatcher = SEARCH_RESULT_NEGATIVE_FACT.matcher(line);
            if (negativeFactMatcher.matches()) {
                String negativeFact = negativeFactMatcher.group(1).trim();
                factSink.accept("NegativeFact（负向事实）: " + negativeFact);
                pruneEvidenceForNegativeFact(negativeFact);
            }
        }
    }

    /**
     * negativeFact 表明某个旧 symbolVersion 已失效；立即清理引用该版本的 RAG 证据，
     * 避免过期证据继续留在注入上下文里误导模型（不再依赖外部维护服务触发）。
     * 行内没有结构化 oldSymbolVersion= 字段时不做任何清理。
     */
    private void pruneEvidenceForNegativeFact(String negativeFact) {
        if (negativeFact == null || negativeFact.isBlank()) {
            return;
        }
        Matcher matcher = NEGATIVE_FACT_OLD_SYMBOL_VERSION.matcher(negativeFact);
        if (!matcher.find()) {
            return;
        }
        String oldSymbolVersion = matcher.group(1).trim();
        pruneVersion.accept(oldSymbolVersion);
    }

    private static String extractQuery(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("\"query\"\\s*:\\s*\"([^\"]+)\"").matcher(argsJson);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static double safeParseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private record PendingRagEvidence(String chunkType, String symbolName, double similarity, String filePath) {}
}
