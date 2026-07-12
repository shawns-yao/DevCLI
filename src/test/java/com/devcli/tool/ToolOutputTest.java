package com.devcli.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolOutputTest {

    @Test
    void textFactoryCreatesStructuredSuccess() {
        ToolOutput output = ToolOutput.text("ok");

        assertEquals(ToolStatus.SUCCESS, output.status());
        assertEquals(ToolErrorCode.NONE, output.errorCode());
        assertFalse(output.retryable());
        assertTrue(output.isSuccess());
    }

    @Test
    void errorFactoryPreservesMachineReadableFailure() {
        ToolOutput output = ToolOutput.error(
                ToolErrorCode.EXECUTION_FAILED,
                "failed",
                true);

        assertEquals(ToolStatus.ERROR, output.status());
        assertEquals(ToolErrorCode.EXECUTION_FAILED, output.errorCode());
        assertTrue(output.retryable());
        assertFalse(output.isSuccess());
    }
}
