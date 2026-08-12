package com.devcli.observability;

import java.util.Map;

/** 低基数指标出口；失败不得改变业务终态。 */
@FunctionalInterface
public interface MetricRecorder {
    MetricRecorder NO_OP = (name, value, unit, attributes) -> { };

    void record(String name, double value, String unit, Map<String, String> attributes);

    default void increment(String name, Map<String, String> attributes) {
        record(name, 1D, "count", attributes);
    }

    static MetricRecorder safe(MetricRecorder delegate) {
        if (delegate == null || delegate == NO_OP) return NO_OP;
        return (name, value, unit, attributes) -> {
            try {
                delegate.record(name, value, unit, attributes);
            } catch (RuntimeException ignored) {
                // 可观测性故障不能改变业务终态。
            }
        };
    }
}
