package com.devcli.tool;

import com.devcli.policy.CommandGuard;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 工具调用的语义与业务前置校验。
 *
 * <p>Schema 只保证 JSON 形状正确，本类负责参数组合、项目资源状态和明显策略语义。
 * Provider 仍需在真正执行前再次校验可变状态，避免校验与执行之间的竞态。</p>
 */
public final class ToolSemanticValidator {
    private static final Set<String> SEARCH_MODES = Set.of(
            "auto", "general", "call_chain", "definition", "error_trace", "config");

    private ToolSemanticValidator() {
    }

    @FunctionalInterface
    public interface CustomRule {
        ValidationResult validate(JsonNode arguments);
    }

    public record Context(Path projectRoot,
                          Function<String, Path> safePathResolver,
                          Function<String, Boolean> writePathAllowed,
                          CustomRule customRule,
                          boolean applyBuiltInRules) {
        public Context(Path projectRoot,
                       Function<String, Path> safePathResolver,
                       Function<String, Boolean> writePathAllowed,
                       CustomRule customRule) {
            this(projectRoot, safePathResolver, writePathAllowed, customRule, true);
        }

        public Context {
            Path effectiveRoot = projectRoot == null ? Path.of(".").toAbsolutePath().normalize()
                    : projectRoot.toAbsolutePath().normalize();
            projectRoot = effectiveRoot;
            safePathResolver = safePathResolver == null ? ignored -> effectiveRoot : safePathResolver;
            writePathAllowed = writePathAllowed == null ? ignored -> true : writePathAllowed;
        }
    }

    public record ValidationResult(boolean valid,
                                   ToolErrorCode errorCode,
                                   String message,
                                   boolean retryable) {
        public static ValidationResult ok() {
            return new ValidationResult(true, ToolErrorCode.NONE, "", false);
        }

        public static ValidationResult semantic(String message) {
            return new ValidationResult(false, ToolErrorCode.SEMANTIC_VALIDATION_FAILED,
                    message == null ? "业务规则校验失败" : message, true);
        }

        public static ValidationResult arguments(String message) {
            return new ValidationResult(false, ToolErrorCode.INVALID_ARGUMENTS,
                    message == null ? "参数无效" : message, true);
        }

        public static ValidationResult policy(String message) {
            return new ValidationResult(false, ToolErrorCode.POLICY_DENIED,
                    message == null ? "策略拒绝" : message, false);
        }
    }

    public static ValidationResult validate(String name, JsonNode arguments, Context context) {
        if (context != null && context.customRule() != null) {
            try {
                ValidationResult custom = context.customRule().validate(arguments);
                if (custom != null && !custom.valid()) {
                    return custom;
                }
            } catch (RuntimeException e) {
                return semantic("自定义业务校验失败: " + safeMessage(e));
            }
        }
        if (name == null || name.isBlank() || arguments == null || !arguments.isObject()) {
            return semantic("工具参数必须是 JSON 对象");
        }
        Context effective = context == null ? new Context(null, null, null, null, true) : context;
        if (!effective.applyBuiltInRules()) {
            return ValidationResult.ok();
        }
        return switch (name) {
            case "read_file" -> validateReadFile(arguments, effective);
            case "read_tool_result" -> validateReadToolResult(arguments);
            case "write_file" -> validateWriteFile(arguments, effective);
            case "edit_file" -> validateEditFile(arguments, effective);
            case "list_dir" -> validateDirectory(arguments, effective, "path");
            case "grep_code" -> validateGrep(arguments, effective);
            case "create_project" -> validateCreateProject(arguments, effective);
            case "revert_turn" -> validateOptionalInteger(arguments, "offset", 1, Integer.MAX_VALUE);
            case "execute_command" -> validateCommand(arguments);
            case "search_code" -> validateSearchCode(arguments);
            case "web_search" -> validateWebSearch(arguments);
            case "web_fetch" -> validateWebFetch(arguments);
            case "load_skill" -> validateLoadSkill(arguments);
            case "list_memory" -> validatePositiveInteger(arguments, "limit", 1, 10_000);
            case "save_memory" -> requireText(arguments, "fact", "fact 不能为空");
            case "confirm_memory" -> validateConfirmMemory(arguments);
            default -> ValidationResult.ok();
        };
    }

