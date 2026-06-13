package com.devcli.prompt;

import java.util.LinkedHashMap;
import java.util.Map;

public record PromptContext(
        String approvalMode,
        String memoryContext,
        String externalContext,
        String stickyMemory,
        String workingMemory,
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
        private String stickyMemory = "";
        private String workingMemory = "";
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

        public Builder stickyMemory(String stickyMemory) {
            this.stickyMemory = normalize(stickyMemory);
            return this;
        }

        /**
         * 注入工作记忆（WorkingMemory 派生视图）。每轮 user 输入后由
         * {@code MemoryManager.buildWorkingMemorySection()} 渲染：含最近工具证据 / 任务状态 /
         * 临时事实。与 stickyMemory（稳定）区分：workingMemory 易变、当轮重新渲染。
         */
        public Builder workingMemory(String workingMemory) {
            this.workingMemory = normalize(workingMemory);
            return this;
        }

        public Builder variable(String key, Object value) {
            if (key != null && !key.isBlank() && value != null) {
                this.variables.put(key.trim(), String.valueOf(value));
            }
            return this;
        }

        public PromptContext build() {
            return new PromptContext(approvalMode, memoryContext, externalContext, stickyMemory, workingMemory, skillIndex, Map.copyOf(variables));
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
