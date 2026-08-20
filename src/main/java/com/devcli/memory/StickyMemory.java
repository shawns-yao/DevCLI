package com.devcli.memory;

import java.nio.file.Path;
import java.util.List;

/**
 * @deprecated 兼容旧命令和扩展的迁移适配器。强约束使用 {@link RuleContext}，
 * 稳定事实使用 {@link LongTermMemory}。
 */
@Deprecated
public class StickyMemory extends RuleContext {
    public static final int MAX_STICKY_TOKENS = MAX_RULE_TOKENS;

    public StickyMemory(Path memoryDir) { super(memoryDir); }

    public PinnedFact pin(String content, String source) {
        Rule rule = addRule(content, source);
        return new PinnedFact(rule.id, rule.content, rule.source, rule.addedAt);
    }

    public boolean unpin(String factId) { return removeRule(factId); }

    public List<PinnedFact> listPinned() {
        return listRules().stream()
                .map(rule -> new PinnedFact(rule.id, rule.content, rule.source, rule.addedAt))
                .toList();
    }

    public static final class PinnedFact {
        public final String id;
        public final String content;
        public final String source;
        public final long addedAt;

        PinnedFact(String id, String content, String source, long addedAt) {
            this.id = id;
            this.content = content;
            this.source = source;
            this.addedAt = addedAt;
        }
    }
}
