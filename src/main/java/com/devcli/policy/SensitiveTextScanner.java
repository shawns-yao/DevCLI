package com.devcli.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Content-review findings use original UTF-16 offsets; no matched values are retained. */
final class SensitiveTextScanner {
    private record Rule(String id, String type, Pattern pattern, int group) { }

    private static final List<Rule> RULES = List.of(
            rule("private-key", "private_key", "(?s)-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----.*?"
                    + "(?:-----END (?:[A-Z0-9]+ )*PRIVATE KEY-----|\\z)", 0),
            rule("api-token", "credential", "\\b(?:sk-(?:proj-|ant-)?[A-Za-z0-9_-]{16,}|"
                    + "gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16})\\b", 0),
            rule("bearer", "credential", "(?i)\\bBearer[ \\t]+([A-Za-z0-9._~+/-]+={0,2})", 1),
            rule("authorization", "credential", "(?i)\\bauthorization[\"']?[ \\t]*:[ \\t]*[\"']?"
                    + "((?:Basic|Bearer)[ \\t]+[^\\s\"',;}]+)", 1),
            rule("id-card", "id_card", "\\b\\d{17}[0-9Xx]\\b", 0),
            rule("phone", "phone", "(?<!\\d)1[3-9]\\d{9}(?!\\d)", 0),
            rule("bank-card", "bank_card", "\\b\\d{13,19}\\b", 0),
            rule("address", "address", "(?i)(?:住址|收货地址|家庭地址|home\\s+address|shipping\\s+address)"
                    + "[\"']?[ \\t]*[:=：是][ \\t]*[\"']?([^\"',，;；\\r\\n}]+)", 1),
            rule("medical", "medical", "(?i)(?:病历|医疗诊断|诊断结果|medical\\s+record|medical\\s+diagnosis)"
                    + "[\"']?[ \\t]*[:=：是][ \\t]*[\"']?([^\"',，;；\\r\\n}]+)", 1));
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(?<![a-z0-9_])((?:[a-z0-9]+[_-])*(?:token|(?:access|refresh)[_-]?token|api[_-]?key|"
                    + "password|passwd|secret|client[_-]?secret|secret[_-](?:access[_-])?key|"
                    + "private[_-]?key|authorization|cookie|set-cookie|account|username|账号|账户|用户名)"
                    + ")(?![a-z0-9_])[\"']?[ \\t]*[:=：是](?!=|>)[ \\t]*");
    private static final Pattern DECLARATION = Pattern.compile(
            "(?:\\b(?:String|var|val|let|const|int|long|boolean|"
                    + "(?!(?:Authorization|Bearer|Basic)\\b)[A-Z][A-Za-z0-9_$]*)(?:<[^<>\\r\\n]+>)?\\s+)$");

    private SensitiveTextScanner() { }

    static List<SensitiveContentPolicy.Finding> scan(String text) {
        List<SensitiveContentPolicy.Finding> findings = new ArrayList<>();
        for (Rule rule : RULES) {
            var matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                add(findings, rule.type(), matcher.start(rule.group()), matcher.end(rule.group()), rule.id());
            }
        }
        var assignment = ASSIGNMENT.matcher(text);
        while (assignment.find()) {
            int start = assignment.end();
            if (start >= text.length()) continue;
            int lineStart = text.lastIndexOf('\n', assignment.start()) + 1;
            String prefix = text.substring(lineStart, assignment.start());
            // Search snippets may end before the semicolon, or come from a language without one.
            boolean declaration = DECLARATION.matcher(prefix).find();
            String type = assignment.group(1).matches("(?i)account|username|账号|账户|用户名")
                    ? "account" : "credential";
            char quote = text.charAt(start);
            if (quote == '"' || quote == '\'') {
                String delimiter = String.valueOf(quote);
                boolean multiline = text.startsWith(delimiter.repeat(3), start);
                if (multiline) delimiter = delimiter.repeat(3);
                int valueStart = start + delimiter.length();
                int end = quotedEnd(text, valueStart, delimiter, multiline);
                int tail = skipWhitespace(text, Math.min(text.length(), end + delimiter.length()));
                boolean dynamic = false;
                while (declaration && tail < text.length() && text.charAt(tail) == '+') {
                    int next = skipWhitespace(text, tail + 1);
                    if (next >= text.length() || text.charAt(next) != quote) {
                        dynamic = true;
                        break;
                    }
                    end = quotedEnd(text, next + delimiter.length(), delimiter, multiline);
                    tail = skipWhitespace(text, Math.min(text.length(), end + delimiter.length()));
                }
                // A generic source token may be a dynamically constructed lexer/UI token.
                // Explicit secret fields and recognizable key formats remain independently checked.
                if (dynamic && assignment.group(1).equalsIgnoreCase("token")) continue;
                if (!placeholder(text.substring(valueStart, end))) {
                    add(findings, type, valueStart, end, "sensitive-literal");
                }
            } else {
                int end = start;
                while (end < text.length() && !Character.isWhitespace(text.charAt(end))
                        && ",，;；}&\"'".indexOf(text.charAt(end)) < 0) end++;
                String value = text.substring(start, end);
                // A source declaration with a nonliteral RHS is an expression, not a secret value.
                // Known token/private-key patterns are scanned independently, even in that expression.
                if (!declaration && !placeholder(value)) {
                    add(findings, type, start, end, "sensitive-field");
                }
            }
        }
        findings.sort(Comparator.comparingInt(SensitiveContentPolicy.Finding::start)
                .thenComparingInt(SensitiveContentPolicy.Finding::end));
        return List.copyOf(findings);
    }

    private static int quotedEnd(String text, int start, String delimiter, boolean multiline) {
        int index = start;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (text.startsWith(delimiter, index)) return index;
            if (!multiline && (current == '\r' || current == '\n')) return index;
            if (current == '\\' && index + 1 < text.length()) index++;
            index++;
        }
        return index;
    }

    private static int skipWhitespace(String text, int start) {
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        return start;
    }

    private static boolean placeholder(String value) {
        return value.isBlank() || value.equals("null") || value.equals("undefined")
                || value.matches("\\$\\{[A-Za-z_][A-Za-z0-9_]*}")
                || value.matches("%[A-Za-z_][A-Za-z0-9_]*%");
    }

    private static Rule rule(String id, String type, String pattern, int group) {
        return new Rule(id, type, Pattern.compile(pattern), group);
    }

    private static void add(List<SensitiveContentPolicy.Finding> findings,
                            String type, int start, int end, String rule) {
        if (end > start) findings.add(new SensitiveContentPolicy.Finding(type, start, end, rule));
    }
}
