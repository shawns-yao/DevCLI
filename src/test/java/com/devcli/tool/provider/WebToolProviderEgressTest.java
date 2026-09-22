package com.devcli.tool.provider;

import com.devcli.web.NetworkPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网络出口预算：web_search 与 web_fetch 共享同一份限流，且在建连之前判定。
 */
class WebToolProviderEgressTest {

    @Test
    void searchAndFetchShareOneEgressBudget() {
        WebToolProvider provider = new WebToolProvider();
        provider.setNetworkPolicy(new NetworkPolicy(60_000L, 1));

        assertNull(provider.admitEgress(), "第一次出口应通过");
        assertNotNull(provider.admitEgress(), "第二次应命中同一个出口预算");
    }

    @Test
    void rateLimitedSearchReturnsLimitReasonInsteadOfConnecting() {
        WebToolProvider provider = new WebToolProvider();
        provider.setNetworkPolicy(new NetworkPolicy(60_000L, 1));
        provider.admitEgress();

        String result = provider.webSearch("java agent", 5);

        assertTrue(result.contains("请求过于频繁"),
                "额度用尽时应直接返回限流原因，不继续解析 provider: " + result);
    }

    @Test
    void blankQueryStillFailsArgumentValidationBeforeEgress() {
        WebToolProvider provider = new WebToolProvider();
        provider.setNetworkPolicy(new NetworkPolicy(60_000L, 1));

        String result = provider.webSearch("  ", 5);

        assertTrue(result.contains("不能为空"), result);
        assertNull(provider.admitEgress(), "参数非法不应消耗出口预算");
    }
}
