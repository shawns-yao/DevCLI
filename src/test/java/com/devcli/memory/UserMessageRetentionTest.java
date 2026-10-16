package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UserMessageRetentionTest {
    @Test void retainsAllUserSourcesInOrderWithoutInternalContext() {
        var users = List.of(LlmClient.Message.user("最早约束"), LlmClient.Message.steering("补充约束"), LlmClient.Message.followUp("下一项"));
        var history = new java.util.ArrayList<>(users);
        history.add(LlmClient.Message.internalUser("derived"));
        history.add(LlmClient.Message.user(List.of(LlmClient.ContentPart.text("tool image")), LlmClient.MessageSource.TOOL));
        history.add(LlmClient.Message.assistant("reasoning", "answer"));
        assertEquals(users, UserMessageRetention.select(history, 10_000));
    }

    @Test void keepsWholeRecentMessagesWithinActualBudget() {
        var history = List.of(LlmClient.Message.user("old"), LlmClient.Message.user("recent"));
        int recentTokens = TokenBudget.estimateMessagesTokens(List.of(history.getLast()));
        assertEquals(List.of(history.getLast()), UserMessageRetention.select(history, recentTokens));
        assertTrue(UserMessageRetention.select(history, recentTokens - 1).isEmpty());
    }

    @Test void retainsImageDescriptionAndPathWithoutKeepingPixels() {
        var image = LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png")
                .withImageReference(new LlmClient.ImageReference("archive/shot.img", 10, 20, "登录错误截图"));
        var retained = UserMessageRetention.select(List.of(LlmClient.Message.user(List.of(LlmClient.ContentPart.text("请修复"), image))), 1_000);
        assertFalse(retained.getFirst().hasImageContent());
        assertTrue(retained.getFirst().content().contains("archive/shot.img, 10x20, 登录错误截图"));
    }
}
