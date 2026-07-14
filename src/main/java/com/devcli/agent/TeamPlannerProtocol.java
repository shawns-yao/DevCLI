package com.devcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Multi-Agent Planner 输出协议边界。
 *
 * 负责 JSON 提取、计划结构语义校验、修复提示构建和修复次数配置，
 * 避免把模型协议治理继续堆叠到编排器调度逻辑中。
 */
final class TeamPlannerProtocol {
    private static final Logger log = LoggerFactory.getLogger(TeamPlannerProtocol.class);

    static final int DEFAULT_REPAIR_ATTEMPTS = 2;
    static final int MAX_REPAIR_ATTEMPTS = 3;

    private TeamPlannerProtocol() {
    }

    static int resolveRepairAttempts() {
        String raw = System.getProperty("devcli.team.planner.repair.max.attempts");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("DEVCLI_TEAM_PLANNER_REPAIR_MAX_ATTEMPTS");
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_REPAIR_ATTEMPTS;
        }
        try {
            int attempts = Integer.parseInt(raw.trim());
            int clamped = Math.max(0, Math.min(MAX_REPAIR_ATTEMPTS, attempts));
            if (clamped != attempts) {
                log.warn("devcli.team.planner.repair.max.attempts={} 超出范围 [0,{}]，已夹取为 {}",
                        attempts, MAX_REPAIR_ATTEMPTS, clamped);
            }
            return clamped;
        } catch (NumberFormatException e) {
            log.warn("非法 devcli.team.planner.repair.max.attempts={}，使用默认 {}",
                    raw, DEFAULT_REPAIR_ATTEMPTS);
            return DEFAULT_REPAIR_ATTEMPTS;
        }
    }

    static JsonNode parsePlanRoot(ObjectMapper mapper, String cleaned) throws IOException {
        Objects.requireNonNull(mapper, "mapper");
        if (cleaned == null || cleaned.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(cleaned);
        } catch (IOException directFailure) {
            log.debug("Planner output is not a standalone JSON document; scanning balanced objects");
        }

        for (int start = 0; start < cleaned.length(); start++) {
            if (cleaned.charAt(start) != '{') {
                continue;
            }
            String candidate = extractBalancedJsonObject(cleaned, start);
            if (candidate == null) {
                continue;
            }
            try {
                JsonNode root = mapper.readTree(candidate);
                JsonNode steps = root.path("steps");
                JsonNode tasks = root.path("tasks");
                if ((steps.isArray() && !steps.isEmpty()) || (tasks.isArray() && !tasks.isEmpty())) {
                    return root;
                }
            } catch (IOException ignored) {
                // 当前平衡对象不是合法 JSON，继续查找后续对象。
            }
        }
        return null;
    }

    static <T> String validate(List<T> steps,
                               String userInput,
                               Function<T, String> idExtractor,
                               Function<T, String> descriptionExtractor,
                               Function<T, List<String>> dependencyExtractor) {
        if (steps == null || steps.isEmpty()) {
            return "上次规划输出无法解析为包含 steps 的 JSON 计划";
        }
        Set<String> blockingDependencies = steps.stream()
                .flatMap(step -> dependencyExtractor.apply(step).stream())
                .collect(Collectors.toSet());
        String normalizedUserInput = Objects.toString(userInput, "").toLowerCase(Locale.ROOT);
        boolean userRequestsDelivery = containsAny(normalizedUserInput,
                "实现", "创建", "编写", "修改", "修复", "生成", "初始化", "开发", "新增", "添加", "重构",
                "implement", "create", "write", "modify", "fix", "generate", "initialize", "develop", "refactor");

        for (T step : steps) {
            String description = Objects.toString(descriptionExtractor.apply(step), "");
            String normalized = description.toLowerCase(Locale.ROOT);
            boolean discoveryAction = containsAny(normalized,
                    "检查", "确认", "查看", "读取", "列出", "扫描", "inspect", "check", "list");
            boolean workspaceExistenceTarget = containsAny(normalized,
                    "空工作区", "工作区", "项目结构", "目录是否", "目录存在", "文件是否存在",
                    "同名文件", "empty workspace", "project structure", "directory exists", "file exists");
            boolean deliveryAction = containsAny(normalized,
                    "实现", "创建", "编写", "修改", "修复", "生成", "初始化", "若不存在则创建",
                    "implement", "create", "write", "modify", "fix", "generate", "initialize");
            String stepId = Objects.toString(idExtractor.apply(step), "unknown");
            if (discoveryAction && workspaceExistenceTarget && !deliveryAction
                    && (userRequestsDelivery || blockingDependencies.contains(stepId))) {
                return "计划包含阻塞性纯检查步骤：" + stepId + " " + description
                        + "。空工作区检查必须并入实现步骤，不能阻塞后续实现";
            }
        }
        return null;
    }

    static String buildRepairPrompt(String userInput, String invalidOutput,
                                    String validationIssue, int attempt) {
        return """
                上次规划输出无法解析或未通过结构校验。请修复协议和计划结构，不要延续解释文本。

                校验失败原因：
                %s

                原始用户任务：
                %s

                上次无效输出（第 %d 次修复）：
                %s

                只输出一个 JSON 对象，禁止 Markdown 代码块、前置解释和后置说明。JSON 必须包含：
                {"summary":"...","acceptance_criteria":[],"steps":[{"id":"step_1","description":"...","type":"FILE_READ | FILE_WRITE | COMMAND | ANALYSIS | VERIFICATION","dependencies":[]}]}

                工作区可能为空；空工作区是合法状态。不要调用工具或声称先检查工作区。
                不要把 list_dir、检查目录、确认文件是否存在拆成阻塞性独立步骤。
                必要检查应并入首个实现步骤，并写明“若不存在则创建”。
                只输出 JSON。
                """.formatted(
                Objects.toString(validationIssue, "未知校验错误"),
                Objects.toString(userInput, ""),
                attempt,
                previewOutput(invalidOutput));
    }

    private static String extractBalancedJsonObject(String source, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
                if (depth < 0) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String previewOutput(String output) {
        if (output == null || output.isBlank()) {
            return "<empty>";
        }
        String normalized = output.trim();
        int limit = 2_000;
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
