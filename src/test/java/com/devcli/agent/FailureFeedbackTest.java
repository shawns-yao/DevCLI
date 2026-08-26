package com.devcli.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureFeedbackTest {

    @Test
    void classifiesRequestedFailureCategoriesDeterministically() {
        assertEquals(FailureFeedback.Category.VALIDATION_ERROR,
                FailureFeedback.fromReason("参数校验失败：query 缺失").category());
        assertEquals(FailureFeedback.Category.RESOURCE_CONFLICT,
                FailureFeedback.fromReason("资源租约冲突：Service.java 正在被占用").category());
        assertEquals(FailureFeedback.Category.STALE_CONTEXT,
                FailureFeedback.fromReason("STALE_CONTEXT：依赖符号已经变化").category());
        assertEquals(FailureFeedback.Category.BUDGET_EXHAUSTED,
                FailureFeedback.fromReason("Token 预算已用尽").category());
        assertEquals(FailureFeedback.Category.ENVIRONMENT_FAILURE,
                FailureFeedback.fromReason("Docker 环境不可用").category());
        assertEquals(FailureFeedback.Category.TASK_AMBIGUITY,
                FailureFeedback.fromReason("任务边界不明确，缺少验收标准").category());
    }

    @Test
    void rendersReasonCategorySuggestionAndFourExplicitActions() {
        String rendered = FailureFeedback.fromReason("参数校验失败：query 缺失").render();

        assertTrue(rendered.contains("失败原因：参数校验失败：query 缺失"), rendered);
        assertTrue(rendered.contains("失败分类：校验错误"), rendered);
        assertTrue(rendered.contains("操作建议："), rendered);
        assertTrue(rendered.contains("重试："), rendered);
        assertTrue(rendered.contains("人工接手："), rendered);
        assertTrue(rendered.contains("接受部分结果："), rendered);
        assertTrue(rendered.contains("回滚："), rendered);
    }

    @Test
    void planCheckpointProducesConcreteResumeAction() {
        FailureFeedback feedback = FailureFeedback.fromReason("AC-03 未满足：退款状态错误")
                .withRetryInstruction("运行 `/plan resume orch-pay` 从 checkpoint 继续");

        assertTrue(feedback.render().contains("/plan resume orch-pay"));
    }
}
