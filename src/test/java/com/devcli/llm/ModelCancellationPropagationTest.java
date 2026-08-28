package com.devcli.llm;

import com.devcli.runtime.CancellationContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ModelCancellationPropagationTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @org.junit.jupiter.api.Timeout(10)
    void cancellationAbortsBlockedHttpTransport(boolean anthropic) throws Exception {
        var received = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
             var run = CancellationContext.startRunContext(Path.of("."))) {
            var serverTask = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.getInputStream().read();
                    received.countDown();
                    release.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            String url = "http://127.0.0.1:" + server.getLocalPort();
            LlmClient client = anthropic ? new AnthropicClient("test-key", "test-model", url)
                    : new OpenAiClient("test-key", "test-model", url);
            var request = executor.submit(() -> {
                try (var sampling = new SamplingRequestCoordinator().begin("blocked-http")) {
                    return client.chat(java.util.List.of(LlmClient.Message.user("test")), java.util.List.of());
                }
            });
            assertTrue(received.await(3, java.util.concurrent.TimeUnit.SECONDS));
            run.cancel();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> request.get(3, java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();
            serverTask.get(3, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void runCancellationStopsTheCurrentSamplingRequestAndTransportCallback() {
        var coordinator = new SamplingRequestCoordinator();
        AtomicBoolean cancelled = new AtomicBoolean();
        try (var run = CancellationContext.startRunContext(Path.of("."));
             var request = coordinator.begin("child-call");
             var registration = SamplingRequestCoordinator.onCurrentCancel(() -> cancelled.set(true))) {
            run.cancel();
            assertTrue(request.isCancelled());
            assertTrue(cancelled.get());
            assertTrue(SamplingRequestCoordinator.isCurrentCancelled());
        }
        assertEquals(0, coordinator.activeCount());
    }
}
