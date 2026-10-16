package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SummaryImageReferenceTest {
    @Test void summaryInputIncludesCachedDescriptionAndRecoverableReference() {
        var image = LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png")
                .withImageReference(new LlmClient.ImageReference("archive/shot.img", 640, 480, "页面显示登录失败"));
        var message = LlmClient.Message.user(List.of(LlmClient.ContentPart.text("请处理这个错误"), image));
        String rendered = new SummaryMessageRenderer().renderMessageForSummary(message);
        assertTrue(rendered.contains("archive/shot.img, 640x480, 页面显示登录失败"));
        assertFalse(rendered.contains("aGVsbG8="));
        assertTrue(new SummaryMessageRenderer().renderMessageForSummary(message.withoutImageContent())
                .contains("页面显示登录失败"));
    }
}
