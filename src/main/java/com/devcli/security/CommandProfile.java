package com.devcli.security;

import java.util.Locale;
import java.util.regex.Pattern;

/** 命令执行画像；默认全部使用禁网 Docker，宿主机画像必须显式配置。 */
public enum CommandProfile {
    MAVEN_COMPILE(true, 120, 2, 1024, 256, false,
            Pattern.compile("(?i)^mvn(?:\\.cmd)?\\s+(?=.*(?:^|\\s)(?:compile|test-compile|package)(?:\\s|$)).+$")),
    MAVEN_TEST(true, 300, 2, 1024, 256, false,
            Pattern.compile("(?i)^mvn(?:\\.cmd)?\\s+(?=.*(?:^|\\s)test(?:\\s|$)).+$")),
    READ_ONLY_SHELL(true, 60, 1, 512, 128, false,
            Pattern.compile("(?i)^(pwd|ls|dir|type|cat|git\\s+(status|diff|log|show)|rg|grep)\\b.*$")),
    PROJECT_BUILD(true, 300, 2, 1024, 256, false,
            Pattern.compile("(?i)^(mvn(?:\\.cmd)?|gradle(?:\\.bat)?|gradlew(?:\\.bat)?|npm(?:\\.cmd)?|pnpm(?:\\.cmd)?|yarn(?:\\.cmd)?)\\b.*$")),
    CUSTOM_SANDBOX(true, 120, 2, 1024, 256, false, Pattern.compile("(?s)^.+$")),
    TRUSTED_HOST(false, 60, 1, 512, 128, false, Pattern.compile("(?s)^.+$"));

    private final boolean sandboxRequired;
    private final long timeoutSeconds;
    private final int cpus;
    private final int memoryMb;
    private final int pidsLimit;
    private final boolean networkAllowed;
    private final Pattern commandPattern;

    CommandProfile(boolean sandboxRequired, long timeoutSeconds, int cpus,
                   int memoryMb, int pidsLimit, boolean networkAllowed,
                   Pattern commandPattern) {
        this.sandboxRequired = sandboxRequired;
        this.timeoutSeconds = timeoutSeconds;
        this.cpus = cpus;
        this.memoryMb = memoryMb;
        this.pidsLimit = pidsLimit;
        this.networkAllowed = networkAllowed;
        this.commandPattern = commandPattern;
    }

    public boolean sandboxRequired() { return sandboxRequired; }
    public long timeoutSeconds() { return timeoutSeconds; }
    public int cpus() { return cpus; }
    public int memoryMb() { return memoryMb; }
    public int pidsLimit() { return pidsLimit; }
    public boolean networkAllowed() { return networkAllowed; }

    public boolean accepts(String command) {
        return command != null && commandPattern.matcher(command.trim()).matches();
    }

    public static CommandProfile classify(String command) {
        String value = command == null ? "" : command.trim();
        for (CommandProfile profile : values()) {
            if (profile != TRUSTED_HOST && profile != CUSTOM_SANDBOX && profile.accepts(value)) {
                return profile;
            }
        }
        return CUSTOM_SANDBOX;
    }

    public static CommandProfile parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
