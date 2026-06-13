package com.devcli.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.devcli.web.RetryInterceptor;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Embedding 客户端，支持 Ollama 本地模型和 OpenAI 兼容的远程 API
 */
public class EmbeddingClient {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(new RetryInterceptor())
            .build();

    private final String provider;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    /**
     * 单次 embed 调用的输入字符上限。
     *
     * <p>nomic-embed-text / GLM embedding-3 / OpenAI text-embedding-3-large
     * 都接受 ~8K token 输入，对应 ~6K 中文字符或 ~24K 英文字符。
     * 默认 6000 字符兼顾安全裕度（避免 byte-pair 编码后超 8192 token）。
     * 通过 {@code EMBEDDING_MAX_INPUT_CHARS} 环境变量可覆盖。
     */
    private final int maxInputChars;

    /** 默认输入字符上限，对应嵌入模型 ~8K token 容量留 25% 安全裕度。 */
    private static final int DEFAULT_MAX_INPUT_CHARS = 6000;

    public EmbeddingClient() {
        this.provider = normalizeProvider(getEnv("EMBEDDING_PROVIDER", "ollama"));
        this.model = getEnv("EMBEDDING_MODEL", "nomic-embed-text:latest");
        this.baseUrl = getEnv("EMBEDDING_BASE_URL", inferDefaultUrl(provider));
        this.apiKey = getEnv("EMBEDDING_API_KEY", "");
        this.maxInputChars = parsePositiveInt(getEnv("EMBEDDING_MAX_INPUT_CHARS", ""), DEFAULT_MAX_INPUT_CHARS);
    }

    public EmbeddingClient(String provider, String model, String baseUrl, String apiKey) {
        this.provider = normalizeProvider(provider);
        this.model = model;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.maxInputChars = DEFAULT_MAX_INPUT_CHARS;
    }

    /**
     * 校验并归一化 provider。
     *
     * <p>未知 provider 直接抛 {@link IllegalArgumentException}（Fail-Fast）。
     * 旧版本会在 {@code embed()} 时静默 fallback 到 ollama，用户拼错 provider
     * （比如 EMBEDDING_PROVIDER=deepseek）只会拿到 "connection refused" 之类
     * 难以诊断的错误。这里在构造时就报错，错误信息直接告诉用户支持哪些 provider。
     */
    static String normalizeProvider(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "EMBEDDING_PROVIDER 不能为空，支持值: ollama / openai / zhipu / glm");
        }
        String lower = raw.trim().toLowerCase();
        return switch (lower) {
            case "ollama" -> "ollama";
            case "openai" -> "openai";
            // glm 是 zhipu 的别名，归一化成 zhipu 简化下游分支
            case "zhipu", "glm" -> "zhipu";
            default -> throw new IllegalArgumentException(
                    "未知 EMBEDDING_PROVIDER='" + raw + "'，支持值: ollama / openai / zhipu / glm");
        };
    }

    /**
     * 获取文本的向量表示。
     *
     * <p>空字符串 / null 输入会抛 {@link IOException}（Fail-Fast）：
     * 旧版本返回 {@code new float[0]}，调用方写入零向量后所有余弦相似度计算
     * 退化成 NaN，对应 fact 实际上从向量召回里失踪——非常难诊断。这里直接
     * 拒绝，让上层（如 {@code MemoryVectorStore.upsert}）捕获并显式跳过。
     */
    public float[] embed(String text) throws IOException {
        if (text == null || text.isBlank()) {
            throw new IOException("EmbeddingClient.embed: 输入文本为空");
        }

        // 截断过长文本，防止 API 报错。阈值由构造期 maxInputChars 决定（默认 6000，可通过 EMBEDDING_MAX_INPUT_CHARS 覆盖）。
        String input = text.length() > maxInputChars
                ? text.substring(0, maxInputChars)
                : text;

        return switch (provider) {
            // provider 已经过 normalizeProvider 归一化为小写、glm→zhipu
            case "ollama" -> embedOllama(input);
            case "openai", "zhipu" -> embedOpenAICompatible(input);
            default -> throw new IOException("BUG: provider 未在归一化列表中: " + provider);
        };
    }

    private float[] embedOllama(String text) throws IOException {
        String url = baseUrl + "/api/embeddings";

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("prompt", text);

        String responseBody = postJson(url, requestBody.toString(), false);
        JsonNode root = mapper.readTree(responseBody);
        JsonNode embeddingNode = root.path("embedding");

        if (!embeddingNode.isArray()) {
            throw new IOException("Ollama 返回的 embedding 格式不正确: " + responseBody);
        }

        float[] embedding = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            embedding[i] = (float) embeddingNode.get(i).asDouble();
        }
        return embedding;
    }

    private float[] embedOpenAICompatible(String text) throws IOException {
        String url = baseUrl + "/embeddings";

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("input", text);

        String responseBody = postJson(url, requestBody.toString(), true);
        JsonNode root = mapper.readTree(responseBody);
        JsonNode data = root.path("data");

        if (!data.isArray() || data.isEmpty()) {
            throw new IOException("API 返回的 embedding 格式不正确: " + responseBody);
        }

        JsonNode embeddingNode = data.get(0).path("embedding");
        float[] embedding = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            embedding[i] = (float) embeddingNode.get(i).asDouble();
        }
        return embedding;
    }

    private String postJson(String url, String jsonBody, boolean useAuth) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(body);

        if (useAuth && apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        try (Response response = HTTP_CLIENT.newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                String error = responseBody != null ? responseBody.string() : "无响应";
                throw new IOException("Embedding API 请求失败 [" + response.code() + "]: " + error);
            }
            if (responseBody == null) {
                throw new IOException("Embedding API 返回空响应体");
            }
            return responseBody.string();
        }
    }

    private static String inferDefaultUrl(String normalizedProvider) {
        // normalizedProvider 已经过 normalizeProvider 归一化（小写、glm→zhipu）
        return switch (normalizedProvider) {
            case "ollama" -> "http://localhost:11434";
            case "zhipu" -> "https://open.bigmodel.cn/api/paas/v4";
            case "openai" -> "https://api.openai.com/v1";
            default -> "http://localhost:11434";
        };
    }

    /** 解析正整数 env 配置；非法 / 空 / 0 / 负数都退回默认值。 */
    private static int parsePositiveInt(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value != null && !value.isEmpty()) {
            return value.trim();
        }

        value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            return value.trim();
        }

        value = readFromDotEnv(new File(".env"), key);
        if (value != null && !value.isEmpty()) {
            return value.trim();
        }

        value = readFromDotEnv(new File(System.getProperty("user.home"), ".env"), key);
        if (value != null && !value.isEmpty()) {
            return value.trim();
        }
        return defaultValue;
    }

    private static String readFromDotEnv(File file, String key) {
        if (!file.exists()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int equalsIndex = line.indexOf('=');
                if (equalsIndex <= 0) {
                    continue;
                }
                String candidateKey = line.substring(0, equalsIndex).trim();
                if (!key.equals(candidateKey)) {
                    continue;
                }
                return stripOptionalQuotes(line.substring(equalsIndex + 1).trim());
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static String stripOptionalQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    /** 当前实际生效的输入字符上限（受 EMBEDDING_MAX_INPUT_CHARS 控制）。 */
    public int getMaxInputChars() {
        return maxInputChars;
    }
}
