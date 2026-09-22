package com.devcli.memory;

import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

/**
 * 记忆新鲜度标注。
 *
 * <p>长期记忆是「某个时间点的观察」，不是实时状态。模型看到一个没有时间戳的事实，
 * 会默认它现在仍然成立；看到「这条是 40 天前的」，才会去核实。
 * 本类只做一件事：把主题文件的 {@code updated_at} 换算成年龄，并生成一段提醒模型核实的标注。
 *
 * <p>注意：新鲜度取自 frontmatter 的 {@code updated_at}，因此任何一次写入都会把年龄归零。
 * 这不是缺陷也不是「有效期」——它是「这条记忆多久没被改过」的诚实读数，
 * 绝不参与打分、过滤或淘汰。旧版把新鲜度做成衰减因子乘进相关性分数，
 * 等于藏了一个用户看不见的 TTL，本类刻意不再提供任何权重接口。
 */
public final class MemoryFreshness {

    /** 年龄不超过该天数时不加标注（当天写入的记忆标年龄只会制造噪声）。 */
    public static final long SILENT_AGE_DAYS = 1;

    private MemoryFreshness() {
    }

    /**
     * 年龄（天），向下取整，永不为负。
     *
     * @param instant 记忆的 {@code updated_at}；为 {@code null} 时返回 0
     * @param clock   当前时间来源；为 {@code null} 时退回系统时钟
     */
    public static long ageDays(Instant instant, Clock clock) {
        if (instant == null) return 0;
        Instant now = (clock == null ? Clock.systemUTC() : clock).instant();
        long days = ChronoUnit.DAYS.between(instant, now);
        return Math.max(0, days);
    }

    /**
     * 生成新鲜度标注；年龄不超过 {@link #SILENT_AGE_DAYS} 天时返回空串。
     */
    public static String freshnessText(long ageDays) {
        if (ageDays <= SILENT_AGE_DAYS) return "";
        return "该记忆已存在 " + ageDays + " 天。记忆是某个时间点的观察，不是实时状态——"
                + "关于代码行为或 file:line 的断言可能已经过时，作为事实陈述前请对照当前代码核实。";
    }
}
