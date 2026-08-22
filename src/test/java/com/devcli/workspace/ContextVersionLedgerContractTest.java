package com.devcli.workspace;

import com.devcli.rag.CodeChunk;
import com.devcli.rag.CodeChunker;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextVersionLedgerContractTest {

    @Test
    void codeChunkerSymbolsRemainCompatibleWithWriteGate(@TempDir Path project) throws Exception {
        Path service = project.resolve("OrderService.java");
        Path caller = project.resolve("OrderController.java");
        Files.writeString(service, """
                class OrderService {
                    String find(String id) { return id; }
                }
                """);
        Files.writeString(caller, "class OrderController {}\n");
        ContextVersionLedger ledger = new ContextVersionLedger();
        List<CodeChunk> chunks = new CodeChunker().chunkFile(service);

        for (CodeChunk chunk : chunks) {
            ledger.recordCodeEvidence("worker-a", "OrderService.java", service,
                    chunk.chunkType(), chunk.name(), "sv-fixture", chunk.content());
        }
        assertTrue(ledger.validateWrite("worker-a", "OrderController.java", caller,
                Files.readString(caller), project).isAllowed());

        Files.writeString(service, """
                class OrderService {
                    String find(long id) { return Long.toString(id); }
                }
                """);

        WriteGateResult result = ledger.validateWrite("worker-a", "OrderController.java", caller,
                Files.readString(caller), project);
        assertFalse(result.isAllowed());
        assertTrue(result.reason().contains("OrderService") && result.reason().contains("find"),
                result.reason());
    }

    @Test
    void wholeFileEvidenceDetectsChangeButSegmentEvidenceDoesNotFalseStale(@TempDir Path project)
            throws Exception {
        Path readme = project.resolve("README.md");
        Path caller = project.resolve("Caller.java");
        Files.writeString(readme, "production notes\n");
        Files.writeString(caller, "class Caller {}\n");
        ContextVersionLedger ledger = new ContextVersionLedger();
        CodeChunk whole = new CodeChunker().chunkFile(readme).getFirst();
        ledger.recordCodeEvidence("worker-a", "README.md", readme,
                whole.chunkType(), whole.name(), "sv-file", whole.content());
        assertTrue(ledger.validateWrite("worker-a", "Caller.java", caller,
                Files.readString(caller), project).isAllowed());

        Files.writeString(readme, "production notes changed\n");
        assertFalse(ledger.validateWrite("worker-a", "Caller.java", caller,
                Files.readString(caller), project).isAllowed());

        Path large = project.resolve("large.md");
        Files.writeString(large, ("line content\n").repeat(300));
        ContextVersionLedger segmentedLedger = new ContextVersionLedger();
        for (CodeChunk chunk : new CodeChunker().chunkFile(large)) {
            segmentedLedger.recordCodeEvidence("worker-b", "large.md", large,
                    chunk.chunkType(), chunk.name(), "sv-segment", chunk.content());
        }
        assertTrue(segmentedLedger.validateWrite("worker-b", "Caller.java", caller,
                Files.readString(caller), project).isAllowed(),
                "file#N 分段证据短期跳过，不得系统性误报 deleted");
    }

    @Test
    void committedForkChangeInvalidatesOtherFork(@TempDir Path project) throws Exception {
        Path service = project.resolve("OrderService.java");
        Files.writeString(service, "class OrderService { String find(String id) { return id; } }\n");
        Files.writeString(project.resolve("OrderController.java"), "class OrderController {}\n");
        try (ToolRegistry parent = new ToolRegistry();
             WorkspaceExecutionSession first = open(parent, project, "worker-a");
             WorkspaceExecutionSession second = WorkspaceExecutionSession.open(parent, "worker-b")) {
            ToolOutput read = first.toolRegistry().runWithResourceLease("worker-a", () ->
                    first.toolRegistry().executeToolOutput("read_file",
                            "{\"path\":\"OrderService.java\"}"));
            assertTrue(read.isSuccess(), read.text());

            ToolOutput changed = second.toolRegistry().runWithResourceLease("worker-b", () ->
                    second.toolRegistry().executeToolOutput("write_file", """
                            {"path":"OrderService.java","content":"class OrderService { String find(long id) { return Long.toString(id); } }\\n"}
                            """));
            assertTrue(changed.isSuccess(), changed.text());
            assertTrue(second.apply(second.patchSet()).applied());

            ToolOutput stale = first.toolRegistry().runWithResourceLease("worker-a", () ->
                    first.toolRegistry().executeToolOutput("write_file", """
                            {"path":"OrderController.java","content":"class OrderController { }\\n"}
                            """));
            assertFalse(stale.isSuccess(), stale.text());
            assertTrue(stale.text().contains("上下文已过期"), stale.text());
        }
    }

    @Test
    void patchGateCatchesCommandStyleIndirectWrite(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("Service.java"),
                "class Service { String value() { return \"v1\"; } }\n");
        Files.writeString(project.resolve("Caller.java"), "class Caller {}\n");
        try (ToolRegistry parent = new ToolRegistry()) {
            parent.setProjectPath(project.toString());
            try (WorkspaceExecutionSession staleWorker = WorkspaceExecutionSession.open(parent, "stale");
                 WorkspaceExecutionSession writer = WorkspaceExecutionSession.open(parent, "writer")) {
                staleWorker.toolRegistry().runWithResourceLease("stale", () ->
                        staleWorker.toolRegistry().executeToolOutput("read_file",
                                "{\"path\":\"Service.java\"}"));
                Files.writeString(writer.workspacePath().resolve("Service.java"),
                        "class Service { String value(int id) { return Integer.toString(id); } }\n");
                assertTrue(writer.apply(writer.patchSet()).applied());

                Files.writeString(staleWorker.workspacePath().resolve("Caller.java"),
                        "class Caller { String load(Service service) { return service.value(); } }\n");
                PatchSet.ApplyResult result = staleWorker.apply(staleWorker.patchSet());
                assertFalse(result.applied());
                assertTrue(result.error().contains("上下文已过期"), result.failureDescription());
                assertTrue(Files.readString(project.resolve("Caller.java")).equals("class Caller {}\n"));
            }
        }
    }

    @Test
    void refreshedWorkerCanRewriteTheChangedDependency(@TempDir Path project) throws Exception {
        Path service = project.resolve("Service.java");
        Files.writeString(service,
                "class Service { int value() { return 1; } }\n");
        try (ToolRegistry parent = new ToolRegistry()) {
            parent.setProjectPath(project.toString());
            try (WorkspaceExecutionSession staleWorker = WorkspaceExecutionSession.open(parent, "stale");
                 WorkspaceExecutionSession writer = WorkspaceExecutionSession.open(parent, "writer")) {
                ToolOutput read = staleWorker.toolRegistry().runWithResourceLease("stale", () ->
                        staleWorker.toolRegistry().executeToolOutput("read_file",
                                "{\"path\":\"Service.java\"}"));
                assertTrue(read.isSuccess(), read.text());

                Files.writeString(writer.workspacePath().resolve("Service.java"),
                        "class Service { int value() { return 2; } }\n");
                assertTrue(writer.apply(writer.patchSet()).applied());

                ToolOutput stale = staleWorker.toolRegistry().runWithResourceLease("stale", () ->
                        staleWorker.toolRegistry().executeToolOutput("write_file", """
                                {"path":"Service.java","content":"class Service { int value() { return 3; } }\\n"}
                                """));
                assertFalse(stale.isSuccess(), stale.text());
                assertTrue(stale.text().contains("上下文已过期"), stale.text());

                var refreshed = parent.contextVersionLedger().refreshPending("stale", project);
                assertTrue(refreshed.getOrDefault("Service.java", "").contains("return 2"),
                        refreshed.toString());
                ToolOutput rewritten = staleWorker.toolRegistry().runWithResourceLease("stale", () ->
                        staleWorker.toolRegistry().executeToolOutput("write_file", """
                                {"path":"Service.java","content":"class Service { int value() { return 3; } }\\n"}
                                """));
                assertTrue(rewritten.isSuccess(), rewritten.text());

                PatchSet.ApplyResult result = staleWorker.apply(staleWorker.patchSet());
                assertTrue(result.applied(), result.failureDescription());
                assertTrue(Files.readString(service).contains("return 3"));
            }
        }
    }

    private static WorkspaceExecutionSession open(ToolRegistry parent, Path project, String stepId)
            throws Exception {
        parent.setProjectPath(project.toString());
        return WorkspaceExecutionSession.open(parent, stepId);
    }
}