    private static ValidationResult validateReadFile(JsonNode args, Context context) {
        ValidationResult path = validatePath(args, "path", context, false, true);
        if (!path.valid()) {
            return path;
        }
        ValidationResult range = validateOptionalInteger(args, "start_line", 1, Integer.MAX_VALUE);
        if (!range.valid()) {
            return range;
        }
        range = validateOptionalInteger(args, "end_line", 1, Integer.MAX_VALUE);
        if (!range.valid()) {
            return range;
        }
        range = validateOptionalInteger(args, "offset", 0, Long.MAX_VALUE);
        if (!range.valid()) {
            return range;
        }
        range = validateOptionalInteger(args, "limit", 1, 4_000);
        if (!range.valid()) {
            return range;
        }
        long start = args.path("start_line").asLong(-1);
        long end = args.path("end_line").asLong(-1);
        if (start >= 0 && end >= 0 && end < start) {
            return semantic("read_file 的 end_line 不能小于 start_line");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateReadToolResult(JsonNode args) {
        ValidationResult required = requireText(args, "result_ref", "result_ref 不能为空");
        if (!required.valid()) {
            return required;
        }
        required = validateOptionalInteger(args, "offset", 0, Long.MAX_VALUE);
        if (!required.valid()) {
            return required;
        }
        return validateOptionalInteger(args, "limit", 1, ToolResultArtifactStore.MAX_PAGE_CHARS);
    }

    private static ValidationResult validateWriteFile(JsonNode args, Context context) {
        ValidationResult result = validatePath(args, "path", context, true, false);
        if (!result.valid()) {
            return result;
        }
        return requireTextAllowingEmpty(args, "content", "content 必须是字符串");
    }

    private static ValidationResult validateEditFile(JsonNode args, Context context) {
        ValidationResult result = validatePath(args, "path", context, true, true);
        if (!result.valid()) {
            return result;
        }
        result = requireText(args, "old_string", "old_string 不能为空");
        if (!result.valid()) {
            return result;
        }
        JsonNode path = args.get("path");
        try {
            Path safe = context.safePathResolver().apply(path.asText());
            if (!Files.isRegularFile(safe)) {
                return semantic("edit_file 目标必须是已存在的普通文件: " + path.asText());
            }
        } catch (RuntimeException e) {
            return policy("路径策略拒绝: " + safeMessage(e));
        }
        return requireTextAllowingEmpty(args, "new_string", "new_string 必须是字符串");
    }

    private static ValidationResult validateDirectory(JsonNode args, Context context,
                                                      String field) {
        ValidationResult result = validatePath(args, field, context, false, true);
        if (!result.valid()) {
            return result;
        }
        try {
            Path safe = context.safePathResolver().apply(args.path(field).asText());
            return Files.isDirectory(safe)
                    ? ValidationResult.ok()
                    : semantic(field + " 必须指向已存在的目录");
        } catch (RuntimeException e) {
            return policy("路径策略拒绝: " + safeMessage(e));
        }
    }

    private static ValidationResult validateGrep(JsonNode args, Context context) {
        ValidationResult result = requireText(args, "pattern", "pattern 不能为空");
        if (!result.valid()) {
            return result;
        }
        if (args.has("path")) {
            result = validateDirectoryOrFile(args, context, "path");
            if (!result.valid()) {
                return result;
            }
        }
        result = validateOptionalInteger(args, "limit", 1, 500);
        if (!result.valid()) {
            return result;
        }
        boolean regex = args.path("regex").asBoolean(true);
        if (regex) {
            try {
                Pattern.compile(args.path("pattern").asText());
            } catch (PatternSyntaxException e) {
                return semantic("grep_code 的正则表达式无效: " + e.getDescription());
            }
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateDirectoryOrFile(JsonNode args, Context context,
                                                             String field) {
        ValidationResult result = validatePath(args, field, context, false, true);
        if (!result.valid()) {
            return result;
        }
        try {
            Path safe = context.safePathResolver().apply(args.path(field).asText());
            return Files.exists(safe)
                    ? ValidationResult.ok()
                    : semantic(field + " 必须指向已存在的文件或目录");
        } catch (RuntimeException e) {
            return policy("路径策略拒绝: " + safeMessage(e));
        }
    }

    private static ValidationResult validateCreateProject(JsonNode args, Context context) {
        ValidationResult result = requireText(args, "name", "项目名称不能为空");
        if (!result.valid()) {
            return result;
        }
        result = requireText(args, "type", "项目类型不能为空");
        if (!result.valid()) {
            return result;
        }
        String type = args.path("type").asText().toLowerCase(Locale.ROOT);
        if (!Set.of("java", "python", "node").contains(type)) {
            return semantic("不支持的项目类型: " + args.path("type").asText());
        }
        try {
            Path target = context.safePathResolver().apply(args.path("name").asText());
            if (target.equals(context.projectRoot())) {
                return semantic("create_project 不能把项目根目录作为新项目目录");
            }
        } catch (RuntimeException e) {
            return policy("路径策略拒绝: " + safeMessage(e));
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateCommand(JsonNode args) {
        ValidationResult result = requireText(args, "command", "命令不能为空");
        if (!result.valid()) {
            return result;
        }
        String denyReason = CommandGuard.check(args.path("command").asText());
        return denyReason == null ? ValidationResult.ok() : policy(denyReason);
    }

    private static ValidationResult validateSearchCode(JsonNode args) {
        ValidationResult result = requireText(args, "query", "query 不能为空");
        if (!result.valid()) {
            return result;
        }
        result = validateOptionalInteger(args, "top_k", 1, 30);
        if (!result.valid()) {
            return result;
        }
        String mode = args.path("mode").asText("");
        if (!mode.isBlank() && !SEARCH_MODES.contains(mode)) {
            return semantic("search_code.mode 不支持: " + mode
                    + "，可选值: " + String.join(", ", SEARCH_MODES));
        }
        return validateOptionalInteger(args, "graph_depth", 0, 3);
    }

    private static ValidationResult validateWebSearch(JsonNode args) {
        ValidationResult result = requireText(args, "query", "搜索关键词不能为空");
        if (!result.valid()) {
            return result;
        }
        return validateOptionalInteger(args, "top_k", 1, 100);
    }

    private static ValidationResult validateWebFetch(JsonNode args) {
        ValidationResult result = requireText(args, "url", "URL 不能为空");
        if (!result.valid()) {
            return result;
        }
        try {
            URI uri = new URI(args.path("url").asText().trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http")
                    || scheme.equalsIgnoreCase("https")) || uri.getHost() == null) {
                return semantic("URL 必须是包含主机名的 http/https 地址");
            }
        } catch (URISyntaxException e) {
            return semantic("URL 格式无效: " + e.getMessage());
        }
        return validateOptionalInteger(args, "max_chars", 1, 1_000_000);
    }

    private static ValidationResult validateLoadSkill(JsonNode args) {
        ValidationResult result = requireText(args, "name", "name 不能为空");
        if (!result.valid()) {
            return result;
        }
        result = validateOptionalInteger(args, "page", 1, Integer.MAX_VALUE);
        if (!result.valid()) {
            return result;
        }
        String reference = args.path("reference").asText("");
        if (reference.startsWith("/") || reference.startsWith("\\") || reference.contains("..")) {
            return policy("Skill reference 路径不能是绝对路径或包含 ..");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateConfirmMemory(JsonNode args) {
        ValidationResult result = requireText(args, "confirmation_id", "confirmation_id 不能为空");
        if (!result.valid()) {
            return result;
        }
        result = requireText(args, "action", "action 不能为空");
        if (!result.valid()) {
            return result;
        }
        String action = args.path("action").asText();
        if ("save_edited".equals(action)) {
            return requireText(args, "edited_fact", "action=save_edited 时 edited_fact 不能为空");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validatePath(JsonNode args, String field, Context context,
                                                 boolean write, boolean mustExist) {
        ValidationResult result = requireText(args, field, field + " 不能为空");
        if (!result.valid()) {
            return result;
        }
        String raw = args.path(field).asText();
        if (write && !Boolean.TRUE.equals(context.writePathAllowed().apply(raw))) {
            return new ValidationResult(false, ToolErrorCode.CAPABILITY_DENIED,
                    "委派 Worker 写路径不在允许范围内: " + raw, false);
        }
        try {
            Path safe = context.safePathResolver().apply(raw);
            if (mustExist && !Files.exists(safe)) {
                return semantic(field + " 必须指向已存在的路径: " + raw);
            }
            return ValidationResult.ok();
        } catch (RuntimeException e) {
            return policy("路径策略拒绝: " + safeMessage(e));
        }
    }

    private static ValidationResult requireText(JsonNode args, String field, String message) {
        JsonNode value = args.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? ValidationResult.ok() : argument(message);
    }

    private static ValidationResult requireTextAllowingEmpty(JsonNode args, String field,
                                                             String message) {
        JsonNode value = args.get(field);
        return value != null && value.isTextual() ? ValidationResult.ok() : argument(message);
    }

    private static ValidationResult validateOptionalInteger(JsonNode args, String field,
                                                            long minimum, long maximum) {
        if (!args.has(field)) {
            return ValidationResult.ok();
        }
        JsonNode value = args.get(field);
        if (!value.isIntegralNumber()) {
            return argument(field + " 必须是整数");
        }
        long actual = value.asLong();
        return actual >= minimum && actual <= maximum
                ? ValidationResult.ok()
                : semantic(field + " 必须位于 [" + minimum + ", " + maximum + "]");
    }

    private static ValidationResult validatePositiveInteger(JsonNode args, String field,
                                                            long minimum, long maximum) {
        return validateOptionalInteger(args, field, minimum, maximum);
    }

    private static ValidationResult semantic(String message) {
        return ValidationResult.semantic(message);
    }

    private static ValidationResult argument(String message) {
        return ValidationResult.arguments(message);
    }

    private static ValidationResult policy(String message) {
        return ValidationResult.policy(message);
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
