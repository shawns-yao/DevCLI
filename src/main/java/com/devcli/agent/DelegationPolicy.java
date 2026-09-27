package com.devcli.agent;

import com.devcli.config.ConfigResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Admission rules for model-proposed delegation.
 *
 * <p>本层只保留一条语义判据：模型自述的执行粒度必须是需要多轮循环。
 * 参数结构校验（输入、范围、完成条件、交付物、Worker 写入范围）已归入
 * 工具语义校验层，以参数错误而非策略拒绝返回，使模型能区分「参数写错了」
 * 与「这个任务不该委派」。详见 ADR 0010。
 *
 * <p>收益评估（{@link Yield}）仍然计算契约闭合、作用面隔离与传递成本，
 * 但只写入运行事件供事后诊断，不参与放行，也不回灌给模型：把分数告诉模型
 * 只会诱导它修改声明措辞来「过门槛」，而任务实质不变。详见 ADR 0009。
 */
public final class DelegationPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 准入判定结果；{@code allowed} 只反映「能不能干」。 */
    public record Decision(boolean allowed, String reason, Yield yield) {
        /**
         * 事件摘要。放行结论与收益诊断分开表述，避免把诊断读成拒绝理由。
         */
        public String summary() {
            return reason + " (yield=" + yield.score() + ", benefit=" + yield.benefit()
                    + ", coordination_cost=" + yield.coordinationCost() + ")";
        }
    }

    /**
     * 收益诊断，只用于观测委派决策质量。
     *
     * <p>{@code lowYield} 是告警标记，不改变 {@link Decision#allowed()}：
     * 低收益委派照常执行，抑制浪费由提示词软策略承担。
     */
    public record Yield(int benefit, int coordinationCost, int score, boolean lowYield, String advice) {
        static final Yield NOT_EVALUATED = new Yield(0, 0, 0, false, "未评估");

        static Yield of(Map<String, String> args, JsonNode spec, List<String> writePaths) {
            // 收益来自「任务被隔离得多干净」，而不是「输入文本有多长」。
            // 文本长度只计入传递成本，不作为状态耦合或隔离收益的证明。
            int benefit = 2 + writeScopeIsolation(writePaths);
            int cost = 1 + (args.getOrDefault("context", "").length() + spec.toString().length()
                    > 8000 ? 2 : 0) + (args.getOrDefault("upstream_report_id", "").isBlank() ? 0 : 1);
            int score = benefit - cost;
            int advisory = ConfigResolver.intValue("devcli.delegation.yield.advisory.threshold",
                    "DEVCLI_DELEGATION_YIELD_ADVISORY_THRESHOLD", 2, 1, 10);
            boolean lowYield = score < advisory;
            return new Yield(benefit, cost, score, lowYield,
                    lowYield
                            ? "本次委派收益偏低，建议由主 Agent 直接完成；该判断仅供参考，不影响本次放行"
                            : "委派收益正常");
        }
    }

    private DelegationPolicy() { }

    public static Decision evaluate(Map<String, String> args) {
        if (args == null) return deny("缺少任务包");
        JsonNode spec;
        try {
            spec = JSON.readTree(args.getOrDefault("task_spec", "{}"));
        } catch (Exception e) {
            return deny("task_spec 不是合法 JSON");
        }
        if (spec == null || !spec.isObject()) return deny("缺少结构化任务声明");
        // 自述式判据：模型自己声明该任务为单次工具操作时，说明无需开启子循环。
        // 它同时免疫误伤（不按文件数或输入长度推断）与刷分（改写成循环等于承诺多轮迭代）。
        if (!spec.path("execution_kind").asText().equals("agent_loop"))
            return deny("单次工具操作或未声明执行粒度，应由主 Agent 执行");
        List<String> writePaths = "worker".equals(args.get("role"))
                ? parseWritePaths(args) : List.of();
        return new Decision(true, "委派请求通过准入检查", Yield.of(args, spec, writePaths));
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
        return new Decision(false, reason, Yield.NOT_EVALUATED);
    }
}
