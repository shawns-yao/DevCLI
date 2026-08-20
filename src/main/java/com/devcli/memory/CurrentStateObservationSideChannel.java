package com.devcli.memory;

import com.devcli.tool.ToolSideChannel;

/**
 * 工具对当前项目状态的强类型观察。只传递主题、规范值和证据，不进入面向模型的工具正文。
 */
public record CurrentStateObservationSideChannel(
        String subject,
        String value,
        String evidence,
        String confidence
) implements ToolSideChannel {
    public CurrentStateObservationSideChannel {
        subject = subject == null ? "" : subject.trim();
        value = value == null ? "" : value.trim();
        evidence = evidence == null ? "" : evidence.trim();
        confidence = confidence == null || confidence.isBlank() ? "HIGH" : confidence.trim();
        if (subject.isBlank() || value.isBlank() || evidence.isBlank()) {
            throw new IllegalArgumentException("current-state observation requires subject, value and evidence");
        }
    }
}
