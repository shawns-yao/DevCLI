package com.paicli.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongTermMemoryPolicyTest {

    @Test
    void explicitPreferenceShouldBeSavedAutomatically() {
        LongTermMemoryPolicy.Decision decision =
                LongTermMemoryPolicy.evaluate("请记住：我默认使用简体中文和短句回答", 0);

        assertEquals(LongTermMemoryPolicy.Action.SAVE, decision.action());
        assertTrue(decision.score() >= 0.85, "显式低敏偏好应达到自动保存阈值: " + decision);
        assertEquals("preference", decision.metadata().get("memory_type"));
        assertEquals("explicit", decision.metadata().get("source"));
    }

    @Test
    void casualTemporaryMessageShouldStayShortTermOnly() {
        LongTermMemoryPolicy.Decision decision =
                LongTermMemoryPolicy.evaluate("今天地铁好挤，天气也不错", 0);

        assertEquals(LongTermMemoryPolicy.Action.SKIP, decision.action());
        assertTrue(decision.score() < 0.65, "一次性闲聊不应进入长期记忆: " + decision);
    }

    @Test
    void sensitiveFactRequiresConfirmationEvenWhenExplicit() {
        LongTermMemoryPolicy.Decision decision =
                LongTermMemoryPolicy.evaluate("记住我的身份证号是 110101199003071234", 0);

        assertEquals(LongTermMemoryPolicy.Action.CONFIRM, decision.action());
        assertEquals("high", decision.metadata().get("sensitivity"));
        assertTrue(decision.reason().contains("敏感"));
    }

    @Test
    void repeatedStableProjectFactCanBeSavedWithoutExplicitRememberIntent() {
        LongTermMemoryPolicy.Decision decision =
                LongTermMemoryPolicy.evaluate("项目默认测试命令是 mvn test -Pquick", 3);

        assertEquals(LongTermMemoryPolicy.Action.SAVE, decision.action());
        assertEquals("project", decision.metadata().get("memory_type"));
        assertEquals("recurrence", decision.metadata().get("source"));
    }
}
