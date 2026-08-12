package com.devcli.security;

/** 一次执行允许使用的安全边界。 */
public enum SecurityProfile {
    STANDARD(true, false),
    TRUSTED_LOCAL(true, true),
    UNTRUSTED_PROJECT(false, false);

    private final boolean projectResourcesAllowed;
    private final boolean hostCommandsAllowed;

    SecurityProfile(boolean projectResourcesAllowed, boolean hostCommandsAllowed) {
        this.projectResourcesAllowed = projectResourcesAllowed;
        this.hostCommandsAllowed = hostCommandsAllowed;
    }

    public boolean projectResourcesAllowed() {
        return projectResourcesAllowed;
    }

    public boolean hostCommandsAllowed() {
        return hostCommandsAllowed;
    }

    public static SecurityProfile fromConfiguration() {
        String configured = System.getProperty("devcli.security.profile");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("DEVCLI_SECURITY_PROFILE");
        }
        if (configured == null || configured.isBlank()) return STANDARD;
        try {
            return valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ignored) {
            return STANDARD;
        }
    }
}
