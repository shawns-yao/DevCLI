package com.devcli.prompt;

import java.util.LinkedHashMap;
import java.util.Map;

public record PromptContext(
        String approvalMode,
        String memoryContext,
        String externalContext,
        String ruleContext,
        String sessionMemory,
        String skillIndex,
        Map<String, String> variables
) {
    public static Builder builder() {
        return new Builder();
    }

    public static PromptContext empty() {
        return builder().build();
    }

    public String variable(String key) {
        if (variables == null || key == null) {
            return "";
        }
        return variables.getOrDefault(key, "");
    }

    public static final class Builder {
        private String approvalMode = "suggest";
        private String memoryContext = "";
        private String externalContext = "";
        private String ruleContext = "";
        private String sessionMemory = "";
        private String skillIndex = "";
        private final Map<String, String> variables = new LinkedHashMap<>();

        public Builder approvalMode(String approvalMode) {
            if (approvalMode != null && !approvalMode.isBlank()) {
                this.approvalMode = approvalMode.trim();
            }
            return this;
        }

        public Builder memoryContext(String memoryContext) {
            this.memoryContext = normalize(memoryContext);
            return this;
        }

        public Builder externalContext(String externalContext) {
            this.externalContext = normalize(externalContext);
            return this;
        }

        public Builder skillIndex(String skillIndex) {
            this.skillIndex = normalize(skillIndex);
            return this;
        }

        public Builder ruleContext(String ruleContext) {
            this.ruleContext = normalize(ruleContext);
            return this;
        }

        /** @deprecated 使用 {@link #ruleContext(String)}。 */
        @Deprecated
        public Builder stickyMemory(String value) { return ruleContext(value); }

        /**
         * 注入当前任务的会话记忆派生视图。
         */
        public Builder sessionMemory(String sessionMemory) {
            this.sessionMemory = normalize(sessionMemory);
            return this;
        }

        /** @deprecated 使用 {@link #sessionMemory(String)}。 */
        @Deprecated
        public Builder workingMemory(String value) { return sessionMemory(value); }

        public Builder variable(String key, Object value) {
            if (key != null && !key.isBlank() && value != null) {
                this.variables.put(key.trim(), String.valueOf(value));
            }
            return this;
        }

        public PromptContext build() {
            return new PromptContext(approvalMode, memoryContext, externalContext, ruleContext,
                    sessionMemory, skillIndex, Map.copyOf(variables));
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
