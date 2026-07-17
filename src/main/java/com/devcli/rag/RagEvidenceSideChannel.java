package com.devcli.rag;

import com.devcli.tool.ToolSideChannel;

/**
 * search_code 产生的结构化 RAG 证据。
 */
public record RagEvidenceSideChannel(RagEvidencePayload.Payload payload) implements ToolSideChannel {
    public RagEvidenceSideChannel {
        payload = payload == null ? RagEvidencePayload.Payload.empty() : payload.normalized();
    }
}
