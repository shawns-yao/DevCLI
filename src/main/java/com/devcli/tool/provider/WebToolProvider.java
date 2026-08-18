package com.devcli.tool.provider;

import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolExecutionContext;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.devcli.web.FetchResult;
import com.devcli.web.HtmlExtractor;
import com.devcli.web.NetworkPolicy;
import com.devcli.web.SearchProvider;
import com.devcli.web.SearchProviderFactory;
import com.devcli.web.SearchResult;
import com.devcli.web.WebFetcher;

import java.util.List;

public final class WebToolProvider implements ToolProvider {
    private static final int DEFAULT_FETCH_MAX_CHARS = 8_000;
    /** 网络工具外层强制超时（秒），与内部 OkHttp callTimeout 对齐，作为卡死兜底。 */
    private static final long WEB_TOOL_TIMEOUT_SECONDS = 30;

    private SearchProvider searchProvider;
    private WebFetcher webFetcher;
    private HtmlExtractor htmlExtractor;
    private NetworkPolicy networkPolicy;

    @Override
    public void register(ToolContext context) {
        context.registerTool(ToolRegistry.Tool.contextualStructured(
                "web_search",
                "搜索互联网，获取实时信息（最新版本、官方文档、技术资讯等）。" +
                        "支持 SerpAPI（默认）和 SearXNG（自托管）两种 provider，由 SEARCH_PROVIDER 环境变量切换。",
                context.createToolParameters(
                        new ToolParameter("query", "string", "搜索关键词，例如'Java 21 新特性'、'Spring Boot 3.3 release notes'", true),
                        new ToolParameter("top_k", "integer", "返回结果数量（默认5）", false)
                ),
                (args, executionContext) -> webSearchOutput(
                        args.get("query"), parseInt(args.get("top_k"), 5), executionContext),
                WEB_TOOL_TIMEOUT_SECONDS
        ));

        context.registerTool(ToolRegistry.Tool.contextualStructured(
                "web_fetch",
                "抓取指定 URL，提取正文转 Markdown。" +
                        "适用静态 / SSR 页面（博客、文档、官网）；JS 渲染或防爬站会返回空正文，本期不重试。",
                context.createToolParameters(
                        new ToolParameter("url", "string", "完整 URL，需 http 或 https 协议", true),
                        new ToolParameter("max_chars", "integer", "返回 Markdown 最大字符数（默认 8000，超出截断）", false)
                ),
                (args, executionContext) -> webFetchOutput(
                        args.get("url"),
                        parseInt(args.get("max_chars"), DEFAULT_FETCH_MAX_CHARS),
                        executionContext),
                WEB_TOOL_TIMEOUT_SECONDS
        ));
    }

    String webSearch(String query, int topK) {
        return webSearchOutput(query, topK, ToolExecutionContext.current("")).text();
    }

    private ToolOutput webSearchOutput(String query, int topK,
                                       ToolExecutionContext executionContext) {
        if (query == null || query.isBlank()) {
            return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS,
                    "搜索关键词不能为空", false);
        }
        SearchProvider provider = searchProvider();
        if (!provider.isReady()) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    provider.unavailableHint(), true);
        }
        try {
            List<SearchResult> results = provider.search(
                    query.trim(), topK, executionContext);
            return ToolOutput.success(formatSearchResults(provider.name(), query, results));
        } catch (Exception e) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "搜索失败 (" + provider.name() + "): " + e.getMessage(), true);
        }
    }

    String webFetch(String url, int maxChars) {
        return webFetchOutput(url, maxChars, ToolExecutionContext.current("")).text();
    }

    private ToolOutput webFetchOutput(String url, int maxChars,
                                      ToolExecutionContext executionContext) {
        if (url == null || url.isBlank()) {
            return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS,
                    "URL 不能为空", false);
        }
        NetworkPolicy policy = networkPolicy();
        String denyReason = policy.checkUrl(url);
        if (denyReason != null) {
            return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                    "网络访问被拒绝: " + denyReason);
        }
        String rateReason = policy.acquire();
        if (rateReason != null) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    rateReason, true);
        }

        try {
            WebFetcher.RawResponse raw = webFetcher().fetch(
                    url.trim(), executionContext);
            HtmlExtractor.Extracted extracted = htmlExtractor().extract(raw.body(), raw.url());
            String markdown = extracted.markdown();
            int originalLength = markdown.length();
            boolean truncated = false;
            if (maxChars > 0 && markdown.length() > maxChars) {
                markdown = markdown.substring(0, maxChars);
                truncated = true;
            }
            FetchResult result = FetchResult.ok(raw.url(), extracted.title(), markdown, originalLength, truncated);
            return ToolOutput.success(formatFetchResult(result));
        } catch (Exception e) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "抓取失败: " + e.getMessage(), true);
        }
    }

    private synchronized SearchProvider searchProvider() {
        if (searchProvider == null) {
            searchProvider = SearchProviderFactory.create();
        }
        return searchProvider;
    }

    private synchronized WebFetcher webFetcher() {
        if (webFetcher == null) {
            webFetcher = new WebFetcher();
        }
        return webFetcher;
    }

    private synchronized HtmlExtractor htmlExtractor() {
        if (htmlExtractor == null) {
            htmlExtractor = new HtmlExtractor();
        }
        return htmlExtractor;
    }

    private synchronized NetworkPolicy networkPolicy() {
        if (networkPolicy == null) {
            networkPolicy = new NetworkPolicy();
        }
        return networkPolicy;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String formatSearchResults(String providerName, String query, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "🔍 [" + providerName + "] " + query + "\n\n未找到相关结果。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 [").append(providerName).append("] ").append(query).append("\n\n");
        for (SearchResult r : results) {
            sb.append(r.position()).append(". ").append(r.title()).append("\n");
            if (!r.snippet().isBlank()) {
                String snippet = r.snippet();
                if (snippet.length() > 200) {
                    snippet = snippet.substring(0, 200) + "...";
                }
                sb.append("   ").append(snippet).append("\n");
            }
            if (!r.url().isBlank()) {
                sb.append("   🔗 ").append(r.url());
                if (!r.source().isBlank()) {
                    sb.append("  (").append(r.source()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static String formatFetchResult(FetchResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌐 抓取: ").append(result.url()).append("\n");
        if (!result.title().isBlank()) {
            sb.append("📄 标题: ").append(result.title()).append("\n");
        }
        if (result.bodyEmpty()) {
            sb.append("\n⚠️ ").append(result.hint()).append("\n");
            return sb.toString();
        }
        sb.append("📏 正文 ").append(result.contentLength()).append(" 字符");
        if (result.truncated()) {
            sb.append("（已截断）");
        }
        sb.append("\n\n---\n\n");
        sb.append(result.markdown());
        return sb.toString();
    }
}
