package com.devcli.memory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 从事实文本中抽取主题键（subject），用于长期记忆的冲突消解。
 *
 * <p>设计取舍（第一版）：纯确定性规则，不依赖模型——可解释、零外部依赖、行为稳定。
 * 命中规则才返回非空 subject；返回空串表示"无法确定主题"，调用方应退回追加写入（不覆盖），
 * 符合"能确定冲突才覆盖"的保守策略。
 *
 * <p>已知局限：规则覆盖面有限，异词同题需靠词典枚举（如 JSON 库名）。词典未覆盖的同主题
 * 表达会漏判（退回追加），后续可接入语义判定（ConflictJudge）补强，不影响本类对外契约。
 */
public final class MemorySubjectExtractor {

    private MemorySubjectExtractor() {
    }

    /**
     * 库名 → 主题键。让"项目用 Fastjson"与"改用 Jackson"归并到同一主题，
     * 从而后写的覆盖先写的。需要新增同类库时在此登记。
     */
    private static final Map<String, String> LIBRARY_SUBJECTS = buildLibrarySubjects();

    private static Map<String, String> buildLibrarySubjects() {
        Map<String, String> map = new LinkedHashMap<>();
        // JSON 序列化库（典型场景：Fastjson → Jackson 的合规切换）
        map.put("fastjson", "project.json_library");
        map.put("jackson", "project.json_library");
        map.put("gson", "project.json_library");
        return map;
    }

    /**
     * 抽取主题键。优先级：metadata 显式 subject &gt; 库名词典 &gt; 测试命令 &gt; 响应偏好 &gt; 职业身份 &gt; 空。
     *
     * @param fact     事实文本
     * @param metadata 事实附带的元数据（可能含上游写入的显式 subject）
     * @return 主题键；无法确定时返回空串（调用方退回追加写入，不覆盖）
     */
    public static String extract(String fact, Map<String, String> metadata) {
        // 1. 显式 subject（来自 /save、save_memory 工具参数或上游 policy）最高优先
        if (metadata != null) {
            String explicit = metadata.get("subject");
            if (explicit != null && !explicit.isBlank()) {
                return explicit.trim();
            }
        }
        if (fact == null || fact.isBlank()) {
            return "";
        }
        String lower = fact.toLowerCase(Locale.ROOT);

        // 2. 库名词典：异词同题归并（Fastjson / Jackson / Gson → 同一 json_library 主题）
        for (Map.Entry<String, String> e : LIBRARY_SUBJECTS.entrySet()) {
            if (lower.contains(e.getKey())) {
                return e.getValue();
            }
        }

        // 3. 默认测试命令
        if (containsAny(lower, "mvn test", "gradle test", "npm test", "pnpm test", "pytest")
                || fact.contains("测试命令")
                || (fact.contains("默认") && fact.contains("测试"))) {
            return "project.default_test_command";
        }

        // 4. 响应风格偏好
        if (containsAny(fact, "用中文", "简洁", "短句", "输出风格", "回复风格")) {
            return "preference.response_style";
        }

        // 5. 职业 / 身份
        if (fact.matches(".*(我是|我的职业是|我从事|我负责).{0,24}(医生|老师|教师|律师|学生|工程师|程序员|开发|产品经理|设计师|运维|测试|研究员).*")) {
            return "profile.occupation";
        }

        // 6. 无法确定主题：退回追加，不覆盖
        return "";
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
