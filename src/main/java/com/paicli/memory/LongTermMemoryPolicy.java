package com.paicli.memory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 长期记忆写入决策器。
 *
 * <p>目标是高精度拦截：显式、稳定、低敏的信息才自动保存；敏感或中等置信信息交给上层确认；
 * 低价值临时信息只留在 WorkingMemory。
 */
public final class LongTermMemoryPolicy {
    private static final double AUTO_SAVE_THRESHOLD = 0.85;
    private static final double CONFIRM_THRESHOLD = 0.65;

    private static final Pattern ID_CARD = Pattern.compile("\\b\\d{17}[0-9Xx]\\b");
    private static final Pattern BANK_CARD = Pattern.compile("\\b\\d{13,19}\\b");
    private static final Pattern TOKEN = Pattern.compile("(?i)(api[_-]?key|token|secret|password|bearer)\\s*[:=]");

    private LongTermMemoryPolicy() {
    }

    public static Decision evaluate(String fact, int recurrenceCount) {
        return evaluate(fact, recurrenceCount, false);
    }

    public static Decision evaluate(String fact, int recurrenceCount, boolean explicitOverride) {
        String text = normalize(fact);
        if (text.isBlank()) {
            return Decision.skip(0.0, "空事实不保存");
        }

        boolean explicit = explicitOverride || hasExplicitRememberIntent(text);
        String sensitivity = sensitivity(text);
        String memoryType = memoryType(text);

        double explicitness = explicit ? 1.0 : 0.0;
        double stability = stability(text, memoryType);
        double futureUtility = futureUtility(text, memoryType);
        double recurrence = Math.min(1.0, Math.max(0, recurrenceCount) / 3.0);
        double specificity = specificity(text);
        double confidence = explicit ? 0.95 : (recurrence >= 1.0 ? 0.75 : 0.45);
        double sensitivityPenalty = switch (sensitivity) {
            case "high" -> 0.55;
            case "medium" -> 0.25;
            default -> 0.0;
        };

        double score = clamp(
                0.30 * explicitness
                        + 0.20 * futureUtility
                        + 0.15 * stability
                        + 0.15 * recurrence
                        + 0.10 * specificity
                        + 0.10 * confidence
                        - sensitivityPenalty
        );
        if (explicit && ("preference".equals(memoryType) || "project".equals(memoryType))) {
            score = clamp(score + 0.10);
        }
        if (!explicit && recurrenceCount >= 3 && ("preference".equals(memoryType) || "project".equals(memoryType))) {
            score = clamp(score + 0.25);
        }

        if ("high".equals(sensitivity)) {
            return Decision.confirm(score, "包含高敏感信息，必须用户确认", source(explicit, recurrenceCount), memoryType, sensitivity);
        }
        if ("medium".equals(sensitivity) && explicit) {
            return Decision.confirm(score, "包含敏感个人信息，必须用户确认", source(true, recurrenceCount), memoryType, sensitivity);
        }
        if (score >= AUTO_SAVE_THRESHOLD && ("low".equals(sensitivity))) {
            return Decision.save(score, source(explicit, recurrenceCount), memoryType, sensitivity);
        }
        if (score >= CONFIRM_THRESHOLD) {
            return Decision.confirm(score, "记忆价值中等，需要用户确认", source(explicit, recurrenceCount), memoryType, sensitivity);
        }
        return Decision.skip(score, "临时、低复用或低置信信息不进入长期记忆");
    }

    public static Decision evaluate(String fact) {
        return evaluate(fact, 0);
    }

    private static String source(boolean explicit, int recurrenceCount) {
        if (explicit) {
            return "explicit";
        }
        if (recurrenceCount >= 3) {
            return "recurrence";
        }
        return "heuristic";
    }

    private static boolean hasExplicitRememberIntent(String text) {
        return text.contains("记住")
                || text.contains("记一下")
                || text.contains("记下来")
                || text.contains("以后记得")
                || text.contains("下次记得")
                || text.contains("以后默认")
                || text.contains("保存到长期记忆")
                || text.contains("保存这个偏好");
    }

    private static String sensitivity(String text) {
        if (ID_CARD.matcher(text).find() || TOKEN.matcher(text).find()) {
            return "high";
        }
        if (BANK_CARD.matcher(text).find()
                || text.contains("身份证")
                || text.contains("银行卡")
                || text.contains("密码")
                || text.contains("住址")
                || text.contains("收货地址")
                || text.contains("手机号")
                || text.contains("电话")
                || text.contains("病历")
                || text.contains("诊断")) {
            return "medium";
        }
        return "low";
    }

    private static String memoryType(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (text.contains("项目") || lower.contains("mvn ") || lower.contains("gradle ")
                || lower.contains("npm ") || lower.contains("pnpm ") || lower.contains("pytest")) {
            return "project";
        }
        if (text.contains("偏好") || text.contains("默认") || text.contains("喜欢")
                || text.contains("习惯") || text.contains("用中文") || text.contains("短句")
                || text.contains("优先")) {
            return "preference";
        }
        if (text.contains("我是") || text.contains("我在") || text.contains("我的")) {
            return "profile";
        }
        return "fact";
    }

    private static double stability(String text, String memoryType) {
        if (containsAny(text, "今天", "刚刚", "这次", "临时", "暂时", "现在先")) {
            return 0.15;
        }
        return switch (memoryType) {
            case "preference", "project", "profile" -> 0.9;
            default -> 0.55;
        };
    }

    private static double futureUtility(String text, String memoryType) {
        if (containsAny(text, "天气", "地铁", "吃了", "高考", "朋友")) {
            return 0.15;
        }
        if ("preference".equals(memoryType) || "project".equals(memoryType)) {
            return 0.9;
        }
        if ("profile".equals(memoryType)) {
            return 0.7;
        }
        return 0.45;
    }

    private static double specificity(String text) {
        if (text.length() < 6) {
            return 0.2;
        }
        if (containsAny(text, "默认", "使用", "命令", "路径", "偏好", "语言", "测试", "优先")) {
            return 0.9;
        }
        return 0.55;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').trim().replaceAll("\\s+", " ");
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public enum Action {
        SAVE,
        CONFIRM,
        SKIP
    }

    public record Decision(Action action, double score, String reason, Map<String, String> metadata) {
        public static Decision save(double score, String source, String memoryType, String sensitivity) {
            return new Decision(Action.SAVE, score, "满足长期记忆自动保存阈值",
                    metadata(score, source, memoryType, sensitivity));
        }

        public static Decision confirm(double score, String reason, String source, String memoryType, String sensitivity) {
            return new Decision(Action.CONFIRM, score, reason, metadata(score, source, memoryType, sensitivity));
        }

        public static Decision skip(double score, String reason) {
            return new Decision(Action.SKIP, score, reason,
                    metadata(score, "policy", "fact", "low"));
        }

        private static Map<String, String> metadata(double score, String source, String memoryType, String sensitivity) {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("source", source);
            metadata.put("memory_type", memoryType);
            metadata.put("sensitivity", sensitivity);
            metadata.put("score", String.format(Locale.ROOT, "%.3f", score));
            return Map.copyOf(metadata);
        }
    }
}
