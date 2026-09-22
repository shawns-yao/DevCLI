package com.devcli.tool.provider;

import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolSideChannel;
import com.devcli.llm.LlmClient;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class ToolSearchProvider implements ToolProvider {
    private static final Set<String> QUERY_STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "has", "have",
            "i", "in", "is", "it", "of", "on", "or", "that", "the", "this", "to", "was", "with");
    private final AtomicLong buildCount = new AtomicLong();
    private volatile ToolSearchIndex searchIndex;

    @Override
    public void register(ToolContext context) {
        context.registerTool(ToolRegistry.Tool.structured(
                "search_tools",
                "Search currently available tools by name, description and parameter schema. Use this when the exact MCP or built-in tool name is unknown.",
                context.createToolParameters(
                        new ToolParameter("query", "string", "keywords to search in tool name, description and parameter schema", true),
                        new ToolParameter("limit", "string", "maximum number of matches to return, default 10", false)
                ),
                args -> searchToolsOutput(context, args.get("query"), args.get("limit"))
        ));
    }

    private ToolOutput searchToolsOutput(ToolContext context, String query, String limitValue) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS,
                    "search_tools 失败: query 不能为空", false);
        }
        return searchToolsResult(context, query, limitValue);
    }

    public String searchTools(ToolContext context, String query, String limitValue) {
        return searchToolsResult(context, query, limitValue).text();
    }

    private ToolOutput searchToolsResult(ToolContext context, String query, String limitValue) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS, "search_tools 失败: query 不能为空", false);
        }
        int limit = parseSearchToolLimit(limitValue);
        List<String> terms = splitTerms(normalized);
        List<ToolSearchMatch> matches = searchEntries(context).stream()
                .map(entry -> new ToolSearchMatch(entry, scoreTool(entry, terms)))
                .filter(match -> match.score() > 0)
                .sorted(Comparator
                        .comparingInt(ToolSearchMatch::score).reversed()
                        .thenComparing(match -> match.entry().name()))
                .limit(limit)
                .toList();
        if (matches.isEmpty()) {
            return ToolOutput.success("未找到匹配工具: " + query)
                    .withSideChannel(new DiscoveredTools(List.of()));
        }
        StringBuilder sb = new StringBuilder("匹配工具:\n");
        for (ToolSearchMatch match : matches) {
            ToolSearchEntry entry = match.entry();
            context.activateToolDefinition(entry.name());
            sb.append("- ").append(entry.name()).append(": ")
                    .append(oneLine(entry.description())).append('\n');
        }
        return ToolOutput.success(sb.toString().stripTrailing())
                .withSideChannel(new DiscoveredTools(matches.stream().map(match -> match.entry().name()).toList()));
    }

    public record DiscoveredTools(List<String> names) implements ToolSideChannel {
        public DiscoveredTools { names = List.copyOf(names); }
    }

    /** Reuse discovery ranking; selection changes visibility, never execution authority. */
    public static List<LlmClient.Tool> rankCandidates(List<LlmClient.Tool> tools, String input) {
        List<String> terms = splitTerms(input == null ? "" : input.toLowerCase(Locale.ROOT)).stream()
                .filter(term -> !QUERY_STOP_WORDS.contains(term)).toList();
        record RankedTool(LlmClient.Tool tool, int score) {}
        return tools.stream().map(tool -> new RankedTool(tool, scoreTool(new ToolSearchEntry(
                        tool.name(), tool.description(), tool.name().toLowerCase(Locale.ROOT),
                        oneLine(tool.description()).toLowerCase(Locale.ROOT),
                        tool.parameters() == null ? "" : tool.parameters().toString().toLowerCase(Locale.ROOT)), terms)))
                .sorted(Comparator.comparingInt(RankedTool::score).reversed()
                        .thenComparing(ranked -> ranked.tool().name()))
                .map(RankedTool::tool).toList();
    }

    public int prefetchToolDefinitionsForInput(ToolContext context, String input) {
        String normalized = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return 0;
        }
        List<String> terms = splitTerms(normalized).stream()
                .filter(term -> !QUERY_STOP_WORDS.contains(term))
                .filter(term -> !term.matches("[a-z]"))
                .distinct()
                .toList();
        if (terms.isEmpty()) {
            return 0;
        }
        int activated = 0;
        List<ToolSearchMatch> matches = searchEntries(context).stream()
                .filter(entry -> context.isMcpTool(entry.name()))
                .map(entry -> new ToolSearchMatch(entry, scoreTool(entry, terms)))
                .filter(match -> match.score() > 0)
                .sorted(Comparator
                        .comparingInt(ToolSearchMatch::score).reversed()
                        .thenComparing(match -> match.entry().name()))
                .limit(5)
                .toList();
        for (ToolSearchMatch match : matches) {
            String name = match.entry().name();
            if (context.activateToolDefinition(name)) {
                activated++;
            }
        }
        return activated;
    }

    public long buildCount() {
        return buildCount.get();
    }

    private List<ToolSearchEntry> searchEntries(ToolContext context) {
        long version = context.toolCatalogVersion();
        List<ToolRegistry.Tool> visible = context.searchableTools();
        Set<String> names = visible.stream().map(ToolRegistry.Tool::name)
                .collect(java.util.stream.Collectors.toSet());
        ToolSearchIndex snapshot = searchIndex;
        if (snapshot != null && snapshot.version() == version && snapshot.names().equals(names)) {
            return snapshot.entries();
        }
        synchronized (this) {
            snapshot = searchIndex;
            if (snapshot != null && snapshot.version() == version && snapshot.names().equals(names)) {
                return snapshot.entries();
            }
            List<ToolSearchEntry> entries = visible.stream()
                    .filter(tool -> !"search_tools".equals(tool.name()))
                    .map(ToolSearchEntry::from)
                    .toList();
            searchIndex = new ToolSearchIndex(version, names, entries);
            buildCount.incrementAndGet();
            return entries;
        }
    }

    private static List<String> splitTerms(String normalized) {
        return Arrays.stream(normalized.split("\\s+"))
                .map(term -> term.replaceAll("^[^\\p{L}\\p{N}_-]+|[^\\p{L}\\p{N}_-]+$", ""))
                .filter(s -> !s.isBlank())
                .toList();
    }

    private static int parseSearchToolLimit(String value) {
        if (value == null || value.isBlank()) {
            return 10;
        }
        try {
            return Math.max(1, Math.min(30, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    private static int scoreTool(ToolSearchEntry entry, List<String> terms) {
        int score = 0;
        for (String term : terms) {
            if (matchesTerm(entry.searchName(), term)) {
                score += 3;
            }
            if (matchesTerm(entry.searchDescription(), term)) {
                score += 1;
            }
            if (matchesTerm(entry.searchSchema(), term)) {
                score += 1;
            }
        }
        return score;
    }

    private static boolean matchesTerm(String text, String term) {
        if (term.matches("[a-z]{1,2}")) {
            return java.util.regex.Pattern.compile("(?<![a-z0-9])"
                    + java.util.regex.Pattern.quote(term) + "(?![a-z0-9])").matcher(text).find();
        }
        return text.contains(term);
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private record ToolSearchIndex(long version, Set<String> names, List<ToolSearchEntry> entries) {
        private ToolSearchIndex {
            names = Set.copyOf(names);
            entries = List.copyOf(entries);
        }
    }

    private record ToolSearchEntry(String name, String description, String searchName,
                                   String searchDescription, String searchSchema) {
        static ToolSearchEntry from(ToolRegistry.Tool tool) {
            String name = tool.name() == null ? "" : tool.name();
            String description = tool.description() == null ? "" : tool.description();
            String schema = tool.parameters() == null ? "" : tool.parameters().toString();
            return new ToolSearchEntry(
                    name,
                    description,
                    name.toLowerCase(Locale.ROOT),
                    oneLine(description).toLowerCase(Locale.ROOT),
                    schema.toLowerCase(Locale.ROOT)
            );
        }
    }

    private record ToolSearchMatch(ToolSearchEntry entry, int score) {}
}
