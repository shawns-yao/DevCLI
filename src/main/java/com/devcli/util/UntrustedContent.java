package com.devcli.util;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 运行时生成的信任标签与数据正文分开编码，正文不能伪造外层字段。 */
public final class UntrustedContent {
    public static final String UNTRUSTED = "UNTRUSTED";
    private static final ObjectMapper JSON = new ObjectMapper();

    private UntrustedContent() { }

    public static String toolResult(String sourceTool, String callId, String content) {
        var framed = JSON.createObjectNode();
        framed.putObject("tool_result_security")
                .put("content_trust", UNTRUSTED)
                .put("source_tool", sourceTool)
                .put("tool_call_id", callId);
        framed.put("content", content);
        return framed.toString();
    }
}
