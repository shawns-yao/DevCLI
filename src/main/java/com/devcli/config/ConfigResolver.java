package com.devcli.config;

import java.util.Locale;

/**
 * 统一解析系统属性和环境变量。显式配置一旦存在就必须语义合法，避免静默回退。
 */
public final class ConfigResolver {
    private ConfigResolver() {
    }

    public static String optional(String property, String environment) {
        String value = System.getProperty(property);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        value = System.getenv(environment);
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static String stringValue(String property, String environment, String fallback) {
        String value = optional(property, environment);
        return value == null ? fallback : value;
    }

    public static boolean booleanValue(String property, String environment, boolean fallback) {
        String value = optional(property, environment);
        if (value == null) {
            return fallback;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> throw invalid(property, environment,
                    "必须为 true/false、1/0、yes/no 或 on/off", value);
        };
    }

    public static int intValue(String property, String environment, int fallback,
                               int minimum, int maximum) {
        String value = optional(property, environment);
        if (value == null) {
            return fallback;
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw invalid(property, environment, "必须为整数", value);
        }
        if (parsed < minimum || parsed > maximum) {
            throw invalid(property, environment,
                    "必须位于 [" + minimum + ", " + maximum + "]", value);
        }
        return parsed;
    }

    public static long longValue(String property, String environment, long fallback,
                                 long minimum, long maximum) {
        String value = optional(property, environment);
        if (value == null) {
            return fallback;
        }
        final long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalid(property, environment, "必须为整数", value);
        }
        if (parsed < minimum || parsed > maximum) {
            throw invalid(property, environment,
                    "必须位于 [" + minimum + ", " + maximum + "]", value);
        }
        return parsed;
    }

    private static IllegalArgumentException invalid(String property, String environment,
                                                    String requirement, String value) {
        return new IllegalArgumentException(
                "非法配置 " + property + "/" + environment + "=" + value + "，" + requirement);
    }
}
