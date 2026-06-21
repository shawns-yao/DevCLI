package com.devcli.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单 Agent 实例的 skill 注入缓冲区。
 *
 * 生命周期：LLM 调 load_skill → push 到 buffer → 下一轮构造 user message 时 drain → 拼到原内容前。
 *
 * 关键约束：
 * - drain 是一次性消费（防止跨轮重复注入）
 * - allowedTools 属于已加载 Skill 的运行时权限状态，不随 drain 清空，只随 clear 复位
 * - 同一会话内最多保留 3 个 skill body（上限 3 个，超出 LRU 淘汰最旧）
 * - 同一 skill 重复 push 会替换旧 body 并刷新到末尾，避免重复
 * - /clear 命令调 clear() 复位
 *
 * 三个 SubAgent 角色（Planner / Worker / Reviewer）+ 主 Agent 各持一个独立实例，
 * 不共享 buffer，避免角色间提示词污染。
 */
public final class SkillContextBuffer {

    private static final int MAX_SKILLS = 3;

    private final Map<String, String> entries = new LinkedHashMap<>();
    private final LinkedHashSet<String> activeSkillNames = new LinkedHashSet<>();
    private final Map<String, List<String>> activeAllowedToolsBySkill = new LinkedHashMap<>();
    private final Map<String, Skill.Context> activeContextBySkill = new LinkedHashMap<>();
    private final Map<String, String> activeBodyBySkill = new LinkedHashMap<>();

    public synchronized void push(String skillName, String body) {
        push(skillName, body, List.of());
    }

    public synchronized void push(String skillName, String body, List<String> allowedTools) {
        push(skillName, body, allowedTools, Skill.Context.INLINE);
    }

    public synchronized void push(String skillName, String body, List<String> allowedTools, Skill.Context context) {
        if (skillName == null || skillName.isBlank() || body == null) {
            return;
        }
        entries.remove(skillName);
        entries.put(skillName, body);
        activeSkillNames.remove(skillName);
        activeSkillNames.add(skillName);
        activeContextBySkill.put(skillName, context == null ? Skill.Context.INLINE : context);
        activeBodyBySkill.put(skillName, body);
        List<String> normalizedAllowedTools = normalizeAllowedTools(allowedTools);
        if (normalizedAllowedTools.isEmpty()) {
            activeAllowedToolsBySkill.remove(skillName);
        } else {
            activeAllowedToolsBySkill.put(skillName, normalizedAllowedTools);
        }
        while (entries.size() > MAX_SKILLS) {
            String oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
            activeAllowedToolsBySkill.remove(oldest);
            activeContextBySkill.remove(oldest);
            activeBodyBySkill.remove(oldest);
            activeSkillNames.remove(oldest);
        }
        while (activeSkillNames.size() > MAX_SKILLS) {
            String oldest = activeSkillNames.iterator().next();
            activeSkillNames.remove(oldest);
            activeAllowedToolsBySkill.remove(oldest);
            activeContextBySkill.remove(oldest);
            activeBodyBySkill.remove(oldest);
        }
        while (activeAllowedToolsBySkill.size() > MAX_SKILLS) {
            String oldest = activeAllowedToolsBySkill.keySet().iterator().next();
            activeAllowedToolsBySkill.remove(oldest);
        }
    }

    /**
     * 取出全部已积累 skill body 并清空。返回拼接好的 markdown 段，可直接前置到 user message。
     */
    public synchronized String drain() {
        if (entries.isEmpty()) {
            return "";
        }
        List<Map.Entry<String, String>> snapshot = new ArrayList<>(entries.entrySet());
        entries.clear();
        return format(snapshot);
    }

    /**
     * 返回当前 skill body 的只读快照，不清空 buffer。
     *
     * Forked SubAgent 并行执行会使用这个方法把同一轮已加载 skill 固化进 fork 后缀，
     * 避免多个 Worker 并发 drain 同一个 buffer，导致只有第一个 Worker 看到 skill 内容。
     */
    public synchronized String snapshot() {
        if (entries.isEmpty()) {
            return "";
        }
        return format(new ArrayList<>(entries.entrySet()));
    }

    private static String format(List<Map.Entry<String, String>> snapshot) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : snapshot) {
            sb.append("## 已加载 Skill：").append(e.getKey()).append('\n')
                    .append(e.getValue().trim()).append('\n')
                    .append('\n');
        }
        sb.append("---\n");
        return sb.toString();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized Set<String> activeAllowedTools() {
        LinkedHashSet<String> allowedTools = new LinkedHashSet<>();
        for (List<String> tools : activeAllowedToolsBySkill.values()) {
            allowedTools.addAll(tools);
        }
        return Collections.unmodifiableSet(allowedTools);
    }

    public synchronized List<String> activeSkillNames() {
        return List.copyOf(activeSkillNames);
    }

    public synchronized String renderPostCompactRestoreSection() {
        if (activeSkillNames.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("### 已加载 Skill\n\n");
        for (String skillName : activeSkillNames) {
            sb.append("- ").append(skillName);
            Skill.Context context = activeContextBySkill.getOrDefault(skillName, Skill.Context.INLINE);
            sb.append(" | context: ").append(context.wireName());
            List<String> allowedTools = activeAllowedToolsBySkill.get(skillName);
            if (allowedTools == null || allowedTools.isEmpty()) {
                sb.append(" | 允许工具: 不限制");
            } else {
                sb.append(" | 允许工具: ").append(String.join(", ", allowedTools));
            }
            String body = activeBodyBySkill.get(skillName);
            if (body != null && !body.isBlank()) {
                sb.append(" | 内容摘要: ").append(compactBody(body));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    public synchronized void clear() {
        entries.clear();
        activeSkillNames.clear();
        activeAllowedToolsBySkill.clear();
        activeContextBySkill.clear();
        activeBodyBySkill.clear();
    }

    public synchronized SkillContextBuffer copy() {
        SkillContextBuffer copy = new SkillContextBuffer();
        copy.entries.putAll(this.entries);
        copy.activeSkillNames.addAll(this.activeSkillNames);
        copy.activeAllowedToolsBySkill.putAll(this.activeAllowedToolsBySkill);
        copy.activeContextBySkill.putAll(this.activeContextBySkill);
        copy.activeBodyBySkill.putAll(this.activeBodyBySkill);
        return copy;
    }

    private static List<String> normalizeAllowedTools(List<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tool : allowedTools) {
            if (tool != null && !tool.isBlank()) {
                normalized.add(tool.trim());
            }
        }
        return List.copyOf(normalized);
    }

    private static String compactBody(String body) {
        String compacted = body.replaceAll("\\s+", " ").trim();
        return compacted.length() > 240 ? compacted.substring(0, 237) + "..." : compacted;
    }
}
