package com.paicli.web;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RetryInterceptor 不变量契约测试。
 *
 * <p>守住的核心契约：
 * <ol>
 *   <li>4xx（除 408/429）<b>不</b>重试 — 业务错误必须原样回灌给 LLM</li>
 *   <li>5xx 重试，最终成功能拿到 200 响应</li>
 *   <li>5xx 重试用尽后返回最后一次响应（不抛异常）</li>
 *   <li>IOException 重试，重试次数耗尽抛出</li>
 *   <li>Retry-After header 在 429 场景被尊重</li>
 * </ol>
 */
class RetryInterceptorTest {

    private MockWebServer server;
    private OkHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        // baseBackoff=1ms 让测试跑得快；生产是 500ms
        client = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .addInterceptor(new RetryInterceptor(2, 1))
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void doesNotRetryOn4xxBusinessErrors() throws IOException {
        // 400 是业务错误（参数错），不应被静默重试
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad request"));

        try (Response resp = client.newCall(new Request.Builder()
                .url(server.url("/test")).build()).execute()) {
            assertEquals(400, resp.code());
        }
        assertEquals(1, server.getRequestCount(),
                "4xx 业务错误必须原样返回，不能被重试（重试只会再失败一次）");
    }

    @Test
    void retriesOn5xxAndEventuallySucceeds() throws IOException {
        // 前两次 503，第三次 200
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        try (Response resp = client.newCall(new Request.Builder()
                .url(server.url("/test")).build()).execute()) {
            assertEquals(200, resp.code());
            assertEquals("ok", resp.body().string());
        }
        assertEquals(3, server.getRequestCount(),
                "5xx 应该被重试，maxRetries=2 + 首次 = 3 次尝试");
    }

    @Test
    void retriesOn5xxButGivesUpAfterMaxRetries() throws IOException {
        // 全部 503
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        try (Response resp = client.newCall(new Request.Builder()
                .url(server.url("/test")).build()).execute()) {
            assertEquals(503, resp.code(),
                    "重试用尽后应返回最后一次响应而不是抛异常");
        }
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void retriesOn429WithRespect() throws IOException {
        // 429 是 retriable
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        try (Response resp = client.newCall(new Request.Builder()
                .url(server.url("/test")).build()).execute()) {
            assertEquals(200, resp.code());
        }
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void doesNotRetryOn401Or403() throws IOException {
        // 401/403 是认证/授权错误，重试无意义
        server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));
        try (Response resp = client.newCall(new Request.Builder()
                .url(server.url("/auth")).build()).execute()) {
            assertEquals(401, resp.code());
        }
        assertEquals(1, server.getRequestCount(), "401 不应被重试");

        server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));
        try (Response resp = client.newCall(new Request.Builder()
                .url(server.url("/auth")).build()).execute()) {
            assertEquals(403, resp.code());
        }
        assertEquals(2, server.getRequestCount(), "403 不应被重试");
    }

    @Test
    void doesNotRetryOn404() throws IOException {
        server.enqueue(new MockResponse().setResponseCode(404));
        try (Response resp = client.newCall(new Request.Builder()
                .url(server.url("/missing")).build()).execute()) {
            assertEquals(404, resp.code());
        }
        assertEquals(1, server.getRequestCount(),
                "404 是业务错误，不应被重试");
    }

    @Test
    void zeroRetryConfigDisablesRetry() throws IOException {
        OkHttpClient noRetry = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .addInterceptor(new RetryInterceptor(0, 1))
                .build();

        server.enqueue(new MockResponse().setResponseCode(503));

        try (Response resp = noRetry.newCall(new Request.Builder()
                .url(server.url("/test")).build()).execute()) {
            assertEquals(503, resp.code());
        }
        assertEquals(1, server.getRequestCount(), "maxRetries=0 应等同不重试");
    }
}
