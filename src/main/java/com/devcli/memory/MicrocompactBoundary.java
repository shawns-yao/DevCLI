package com.devcli.memory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code <microcompact_boundary>} 协议的唯一判定与解析入口。
 *
 * <p>生产侧见 {@link ConversationHistoryCompactor}，消费侧见 {@link SessionMemory}。
 * 两侧必须共用同一判定：只按字符串包含判断，会把"正文里提到该标记"误当成"已折叠引用"，
 * 消费侧据此丢弃真实内容，而生产侧据此跳过淘汰，同一误判在两个方向上后果相反。
 *
 * <p>判定要求标记位于内容起始、闭合标记存在且携带协议字段。压缩器产出的折叠引用必然满足
 * 这三条；工具结果正文偶然出现该字符串则不满足。
 */
final class MicrocompactBoundary {

    static final String OPEN = "<microcompact_boundary>";
    static final String CLOSE = "</microcompact_boundary>";
    /** 折叠引用必须携带的类型字段；缺失说明这不是压缩器产出的边界。 */
    static final String TYPE_TOOL_RESULT = "type=tool_result";

    private static final Pattern TOOL_CALL_ID = Pattern.compile("(?m)^toolCallId=(.+)$");
    private static final Pattern ORIGINAL_CHARS = Pattern.compile("(?m)^originalChars=(.+)$");
    private static final Pattern STORED_PATH = Pattern.compile("(?m)^storedPath=(.+)$");

    private MicrocompactBoundary() {
    }

    /**
     * 判定内容是否为压缩器产出的折叠引用。
     *
     * @param content 工具结果或消息正文
     * @return 仅当内容以标记起始、存在闭合标记且带 {@code type=tool_result} 时为 true
     */
    static boolean isBoundary(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String leading = content.stripLeading();
        if (!leading.startsWith(OPEN)) {
            return false;
        }
        int close = leading.indexOf(CLOSE);
        if (close < 0) {
            return false;
        }
        return leading.substring(0, close).contains(TYPE_TOOL_RESULT);
    }

    static String toolCallId(String content) {
        return extract(TOOL_CALL_ID, content);
    }

    static String originalChars(String content) {
        return extract(ORIGINAL_CHARS, content);
    }

    static String storedPath(String content) {
        return extract(STORED_PATH, content);
    }

    private static String extract(Pattern pattern, String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : "";
    }
}
