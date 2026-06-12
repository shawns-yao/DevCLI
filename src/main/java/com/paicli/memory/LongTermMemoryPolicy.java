package com.paicli.memory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 长期记忆写入决策器。
 *
 * <p>目标是高精度拦截：显式、稳定、低敏的信息才自动保存；敏感或模糊的新事实交给上层确认；
 * 低价值临时信息只留在 WorkingMemory。这里故意不用未校准的加权分数，所有决策都落到可解释的
 * reason_code，方便测试、审计和后续接入 LLM Judge。
 */
public final class LongTermMemoryPolicy {
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
            return Decision.skip("空事实不保存", "EMPTY_FACT", "fact", "low", "LOW");
        }

        boolean explicit = explicitOverride || hasExplicitRememberIntent(text);
        String sensitivity = sensitivity(text);
        String memoryType = memoryType(text);

        if ("high".equals(sensitivity)) {
            return Decision.confirm("包含高敏感信息，必须用户确认",
                    source(explicit, recurrenceCount), memoryType, sensitivity,
                    "SENSITIVE_REQUIRES_CONFIRMATION", "HIGH");
        }
        if ("medium".equals(sensitivity)) {
            return Decision.confirm("包含敏感个人信息，必须用户确认",
                    source(explicit, recurrenceCount), memoryType, sensitivity,
                    "SENSITIVE_REQUIRES_CONFIRMATION", "HIGH");
        }

        if (explicit) {
            return Decision.save("explicit", memoryType, sensitivity,
                    "EXPLICIT_STABLE_MEMORY", "HIGH");
        }
        if (isLowReuseThirdPartyFact(text)) {
            return Decision.skip("第三方一次性事件不进入长期记忆",
                    "LOW_REUSE_VALUE", memoryType, sensitivity, "LOW");
        }
        if (isPersonalAttribute(text)) {
            return Decision.save("heuristic", "profile", sensitivity,
                    "PROFILE_ATTRIBUTE", "HIGH");
        }
        if (isNovelProfileFact(text)) {
            return Decision.confirm("新的个人状态事实需要用户确认",
                    source(explicit, recurrenceCount), "profile", sensitivity,
                    "NOVEL_PROFILE_FACT_REQUIRES_CONFIRMATION", "MEDIUM");
        }
        if (isTemporary(text)) {
            return Decision.skip("临时、低复用或低置信信息不进入长期记忆",
                    "TEMPORARY_LOW_VALUE", memoryType, sensitivity, "LOW");
        }

        if (recurrenceCount >= 3 && isCoreMemoryType(memoryType)) {
            return Decision.save("recurrence", memoryType, sensitivity,
                    "REPEATED_STABLE_MEMORY", "HIGH");
        }
        if (isCoreMemoryType(memoryType) && isSpecific(text)) {
            return Decision.confirm("项目或偏好事实较具体，但缺少显式保存意图",
                    source(false, recurrenceCount), memoryType, sensitivity,
                    "AMBIGUOUS_STABLE_FACT_REQUIRES_CONFIRMATION", "MEDIUM");
        }

        return Decision.skip("低复用信息不进入长期记忆",
                "LOW_REUSE_VALUE", memoryType, sensitivity, "LOW");
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
        if (isPersonalAttribute(text) || isNovelProfileFact(text)
                || (text.contains("我是") || text.contains("我在") || text.contains("我的"))
                && !isLowReuseThirdPartyFact(text)) {
            return "profile";
        }
        return "fact";
    }

    private static boolean isTemporary(String text) {
        return containsAny(text, "今天", "刚才", "刚刚", "这次", "临时", "暂时", "现在先", "先不用管");
    }

    private static boolean isPersonalAttribute(String text) {
        return text.matches(".*(我是|我是一名|我的职业是|我从事|我在做|我负责).{0,24}(医生|老师|教师|律师|学生|工程师|程序员|开发|产品经理|设计师|运维|测试|研究员).*");
    }

    private static boolean isNovelProfileFact(String text) {
        return containsAny(text, "搬到", "迁到", "入职", "离职", "转行", "换工作", "换城市", "定居")
                && containsAny(text, "我", "我的");
    }

    private static boolean isLowReuseThirdPartyFact(String text) {
        return containsAny(text, "朋友", "同学", "同事", "孩子", "高考");
    }

    private static boolean isCoreMemoryType(String memoryType) {
        return "preference".equals(memoryType) || "project".equals(memoryType);
    }

    private static boolean isSpecific(String text) {
        return text.length() >= 6
                && containsAny(text, "默认", "使用", "命令", "路径", "偏好", "语言", "测试", "优先", "版本");
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

    public enum Action {
        SAVE,
        CONFIRM,
        SKIP
    }

    public record Decision(Action action, String reason, Map<String, String> metadata) {
        public static Decision save(String source, String memoryType, String sensitivity,
                                    String reasonCode, String confidence) {
            return new Decision(Action.SAVE, "满足长期记忆保存规则",
                    metadata(source, memoryType, sensitivity, reasonCode, confidence));
        }

        public static Decision confirm(String reason, String source, String memoryType, String sensitivity,
                                       String reasonCode, String confidence) {
            return new Decision(Action.CONFIRM, reason,
                    metadata(source, memoryType, sensitivity, reasonCode, confidence));
        }

        public static Decision skip(String reason, String reasonCode, String memoryType,
                                    String sensitivity, String confidence) {
            return new Decision(Action.SKIP, reason,
                    metadata("policy", memoryType, sensitivity, reasonCode, confidence));
        }

        private static Map<String, String> metadata(String source, String memoryType, String sensitivity,
                                                    String reasonCode, String confidence) {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("source", source);
            metadata.put("memory_type", memoryType);
            metadata.put("sensitivity", sensitivity);
            metadata.put("reason_code", reasonCode);
            metadata.put("confidence", confidence);
            return Map.copyOf(metadata);
        }
    }
}
