package com.devcli.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.devcli.mcp.transport.McpTransport;
import com.devcli.runtime.CancellationToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JsonRpcClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void pairsResponseByNumericId() throws Exception {
        LoopbackTransport transport = new LoopbackTransport("""
                {"jsonrpc":"2.0","id":1,"result":{"ok":true}}
                """);
        JsonRpcClient client = new JsonRpcClient(transport);

        JsonNode result = client.request("ping", MAPPER.createObjectNode(), 1);

        assertTrue(result.path("ok").asBoolean());
        assertTrue(transport.sent.path("id").isNumber());
    }

    @Test
    void mapsJsonRpcErrorToException() {
        LoopbackTransport transport = new LoopbackTransport("""
                {"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"missing"}}
                """);
        JsonRpcClient client = new JsonRpcClient(transport);

        JsonRpcException error = assertThrows(JsonRpcException.class,
                () -> client.request("missing", MAPPER.createObjectNode(), 1));
        assertEquals(-32601, error.code());
    }

    @Test
    void cancellationSendsProtocolNotificationAndWaitsForPeerResponse() throws Exception {
        CancellableLoopbackTransport transport = new CancellableLoopbackTransport();
        try (JsonRpcClient client = new JsonRpcClient(transport)) {
            CancellationToken token = new CancellationToken();
            CompletableFuture<JsonNode> response = CompletableFuture.supplyAsync(() -> {
                try {
                    return client.request("tools/call", MAPPER.createObjectNode(), 30, token);
                } catch (IOException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
            assertTrue(transport.requestSent.await(2, TimeUnit.SECONDS));

            token.cancel(CancellationToken.Reason.TIMEOUT, "deadline");
            JsonNode result = response.get(2, TimeUnit.SECONDS);

            assertTrue(result.path("stopped").asBoolean());
            assertEquals("notifications/cancelled",
                    transport.cancellation.path("method").asText());
            assertEquals(1, transport.cancellation.path("params").path("requestId").asLong());
            assertEquals("deadline", transport.cancellation.path("params").path("reason").asText());
        }
    }

    @Test
    void interruptionWithoutCancellationTokenRestoresInterruptStatus() throws Exception {
        BlockingTransport transport = new BlockingTransport();
        try (JsonRpcClient client = new JsonRpcClient(transport)) {
            AtomicReference<IOException> failure = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread requestThread = new Thread(() -> {
                try {
                    client.request("tools/call", MAPPER.createObjectNode(), 30);
                } catch (IOException e) {
                    failure.set(e);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            }, "jsonrpc-interruption-test");
            requestThread.start();
            assertTrue(transport.requestSent.await(2, TimeUnit.SECONDS));

            requestThread.interrupt();
            requestThread.join(2_000);

            assertFalse(requestThread.isAlive());
            assertNotNull(failure.get());
            assertTrue(interrupted.get(), "JSON-RPC 等待被中断后必须恢复线程中断状态");
        }
    }

    private static final class LoopbackTransport implements McpTransport {
        private final String response;
        private Consumer<JsonNode> listener;
        private JsonNode sent;

        private LoopbackTransport(String response) {
            this.response = response;
        }

        @Override
        public void send(JsonNode message) throws IOException {
            sent = message;
            listener.accept(MAPPER.readTree(response));
        }

        @Override
        public void onReceive(Consumer<JsonNode> listener) {
            this.listener = listener;
        }

        @Override
        public void close() {
        }
    }

    private static final class CancellableLoopbackTransport implements McpTransport {
        private final CountDownLatch requestSent = new CountDownLatch(1);
        private Consumer<JsonNode> listener;
        private JsonNode request;
        private JsonNode cancellation;

        @Override
        public void send(JsonNode message) {
            request = message;
            requestSent.countDown();
        }

        @Override
        public void sendCancellation(JsonNode notification) {
            cancellation = notification;
            try {
                listener.accept(MAPPER.readTree("""
                        {"jsonrpc":"2.0","id":1,"result":{"stopped":true}}
                        """));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void onReceive(Consumer<JsonNode> listener) {
            this.listener = listener;
        }

        @Override
        public void close() {
        }
    }

    private static final class BlockingTransport implements McpTransport {
        private final CountDownLatch requestSent = new CountDownLatch(1);

        @Override
        public void send(JsonNode message) {
            requestSent.countDown();
        }

        @Override
        public void onReceive(Consumer<JsonNode> listener) {
        }

        @Override
        public void close() {
        }
    }
}
