package com.devcli.workspace;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspacePathPolicyTest {

    @Test
    void keepsLegitimateSourceFilesWhoseNamesDescribeSecurityConcepts() {
        assertFalse(WorkspacePathPolicy.isSensitiveFile(Path.of("src/SecretService.java")));
        assertFalse(WorkspacePathPolicy.isSensitiveFile(Path.of("src/CredentialProvider.java")));
        assertFalse(WorkspacePathPolicy.isSensitiveFile(Path.of("docs/secrets-management.md")));
    }

    @Test
    void excludesExplicitCredentialFilePatterns() {
        assertTrue(WorkspacePathPolicy.isSensitiveFile(Path.of(".env")));
        assertTrue(WorkspacePathPolicy.isSensitiveFile(Path.of(".env.local")));
        assertTrue(WorkspacePathPolicy.isSensitiveFile(Path.of("credentials.json")));
        assertTrue(WorkspacePathPolicy.isSensitiveFile(Path.of("service-account.json")));
        assertTrue(WorkspacePathPolicy.isSensitiveFile(Path.of("deploy/private.pem")));
        assertTrue(WorkspacePathPolicy.isSensitiveFile(Path.of("deploy/signing.key")));
    }
}
