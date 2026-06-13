package com.devcli.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolVersionTest {

    @Test
    void sameSymbolContentAndClasspathEpochProduceStableVersion() {
        SymbolVersion v1 = SymbolVersion.from("User.java", "method", "User.getName()", "String getName() {}", "epoch-1");
        SymbolVersion v2 = SymbolVersion.from("User.java", "method", "User.getName()", "String getName() {}", "epoch-1");

        assertEquals(v1, v2);
        assertTrue(v1.value().startsWith("sv_"));
    }

    @Test
    void contentChangeChangesVersion() {
        SymbolVersion v1 = SymbolVersion.from("User.java", "method", "User.getName()", "String getName() {}", "epoch-1");
        SymbolVersion v2 = SymbolVersion.from("User.java", "method", "User.getName()", "String name() {}", "epoch-1");

        assertNotEquals(v1, v2);
    }

    @Test
    void classpathEpochChangeChangesVersion() {
        SymbolVersion v1 = SymbolVersion.from("User.java", "method", "User.getName()", "String getName() {}", "epoch-1");
        SymbolVersion v2 = SymbolVersion.from("User.java", "method", "User.getName()", "String getName() {}", "epoch-2");

        assertNotEquals(v1, v2);
    }
}
