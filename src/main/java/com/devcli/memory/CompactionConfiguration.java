package com.devcli.memory;

import java.util.Locale;

/** 现有压缩开关的唯一解析入口。 */
public final class CompactionConfiguration {
    private CompactionConfiguration() {}
    public static final String COMPACTION_ENABLED_PROPERTY = "devcli.context.compaction.enabled";
    public static final String COMPACTION_ENABLED_ENV = "DEVCLI_CONTEXT_COMPACTION_ENABLED";
    public static boolean isCompactionEnabled(java.util.Properties properties,
                                              java.util.Map<String, String> environment) {
        String configured = properties == null ? null : properties.getProperty(COMPACTION_ENABLED_PROPERTY);
        if ((configured == null || configured.isBlank()) && environment != null) {
            configured = environment.get(COMPACTION_ENABLED_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return true;
        }
        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)
                || "on".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)
                || "off".equals(normalized)) {
            return false;
        }
        throw new IllegalArgumentException(COMPACTION_ENABLED_PROPERTY
                + " must be true|false, got: " + configured);
    }
}
