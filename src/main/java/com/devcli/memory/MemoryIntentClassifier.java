package com.devcli.memory;

import java.util.Locale;

/** 统一识别长期记忆的查看、写入、删除、忽略和历史依赖意图。 */
public final class MemoryIntentClassifier {
    private MemoryIntentClassifier() {
    }

    public static Intent classify(String text) {
        if (text == null || text.isBlank()) {
            return Intent.NONE;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAny(text, "忘记记忆", "别管记忆", "不要使用记忆", "忽略记忆")
                || containsAny(lower, "ignore memory", "forget memory")) {
            return Intent.IGNORE;
        }
        if (containsAny(text, "删除记忆", "清空长期记忆", "忘掉这条", "不要再记得")
                || containsAny(lower, "delete memory", "clear memory", "remove memory")) {
            return Intent.DELETE;
        }
        if (containsAny(text, "有什么长期记忆", "有哪些长期记忆", "查看长期记忆", "列出长期记忆",
                "长期记忆列表", "审计长期记忆", "记住了什么", "记得哪些内容", "以前记住的内容")
                || containsAny(lower, "list memory", "memory list", "what do you remember", "show memory")) {
            return Intent.INVENTORY;
        }
        if (containsAny(text, "记一下", "记住", "记下来", "以后记得", "下次记得", "保存这个偏好", "保存到长期记忆")
                || containsAny(lower, "remember this", "save to memory")) {
            return Intent.SAVE;
        }
        if (containsAny(text, "之前说过", "以前提到", "上次说", "还记得", "根据我的偏好", "按照我的习惯")
                || containsAny(lower, "as i said before", "previously mentioned", "based on my preference")) {
            return Intent.HISTORY_DEPENDENT;
        }
        return Intent.NONE;
    }

    public static boolean hasSaveIntent(String text) {
        return classify(text) == Intent.SAVE;
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    public enum Intent {
        NONE,
        SAVE,
        DELETE,
        IGNORE,
        INVENTORY,
        HISTORY_DEPENDENT
    }
}
