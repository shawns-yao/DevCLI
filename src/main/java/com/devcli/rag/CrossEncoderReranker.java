package com.devcli.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.devcli.web.RetryInterceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI-compatible cross-encoder reranker.
 */
public class CrossEncoderReranker implements CodeReranker {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_MAX_INPUT_CHARS = 1200;
    private static final int DEFAULT_CANDIDATE_LIMIT = 20;
    private static final String DEFAULT_MODEL = "BAAI/bge-reranker-v2-m3";
    private static final String DEFAULT_BASE_URL = "http://localhost:8000/v1";
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(new RetryInterceptor())
            .build();

    private final boolean enabled;
    private final String provider;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final int maxInputChars;
    private final int candidateLimit;
    // Bug #11 修复：添加冷却期机制，避免单次失败永久禁用
    private volatile boolean disabledAfterFailure;
    private volatile long lastFailureTimestamp;
    private static final long COOLDOWN_PERIOD_MS = 5 * 60 * 1000; // 5 分钟冷却期

    public CrossEncoderReranker() {
        this(parseBoolean(getEnv("RERANK_ENABLED", "true")),
                getEnv("RERANK_PROVIDER", "openai"),
                getEnv("RERANK_MODEL", DEFAULT_MODEL),
                getEnv("RERANK_BASE_URL", DEFAULT_BASE_URL),
                getEnv("RERANK_API_KEY", ""),
                parsePositiveInt(getEnv("RERANK_MAX_INPUT_CHARS", ""), DEFAULT_MAX_INPUT_CHARS),
                parsePositiveInt(getEnv("RERANK_CANDIDATE_LIMIT", ""), DEFAULT_CANDIDATE_LIMIT));
    }

    CrossEncoderReranker(boolean enabled,
                         String provider,
                         String model,
                         String baseUrl,
                         String apiKey,
                         int maxInputChars,
                         int candidateLimit) {
        this.enabled = enabled && model != null && !model.isBlank() && baseUrl != null && !baseUrl.isBlank();
        this.provider = provider == null || provider.isBlank() ? "openai" : provider.trim().toLowerCase();
        this.model = model == null ? "" : model.trim();
        this.baseUrl = trimTrailingSlash(baseUrl == null ? "" : baseUrl.trim());
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.maxInputChars = Math.max(1, maxInputChars);
        this.candidateLimit = Math.max(1, candidateLimit);
    }

    @Override
    public List<VectorStore.SearchResult> rerank(String query,
                                                 List<VectorStore.SearchResult> candidates,
                                                 int limit) throws Exception {
        if (!enabled() || candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates.stream().limit(Math.max(0, limit)).toList();
        }
        int candidateCount = Math.min(candidates.size(), Math.max(limit, candidateLimit));
        List<VectorStore.SearchResult> scoped = candidates.stream().limit(candidateCount).toList();
        List<ScoredCandidate> scored;
        try {
            scored = callRerank(query, scoped);
        } catch (Exception e) {
            // Bug #11 修复：记录失败时间戳，5 分钟后自动重试
            disabledAfterFailure = true;
            lastFailureTimestamp = System.currentTimeMillis();
            throw e;
        }
        if (scored.isEmpty()) {
            return scoped.stream().limit(Math.max(0, limit)).toList();
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed()
                        .thenComparingInt(ScoredCandidate::index))
                .limit(Math.max(0, limit))
                .map(scoredCandidate -> withScore(scoped.get(scoredCandidate.index()), scoredCandidate.score()))
                .toList();
    }

    @Override
    public boolean enabled() {
        // Bug #11 修复：冷却期过后自动恢复
        if (disabledAfterFailure && System.currentTimeMillis() - lastFailureTimestamp > COOLDOWN_PERIOD_MS) {
            disabledAfterFailure = false;
        }
        return enabled && !disabledAfterFailure;
    }

    @Override
    public String description() {
        if (!enabled) {
            return "disabled";
        }
        return provider + "/" + model;
    }

    private List<ScoredCandidate> callRerank(String query, List<VectorStore.SearchResult> candidates) throws IOException {
        ObjectNode request = JSON.createObjectNode();
        request.put("model", model);
        request.put("query", query);
        ArrayNode documents = request.putArray("documents");
        for (VectorStore.SearchResult candidate : candidates) {
            documents.add(formatDocument(candidate));
        }

        String responseBody = postJson(baseUrl + "/rerank", request.toString());
        JsonNode root = JSON.readTree(responseBody);
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            throw new IOException("Rerank API 返回格式不正确: " + responseBody);
        }
        List<ScoredCandidate> scored = new ArrayList<>();
        for (JsonNode result : results) {
            int index = result.path("index").asInt(-1);
            double score = result.has("relevance_score")
                    ? result.path("relevance_score").asDouble()
                    : result.path("score").asDouble(Double.NaN);
            if (index >= 0 && index < candidates.size() && !Double.isNaN(score)) {
                scored.add(new ScoredCandidate(index, score));
            }
        }
        return scored;
    }

    private String formatDocument(VectorStore.SearchResult result) {
        String content = result.content() == null ? "" : result.content();
        String trimmed = content.length() > maxInputChars ? content.substring(0, maxInputChars) : content;
        return result.chunkType() + " " + result.name() + "\n"
                + result.filePath() + "\n"
                + trimmed;
    }

    private String postJson(String url, String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(body);
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        try (Response response = HTTP_CLIENT.newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                String error = responseBody != null ? responseBody.string() : "无响应";
                throw new IOException("Rerank API 请求失败 [" + response.code() + "]: " + error);
            }
            if (responseBody == null) {
                throw new IOException("Rerank API 返回空响应体");
            }
            return responseBody.string();
        }
    }

    private VectorStore.SearchResult withScore(VectorStore.SearchResult result, double score) {
        return new VectorStore.SearchResult(result.filePath(), result.chunkType(), result.name(), result.content(),
                score, result.symbolVersion(), result.classpathEpoch(), result.indexEpoch(), result.invalidations());
    }

    private static boolean parseBoolean(String raw) {
        return raw != null && ("true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim()));
    }

    private static int parsePositiveInt(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        value = readFromDotEnv(new File(".env"), key);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        value = readFromDotEnv(new File(System.getProperty("user.home"), ".env"), key);
        if (value != null && !value.isBlank()) {
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
                if (key.equals(candidateKey)) {
                    return stripOptionalQuotes(line.substring(equalsIndex + 1).trim());
                }
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

    private record ScoredCandidate(int index, double score) {
    }
}
