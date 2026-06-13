package com.devcli.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LongTermMemoryPolicyTest {

    @Test
    void explicitLowRiskRememberInstructionShouldBeSavedAutomatically() {
        LongTermMemoryPolicy.Decision decision =
                LongTermMemoryPolicy.evaluate("请记住：我默认使用简体中文和短句回答", 0);

        assertEquals(LongTermMemoryPolicy.Action.SAVE, decision.action());
        assertEquals("preference", decision.metadata().get("memory_type"));
        assertEquals("explicit", decision.metadata().get("source"));
        assertEquals("EXPLICIT_STABLE_MEMORY", decision.metadata().get("reason_code"));
        assertEquals("HIGH", decision.metadata().get("confidence"));
        assertFalse(decision.metadata().containsKey("score"));
    }

    @Test
    void explicitLowRiskRememberInstructionShouldWinOverLowReuseHeuristics() {
        LongTermMemoryPolicy.Decision decision =
                LongTermMemoryPolicy.evaluate("请记住：我朋友的孩子今天高考", 0);

        assertEquals(LongTermMemoryPolicy.Action.SAVE, decision.action());
        assertEquals("explicit", decision.metadata().get("source"));
        assertEquals("EXPLICIT_STABLE_MEMORY", decision.metadata().get("reason_code"));
    }

    @Test
    void casualTemporaryMessageShouldStayShortTermOnly() {
        LongTermMemoryPolicy.Decision decision =
                LongTermMemoryPolicy.evaluate("今天地铁好挤，天气也不错", 0);

        assertEquals(LongTermMemoryPolicy.Action.SKIP, decision.action());
        assertEquals("TEMPORARY_LOW_VALUE", decision.metadata().get("reason_code"));
    }

    @Test
    void sensitiveFactRequiresConfirmationEvenWhenExplicit() {
        LongTermMemoryPolicy.Decision decision =
                LongTermMemoryPolicy.evaluate("记住我的身份证号是 110101199003071234", 0);

        assertEquals(LongTermMemoryPolicy.Action.CONFIRM, decision.action());
        assertEquals("high", decision.metadata().get("sensitivity"));
        assertEquals("SENSITIVE_REQUIRES_CONFIRMATION", decision.metadata().get("reason_code"));
    }

    @Test
    void repeatedStableProjectFactCanBeSavedWithoutExplicitRememberIntent() {
        LongTermMemoryPolicy.Decision decision =
                LongTermMemoryPolicy.evaluate("项目默认测试命令是 mvn test -Pquick", 3);

        assertEquals(LongTermMemoryPolicy.Action.SAVE, decision.action());
        assertEquals("project", decision.metadata().get("memory_type"));
        assertEquals("recurrence", decision.metadata().get("source"));
        assertEquals("REPEATED_STABLE_MEMORY", decision.metadata().get("reason_code"));
    }

    @Test
    void personalProfileAttributeShouldBeSavedAsNewStableFact() {
        LongTermMemoryPolicy.Decision decision =
                LongTermMemoryPolicy.evaluate("我是医生", 0);

        assertEquals(LongTermMemoryPolicy.Action.SAVE, decision.action());
        assertEquals("profile", decision.metadata().get("memory_type"));
        assertEquals("PROFILE_ATTRIBUTE", decision.metadata().get("reason_code"));
    }

    @Test
    void novelPersonalLifeEventShouldRequireConfirmationInsteadOfAutomaticSave() {
        LongTermMemoryPolicy.Decision decision =
                LongTermMemoryPolicy.evaluate("我刚刚搬到北京", 0);

        assertEquals(LongTermMemoryPolicy.Action.CONFIRM, decision.action());
        assertEquals("profile", decision.metadata().get("memory_type"));
        assertEquals("NOVEL_PROFILE_FACT_REQUIRES_CONFIRMATION", decision.metadata().get("reason_code"));
    }

    @Test
    void unrelatedFriendLifeEventShouldBeSkipped() {
        LongTermMemoryPolicy.Decision decision =
                LongTermMemoryPolicy.evaluate("我朋友的孩子今天高考", 0);

        assertEquals(LongTermMemoryPolicy.Action.SKIP, decision.action());
        assertEquals("LOW_REUSE_VALUE", decision.metadata().get("reason_code"));
    }
}
