package com.devcli.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveDataRedactorTest {

    @Test
    void redactsCredentialsAccountsAndPersonalIdentifiers() {
        String original = "token=tok-123 password=pw account=alice 身份证号 110101199003071234 "
                + "\"home address\":\"No. 1 Main Road\" \"medical diagnosis\":\"asthma\"";

        String redacted = SensitiveDataRedactor.redact(original);

        assertFalse(redacted.contains("tok-123"));
        assertFalse(redacted.contains("pw"));
        assertFalse(redacted.contains("alice"));
        assertFalse(redacted.contains("110101199003071234"));
        assertFalse(redacted.contains("No. 1 Main Road"));
        assertFalse(redacted.contains("asthma"));
        assertTrue(redacted.contains("token="));
        assertTrue(redacted.contains("account="));
    }
}
