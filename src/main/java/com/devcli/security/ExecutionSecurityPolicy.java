package com.devcli.security;

import com.devcli.tool.ToolRegistry;

import java.util.Objects;

/** 所有执行模式共用的副作用安全决策。 */
public final class ExecutionSecurityPolicy {
    public enum Domain {
        READ_ONLY_HOST("host-readonly"),
        PROJECT_PATCH("project-patch"),
        SANDBOX_COMMAND("sandboxed"),
        EXTERNAL_MUTATION("external-approved"),
        DENIED("denied");

        private final String label;

        Domain(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Decision(Domain domain, boolean allowed, boolean approvalRequired,
                           boolean sandboxRequired, String reason) {
        public Decision {
            domain = Objects.requireNonNullElse(domain, Domain.DENIED);
            reason = reason == null ? "" : reason;
        }
    }

    private final SecurityProfile profile;

    public ExecutionSecurityPolicy(SecurityProfile profile) {
        this.profile = Objects.requireNonNullElse(profile, SecurityProfile.STANDARD);
    }

    public SecurityProfile profile() { return profile; }

    public Decision decide(ToolRegistry.ToolEffect effect,
                           ToolRegistry.ToolAccessScope scope,
                           CommandProfile requestedCommandProfile) {
        ToolRegistry.ToolEffect normalized = Objects.requireNonNullElse(
                effect, ToolRegistry.ToolEffect.EXTERNAL_MUTATION);
        ToolRegistry.ToolAccessScope access = Objects.requireNonNullElse(
                scope, ToolRegistry.ToolAccessScope.FULL);
        if (!access.permits(normalized)) {
            return denied("tool_access_scope_denied");
        }
        return switch (normalized) {
            case READ_ONLY, LOCAL_CONTEXT ->
                    new Decision(Domain.READ_ONLY_HOST, true, false, false, "read_only");
            case PROJECT_MUTATION ->
                    new Decision(Domain.PROJECT_PATCH, true, true, false, "recoverable_project_write");
            case HOST_PROCESS -> commandDecision(requestedCommandProfile);
            case EXTERNAL_MUTATION ->
                    new Decision(Domain.EXTERNAL_MUTATION, true, true, false, "per_call_approval_required");
        };
    }

    private Decision commandDecision(CommandProfile requested) {
        CommandProfile profileToUse = requested == null ? CommandProfile.CUSTOM_SANDBOX : requested;
        if (profileToUse == CommandProfile.TRUSTED_HOST && !profile.hostCommandsAllowed()) {
            return denied("trusted_host_profile_not_enabled");
        }
        boolean sandbox = profileToUse != CommandProfile.TRUSTED_HOST;
        return new Decision(sandbox ? Domain.SANDBOX_COMMAND : Domain.READ_ONLY_HOST,
                true, true, sandbox, profileToUse.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static Decision denied(String reason) {
        return new Decision(Domain.DENIED, false, false, false, reason);
    }
}
