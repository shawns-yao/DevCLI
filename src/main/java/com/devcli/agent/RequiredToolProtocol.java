package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** 命名工具调用的 JSON 信封解析与纠正提示。 */
final class RequiredToolProtocol {
    private static final ObjectMapper JSON = new ObjectMapper();

    private RequiredToolProtocol() {
    }

    static String buildToolEnvelopeRepairPrompt(String toolName) {
        return "Provider 未生成原生工具调用。只输出严格 JSON，不要输出 Markdown、代码围栏或解释：\n"
                + toolEnvelopeTemplate(toolName);
    }

    static LlmClient.ChatResponse adaptRequiredToolEnvelope(
            LlmClient.ChatResponse response, LlmClient.ToolChoice toolChoice) {
        if (response == null || response.hasToolCalls() || toolChoice == null
                || !toolChoice.hasSpecificTool() || response.content() == null
                || response.content().isBlank()) {
            return response;
        }
        try (JsonParser parser = JSON.getFactory().createParser(response.content().trim())) {
            JsonNode root = JSON.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                return response;
            }
            Iterator<String> fields = root.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!"name".equals(field) && !"tool".equals(field) && !"arguments".equals(field)) {
                    return response;
                }
            }
            boolean hasName = root.has("name");
            boolean hasTool = root.has("tool");
            if (hasName == hasTool) {
                return response;
            }
            String name = hasName
                    ? root.path("name").asText("")
                    : root.path("tool").asText("");
            JsonNode arguments = root.get("arguments");
            if (!toolChoice.toolName().equals(name) || arguments == null || !arguments.isObject()) {
                return response;
            }
            String callId = "fallback_" + Integer.toUnsignedString(
                    response.content().hashCode(), 16);
            LlmClient.ToolCall toolCall = new LlmClient.ToolCall(
                    callId,
                    new LlmClient.ToolCall.Function(name, JSON.writeValueAsString(arguments)));
            return new LlmClient.ChatResponse(
                    response.role(), "", response.reasoningContent(), List.of(toolCall),
                    response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
        } catch (IOException ignored) {
            return response;
        }
    }

    private static String toolEnvelopeTemplate(String toolName) {
        return switch (Objects.toString(toolName, "")) {
            case "write_file" -> "{\"name\":\"write_file\",\"arguments\":{\"path\":\"真实项目相对路径\",\"content\":\"完整文件内容\"}}";
            case "execute_command" -> "{\"name\":\"execute_command\",\"arguments\":{\"command\":\"实际命令\"}}";
            default -> "{\"name\":\"" + Objects.toString(toolName, "list_dir")
                    + "\",\"arguments\":{\"path\":\".\"}}";
        };
    }
}
