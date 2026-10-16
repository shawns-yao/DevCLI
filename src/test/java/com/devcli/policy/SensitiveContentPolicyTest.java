package com.devcli.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SensitiveContentPolicyTest {
    @Test
    void ordinarySourceIdentifiersAreNotCredentials() {
        String source = """
                CancellationToken token = runContext.cancellationToken();
                String token = existingToken;
                int key = terminal.reader().read();
                if (key == 27 || token == null) { return; }
                String key = "submit";
                String token = "@image:<" + grab.path().toAbsolutePath() + "> ";
                """;
        assertFalse(SensitiveContentPolicy.inspect(source).sensitive());
        assertEquals(source, SensitiveContentPolicy.inspect(source).sanitized());
    }

    @Test
    void concatenatedSecretsAndRecognizableKeysInExpressionsRemainProtected() {
        var result = SensitiveContentPolicy.inspect("String token = \"first-secret\" + \"second-secret\";");
        assertTrue(result.sensitive());
        assertFalse(result.sanitized().contains("first-secret"));
        assertFalse(result.sanitized().contains("second-secret"));
        assertTrue(SensitiveContentPolicy.inspect(
                "String token = \"sk-testcredential01234567890123456789\" + suffix;").sensitive());
    }

    @Test
    void truncatedSearchSnippetsDoNotTurnExpressionsIntoCredentials() {
        for (String snippet : new String[]{
                "src/Main.java:42: CancellationToken token = runContext.cancellationToken()...",
                "   String password = configuration.getPassword()...",
                "17\tString username = user.getName();",
                "const token = context.token\n",
                "src/Main.java:42: if (token == null) return;"}) {
            assertFalse(SensitiveContentPolicy.inspect(snippet).sensitive(), snippet);
        }
    }

    @Test
    void credentialLiteralsAndHeadersStillRequireReview() {
        for (String text : new String[]{
                "String token = \"literal-secret\";", "password=local-secret",
                "{\"api_key\":\"json-secret\"}", "Authorization: Basic dXNlcjpwYXNz",
                "Authorization token=opaque-secret", "AWS_SECRET_ACCESS_KEY=opaque-secret",
                "Authorization: Bearer secret-token", "账号: alice", "手机号 13800138000"}) {
            var result = SensitiveContentPolicy.inspect(text);
            assertTrue(result.sensitive(), text);
            assertNotEquals(text, result.sanitized(), text);
        }
    }

    @Test
    void rangeInspectionRetainsFullCredentialSpanWithoutReturningItsValue() {
        String secret = "sk-testcredential01234567890123456789";
        String text = "// 中文\r\npassword=\"" + secret + "\"\r\nsafe\r\n";
        int start = text.indexOf(secret) + 5;
        var result = SensitiveContentPolicy.inspectRange(text, start, start + 6);
        assertTrue(result.sensitive());
        assertEquals("******", result.sanitized());
        assertTrue(result.findings().stream().anyMatch(f -> f.start() < start && f.end() > start + 6));
        assertFalse(result.findings().toString().contains(secret));
        int clean = text.indexOf("safe");
        assertFalse(SensitiveContentPolicy.inspectRange(text, clean, clean + 4).sensitive());
    }
}
