package com.devcli.agent;

import com.devcli.config.ConfigResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Admission rules for model-proposed delegation, without keyword-based scoring. */
public final class DelegationPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();

    private DelegationPolicy() { }

    public record Decision(boolean allowed, int score, int benefit, int coordinationCost,
                           String reason, List<String> factors) {
        public String summary() {
            return reason + " (score=" + score + ", benefit=" + benefit
                    + ", coordination_cost=" + coordinationCost + ")";
        }
    }

    public static Decision evaluate(Map<String, String> args) {
        if (args == null) return deny("缺少任务包");
        JsonNode spec;
        try {
            spec = JSON.readTree(args.getOrDefault("task_spec", "{}"));
        } catch (Exception e) {
            return deny("task_spec 不是合法 JSON");
        }
        if (spec == null || !spec.isObject()) return deny("缺少结构化任务声明");
        if (!spec.path("execution_kind").asText().equals("agent_loop"))
            return deny("单次工具操作或未声明执行粒度，应由主 Agent 执行");
        if (!spec.path("parent_dependency").asText().equals("none"))
            return deny("任务依赖父 Agent 的中间状态");
        for (String field : List.of("inputs", "scope", "done_condition")) {
            if (!spec.path(field).isTextual() || spec.path(field).asText().isBlank())
                return deny("任务包缺少 " + field);
        }
        if (args.getOrDefault("deliverable", "").isBlank())
            return deny("缺少明确交付物");
        if ("worker".equals(args.get("role"))) {
            if (parseWritePaths(args).isEmpty()) return deny("Worker 必须声明写入范围");
        }
        // 收益来自「任务被隔离得多干净」，而不是「输入文本有多长」。
        // 文本长度只计入传递成本，不作为状态耦合或隔离收益的证明。
        int isolation = writeScopeIsolation("worker".equals(args.get("role"))
                ? parseWritePaths(args) : List.of());
        int benefit = 2 + isolation;
        int cost = 1 + (args.getOrDefault("context", "").length() + spec.toString().length()
                > 8000 ? 2 : 0) + (args.getOrDefault("upstream_report_id", "").isBlank() ? 0 : 1);
        int minimum = ConfigResolver.intValue("devcli.delegation.policy.min.score",
                "DEVCLI_DELEGATION_POLICY_MIN_SCORE", 2, 1, 10);
        return new Decision(benefit - cost >= minimum, benefit - cost, benefit, cost,
                benefit - cost >= minimum ? "任务契约通过委派准入" : "委派收益不足，应由主 Agent 执行",
                List.of("closed_contract", "write_scope_isolation", "transfer_cost"));
    }

    /**
     * 写入范围隔离度（0–2）。
     *
     * <p>判据是子任务声明的**作用面有多窄**：作用面窄意味着与并行任务的冲突概率低，
     * 委派出去后主 Agent 不必反复协调。这是作用面启发式，不由输入文本长度推断状态耦合。
     */
    private static int writeScopeIsolation(List<String> paths) {
        if (paths.isEmpty()) {
            // 非 Worker 角色不声明写入范围，此时无从判断，给中性值。
            return 1;
        }
        boolean allSpecific = paths.stream().allMatch(DelegationPolicy::looksLikeFile);
        boolean narrow = paths.size() <= 3;
        if (allSpecific && narrow) return 2;
        if (allSpecific || narrow) return 1;
        return 0;
    }

    /** 粗略判断路径是否指向具体文件（最后一段含扩展名）。不解析真实文件系统。 */
    private static boolean looksLikeFile(String path) {
        String normalized = path.replace('\\', '/');
        if (normalized.chars().anyMatch(c -> "*?[]{}".indexOf(c) >= 0)) return false;
        int slash = normalized.lastIndexOf('/');
        String name = slash < 0 ? normalized : normalized.substring(slash + 1);
        return name.contains(".");
    }

    private static List<String> parseWritePaths(Map<String, String> args) {
        try {
            JsonNode paths = JSON.readTree(args.getOrDefault("allowed_write_paths", "[]"));
            if (!paths.isArray()) return List.of();
            List<String> result = new java.util.ArrayList<>();
            for (JsonNode path : paths) {
                if (path.isTextual() && !path.asText().isBlank()) result.add(path.asText());
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Decision deny(String reason) {
        return new Decision(false, 0, 0, 0, reason, List.of());
    }
}
