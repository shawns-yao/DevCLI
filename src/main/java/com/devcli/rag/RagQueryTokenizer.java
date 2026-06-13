package com.devcli.rag;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.devcli.util.JiebaSegmenterFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 查询分词器。
 *
 * 目标不是做复杂 NLP，而是把自然语言问题里的“代码关键词”尽量保留下来，
 * 例如类名、方法名、ReAct、Agent、index、memory 等，用于混合检索加权。
 */
final class RagQueryTokenizer {
    private static final JiebaSegmenter SEGMENTER = JiebaSegmenterFactory.createSilently();
    private static final Pattern ASCII_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9_.$-]{1,}");
    private static final Pattern CAMEL_PART = Pattern.compile("[A-Z]?[a-z]+|[A-Z]+(?=[A-Z]|$)|\\d+");

    private RagQueryTokenizer() {
    }

    static Set<String> tokenize(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return tokens;
        }

        String normalized = query.trim();
        List<String> words = SEGMENTER.sentenceProcess(normalized);
        for (String word : words) {
            String token = word.trim();
            if (isUsefulToken(token)) {
                tokens.add(token);
                addDomainAliases(tokens, token);
            }
        }

        Matcher matcher = ASCII_TOKEN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (isUsefulToken(token)) {
                tokens.add(token);
                addCodeTokenParts(tokens, token);
            }
        }

        return tokens;
    }

    private static void addDomainAliases(Set<String> tokens, String token) {
        switch (token.toLowerCase(Locale.ROOT)) {
            case "注册" -> {
                tokens.add("register");
                tokens.add("registry");
            }
            case "工具" -> tokens.add("tool");
            case "检索", "检索器" -> {
                tokens.add("search");
                tokens.add("retriever");
            }
            case "关系", "图谱" -> {
                tokens.add("relation");
                tokens.add("analyzer");
            }
            case "提取", "解析" -> {
                tokens.add("resolve");
                tokens.add("analyze");
            }
            case "向量" -> tokens.add("vector");
            case "存储" -> tokens.add("store");
            case "余弦" -> tokens.add("cosine");
            case "相似度" -> tokens.add("similarity");
            case "记忆" -> tokens.add("memory");
            case "压缩" -> tokens.add("compress");
            case "覆盖" -> tokens.add("override");
            case "诊断" -> tokens.add("diagnostic");
            case "耗时" -> tokens.add("duration");
            default -> {
            }
        }
    }

    private static void addCodeTokenParts(Set<String> tokens, String token) {
        String[] dottedParts = token.split("[.$_-]+");
        for (String part : dottedParts) {
            addTokenIfUseful(tokens, part);
            Matcher camelMatcher = CAMEL_PART.matcher(part);
            while (camelMatcher.find()) {
                addTokenIfUseful(tokens, camelMatcher.group());
            }
        }
    }

    private static void addTokenIfUseful(Set<String> tokens, String token) {
        if (!isUsefulToken(token)) {
            return;
        }
        tokens.add(token);
        String lower = token.toLowerCase(Locale.ROOT);
        if (!lower.equals(token)) {
            tokens.add(lower);
        }
    }

    private static boolean isUsefulToken(String token) {
        if (token == null) {
            return false;
        }

        String normalized = token.trim();
        if (normalized.length() < 2) {
            return false;
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        boolean stopword = switch (lower) {
            case "怎么", "如何", "什么", "哪些", "一下", "实现", "的是", "一个", "可以", "这里", "那里" -> true;
            default -> false;
        };
        return !stopword && isMeaningful(normalized);
    }

    private static boolean isMeaningful(String token) {
        boolean hasHan = token.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
        boolean hasAsciiWord = token.codePoints().anyMatch(Character::isLetterOrDigit);
        return hasHan || hasAsciiWord;
    }
}
