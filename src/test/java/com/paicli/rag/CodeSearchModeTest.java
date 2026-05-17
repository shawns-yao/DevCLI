package com.paicli.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeSearchModeTest {

    @Test
    void invalidModeFallsBackToInferredIntent() {
        assertEquals(CodeSearchMode.CALL_CHAIN,
                CodeSearchMode.resolve("deep_graph_magic", "登录接口从 Controller 到 Mapper 的调用链"));
    }

    @Test
    void queryIntentOverridesConflictingLlmMode() {
        assertEquals(CodeSearchMode.DEFINITION,
                CodeSearchMode.resolve("call_chain", "UserService 在哪里定义"));
    }

    @Test
    void graphDepthIsClampedAndNarrowedByMode() {
        assertEquals(3, CodeSearchOptions.resolve("call_chain", "Controller 到 Mapper", 99).graphDepth());
        assertEquals(0, CodeSearchOptions.resolve("definition", "UserService 在哪里定义", 3).graphDepth());
        assertEquals(1, CodeSearchOptions.resolve("general", "登录实现", 3).graphDepth());
    }
}
