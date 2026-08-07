package com.devcli.rag;

import com.devcli.policy.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 将不含代码正文的 RAG 分阶段检索记录写入本机 JSONL。 */
public final class RagRetrievalAuditRecorder {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(RagRetrievalAuditRecorder.class);
    private final Path directory;
    private final boolean enabled;

    public RagRetrievalAuditRecorder() {
        this(resolveDirectory(), readBoolean("devcli.rag.audit.enabled", "DEVCLI_RAG_AUDIT_ENABLED", true));
    }

    RagRetrievalAuditRecorder(Path directory, boolean enabled) {
        this.directory = directory;
        this.enabled = enabled;
    }

    public synchronized void record(CodeRetriever.RetrievalAudit audit) {
        if (!enabled || audit == null || audit.timestamp().equals(java.time.Instant.EPOCH)) {
            return;
        }
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve("rag-audit-" + LocalDate.now() + ".jsonl");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("timestamp", audit.timestamp().toString());
            payload.put("query", SensitiveDataRedactor.redact(audit.query()));
            payload.put("mode", audit.mode());
            payload.put("requestedTopK", audit.requestedTopK());
            payload.put("channels", audit.channels());
            payload.put("fused", audit.fused());
            payload.put("reranked", audit.reranked());
            payload.put("selected", audit.selected());
            payload.put("semanticDegraded", audit.semanticDegraded());
            payload.put("rerankDegraded", audit.rerankDegraded());
            payload.put("rerankStrategy", audit.rerankStrategy());
            Files.writeString(target, MAPPER.writeValueAsString(payload) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("RAG retrieval audit write failed: {}", e.getMessage());
        }
    }

    private static Path resolveDirectory() {
        String value = System.getProperty("devcli.rag.audit.dir");
        if (value == null || value.isBlank()) value = System.getenv("DEVCLI_RAG_AUDIT_DIR");
        return value == null || value.isBlank()
                ? Path.of(System.getProperty("user.home"), ".devcli", "rag-audit")
                : Path.of(value).toAbsolutePath().normalize();
    }

    private static boolean readBoolean(String property, String env, boolean fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(env);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }
}
