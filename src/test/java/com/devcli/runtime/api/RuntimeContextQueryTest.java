package com.devcli.runtime.api;

import com.devcli.concurrent.CancellationContext;
import com.devcli.event.RunEvent;
import com.devcli.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeContextQueryTest {
    @TempDir Path root;

    @Test
    void displaysActualRequestsAndUsageWithoutMixingChildrenOrBranches() throws Exception {
        String id;
        try (var store = new RuntimeThreadStore(root.resolve("context.db"))) {
            String thread = store.createThread();
            var publisher = new RuntimeEventPublisher(store, thread, "turn");
            try (var run = CancellationContext.startRunContext(root)) {
                id = run.runId();
                assertEquals(id, com.devcli.trace.TraceContext.root("test").traceId());
                publisher.emit(RunEvent.ModelContext.from(1, List.of(LlmClient.Message.user("parent input")), List.of(
                        new LlmClient.Tool("read_file", "read", new ObjectMapper().readTree("{\"type\":\"object\"}")))));
                publisher.emit(new RunEvent.ModelUsage(101, 11, 80, 0));
                publisher.emit(new RunEvent.AgentEvent("reviewer", RunEvent.ModelContext.from(1,
                        List.of(LlmClient.Message.user(List.of(LlmClient.ContentPart.text("child input"),
                                LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png")))))));
                publisher.emit(new RunEvent.AgentEvent("reviewer", new RunEvent.ModelUsage(202, 22, 180, 0)));
                publisher.emit(new RunEvent.ModelUsage(202, 22, 180, 0));
                publisher.emit(RunEvent.ModelContext.from(2, List.of(LlmClient.Message.user("failed request"))));
            }
            try (var other = CancellationContext.startRunContext(root)) {
                publisher.emit(RunEvent.ModelContext.from(1, List.of(LlmClient.Message.user("other run"))));
            }
            store.activateBranch(thread, store.createBranch(thread, "other", 0).id());
            String rendered = new RuntimeContextQuery(store).render(id);
            assertTrue(rendered.contains("请求 3"));
            assertTrue(rendered.contains("101/11/80"));
            assertTrue(rendered.contains("202/22/180"));
            assertTrue(rendered.contains("agent=reviewer"));
            assertTrue(rendered.contains("failed request"));
            assertTrue(rendered.contains("未记录"));
            assertTrue(rendered.contains("read_file"));
            assertTrue(rendered.contains("image/png"));
            assertFalse(rendered.contains("aGVsbG8="));
            assertFalse(rendered.contains("other run"));
            assertTrue(new RuntimeContextQuery(store).render("missing").contains("没有已记录"));
        }
        try (var store = new RuntimeThreadStore(root.resolve("context.db"))) {
            assertTrue(new RuntimeContextQuery(store).render(id).contains("parent input"));
        }
    }
}
