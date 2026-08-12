package com.devcli.security;

import com.devcli.tool.ToolRegistry;
import com.devcli.hitl.ApprovalPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionSecurityPolicyTest {
    @Test
    void standardProfileSandboxesCommandsAndRejectsHostOverride() {
        ExecutionSecurityPolicy policy = new ExecutionSecurityPolicy(SecurityProfile.STANDARD);

        var sandbox = policy.decide(ToolRegistry.ToolEffect.HOST_PROCESS,
                ToolRegistry.ToolAccessScope.FULL, CommandProfile.MAVEN_TEST);
        var host = policy.decide(ToolRegistry.ToolEffect.HOST_PROCESS,
                ToolRegistry.ToolAccessScope.FULL, CommandProfile.TRUSTED_HOST);

        assertTrue(sandbox.allowed());
        assertTrue(sandbox.sandboxRequired());
        assertEquals(ExecutionSecurityPolicy.Domain.SANDBOX_COMMAND, sandbox.domain());
        assertFalse(host.allowed());
        assertEquals(ExecutionSecurityPolicy.Domain.DENIED, host.domain());
    }

    @Test
    void accessScopeDenialPrecedesApproval() {
        ExecutionSecurityPolicy policy = new ExecutionSecurityPolicy(SecurityProfile.STANDARD);
        var denied = policy.decide(ToolRegistry.ToolEffect.EXTERNAL_MUTATION,
                ToolRegistry.ToolAccessScope.READ_ONLY, null);

        assertFalse(denied.allowed());
        assertFalse(denied.approvalRequired());
        assertFalse(ApprovalPolicy.canRequestApproval(denied));
    }
}
