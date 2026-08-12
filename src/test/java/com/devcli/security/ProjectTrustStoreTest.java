package com.devcli.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectTrustStoreTest {
    @Test
    void nonInteractiveUnknownProjectDefaultsToUntrusted(@TempDir Path tempDir) {
        ProjectTrustStore store = new ProjectTrustStore(tempDir.resolve("trust.json"));
        assertEquals(ProjectTrustStore.Trust.UNTRUSTED,
                store.resolve(tempDir.resolve("project"), false));
    }

    @Test
    void persistsExplicitTrustDecision(@TempDir Path tempDir) throws Exception {
        ProjectTrustStore store = new ProjectTrustStore(tempDir.resolve("trust.json"));
        Path project = tempDir.resolve("project");
        store.set(project, ProjectTrustStore.Trust.TRUSTED);

        assertEquals(ProjectTrustStore.Trust.TRUSTED, store.resolve(project, false));
    }

    @Test
    void interactiveUnknownProjectRemainsUnknownUntilUserDecides(@TempDir Path tempDir) {
        ProjectTrustStore store = new ProjectTrustStore(tempDir.resolve("trust.json"));
        assertEquals(ProjectTrustStore.Trust.UNKNOWN,
                store.resolve(tempDir.resolve("project"), true));
    }
}
