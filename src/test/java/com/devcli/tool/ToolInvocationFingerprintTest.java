package com.devcli.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ToolInvocationFingerprintTest {

    @Test
    void preservesWhitespaceInOpaqueFileContent() {
        String compact = ToolInvocationFingerprint.of(
                "write_file", "{\"path\":\"a.txt\",\"content\":\"a b\"}");
        String spaced = ToolInvocationFingerprint.of(
                "write_file", "{\"content\":\"a  b\",\"path\":\"a.txt\"}");

        assertNotEquals(compact, spaced);
    }

    @Test
    void preservesWhitespaceInRegularExpressionPatterns() {
        String compact = ToolInvocationFingerprint.of(
                "grep_code", "{\"pattern\":\"a b\",\"regex\":true}");
        String spaced = ToolInvocationFingerprint.of(
                "grep_code", "{\"regex\":true,\"pattern\":\"a  b\"}");

        assertNotEquals(compact, spaced);
    }

    @Test
    void normalizesSearchQueriesAndJsonFieldOrder() {
        String first = ToolInvocationFingerprint.of(
                "search_code", "{\"query\":\"  User   Service \" ,\"top_k\":5}");
        String second = ToolInvocationFingerprint.of(
                "SEARCH_CODE", "{\"top_k\":5,\"query\":\"user service\"}");

        assertEquals(first, second);
    }

    @Test
    void preservesCorrectiveQuotingInCommandArguments() {
        String unquoted = ToolInvocationFingerprint.of(
                "execute_command",
                "{\"command\":\"mvn -Dmaven.compiler.release=8 compile\"}");
        String quoted = ToolInvocationFingerprint.of(
                "execute_command",
                "{\"command\":\"mvn \\\"-Dmaven.compiler.release=8\\\" compile\"}");

        assertNotEquals(unquoted, quoted);
    }
}
