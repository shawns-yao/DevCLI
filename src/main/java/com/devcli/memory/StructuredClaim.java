package com.devcli.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 可确定解析的键值声明，用于压缩约束对账和长期记忆冲突治理。 */
final class StructuredClaim {
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)([a-z_][a-z0-9_.-]{2,})\\s*[:=]\\s*([^\\s,，;；。]+)");
    private static final Pattern NEGATED_USAGE = Pattern.compile(
            "(?i)^(.{2,100}?)\\s*(?:不使用|禁止使用|不得使用|must not use|do not use)\\s*(.{1,160})$");
    private static final Pattern NATURAL = Pattern.compile(
            "(?i)^(.{2,100}?)(?:默认|当前|现在|default|currently)?\\s*"
                    + "(?:是|为|使用|采用|设置为|is|uses?|use|set to)\\s*(.{1,160})$");
    private static final Pattern STABLE_SUBJECT = Pattern.compile(
            "(?i)(默认|偏好|版本|端口|模型|语言|框架|provider|model|port|version|default|preference|当前|现在)");

    private StructuredClaim() {
    }

    static Optional<Claim> parse(String text) {
        String source = clean(text);
        if (source.isBlank()) return Optional.empty();

        Matcher assignment = ASSIGNMENT.matcher(source);
        if (assignment.find()) {
            String key = canonical(assignment.group(1));
            String value = canonical(assignment.group(2));
            if (!key.isBlank() && !value.isBlank()) {
                return Optional.of(new Claim("key:" + key, value,
                        assignment.group(1).trim() + "=" + assignment.group(2).trim()));
            }
        }

        Matcher negated = NEGATED_USAGE.matcher(source);
        if (negated.matches()) {
            String subject = canonicalSubject(negated.group(1));
            String value = canonical(negated.group(2));
            if (!subject.isBlank() && !value.isBlank()) {
                return Optional.of(new Claim("claim:" + shortHash(subject), "!" + value, source));
            }
        }

        Matcher natural = NATURAL.matcher(source);
        if (!natural.matches() || !STABLE_SUBJECT.matcher(source).find()) {
            return Optional.empty();
        }
        String subject = canonicalSubject(natural.group(1));
        String value = canonical(natural.group(2));
        if (subject.isBlank() || value.isBlank()) return Optional.empty();
        return Optional.of(new Claim("claim:" + shortHash(subject), value, source));
    }

    private static String clean(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .replaceAll("[。.!！；;]+$", "")
                .trim();
    }

    private static String canonicalSubject(String value) {
        return canonical(value).replaceAll("(?i)(默认|当前|现在|default|currently)", "");
    }

    private static String canonical(String value) {
        return clean(value).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}._:/-]+", "");
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    record Claim(String subject, String value, String display) {
    }
}
