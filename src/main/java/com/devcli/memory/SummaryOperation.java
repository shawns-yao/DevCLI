package com.devcli.memory;

import java.util.List;

/** LLM 只能提出受限的摘要变更操作，实际状态迁移由程序执行。 */
record SummaryOperation(
        Action action,
        String section,
        String targetSection,
        String subject,
        String content,
        SummaryItem.Lifecycle lifecycle,
        int importance,
        List<String> evidenceRefs) {

    enum Action { ADD, UPDATE, RESOLVE, SUPERSEDE, EXPIRE, DELETE }
}
