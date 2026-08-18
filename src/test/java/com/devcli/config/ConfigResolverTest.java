package com.devcli.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigResolverTest {
    private static final String PROPERTY = "devcli.test.config.value";
    private static final String ENVIRONMENT = "DEVCLI_TEST_CONFIG_VALUE";

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void trimsExplicitPropertyAndUsesFallbackWhenAbsent() {
        assertEquals("fallback", ConfigResolver.stringValue(
                PROPERTY, ENVIRONMENT, "fallback"));

        System.setProperty(PROPERTY, "  configured  ");

        assertEquals("configured", ConfigResolver.optional(PROPERTY, ENVIRONMENT));
    }

    @Test
    void parsesSupportedBooleanValuesAndRejectsUnknownValue() {
        System.setProperty(PROPERTY, "yes");
        assertEquals(true, ConfigResolver.booleanValue(
                PROPERTY, ENVIRONMENT, false));

        System.setProperty(PROPERTY, "disabled");
        assertThrows(IllegalArgumentException.class, () ->
                ConfigResolver.booleanValue(PROPERTY, ENVIRONMENT, false));
    }

    @Test
    void rejectsMalformedAndOutOfRangeNumbers() {
        System.setProperty(PROPERTY, "4");
        assertEquals(4, ConfigResolver.intValue(
                PROPERTY, ENVIRONMENT, 2, 1, 8));

        System.setProperty(PROPERTY, "9");
        assertThrows(IllegalArgumentException.class, () ->
                ConfigResolver.intValue(PROPERTY, ENVIRONMENT, 2, 1, 8));

        System.setProperty(PROPERTY, "not-a-number");
        assertThrows(IllegalArgumentException.class, () ->
                ConfigResolver.longValue(PROPERTY, ENVIRONMENT, 2L, 1L, 8L));
    }
}
