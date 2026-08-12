package com.devcli.runtime;

import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReactWorkspaceTransactionTest {
    @Test
    void reactWriteIsInvisibleUntilPatchCommit(@TempDir Path project) throws Exception {
        Path target = project.resolve("file.txt");
        Files.writeString(target, "before");
        try (ToolRegistry parent = new ToolRegistry()) {
            parent.setProjectPath(project.toString());
            try (ReactWorkspaceTransaction transaction =
                         ReactWorkspaceTransaction.open(parent, "turn-1")) {
                ToolOutput output = transaction.toolRegistry().executeToolOutput(
                        "write_file", "{\"path\":\"file.txt\",\"content\":\"after\"}");

                assertTrue(output.isSuccess(), output.text());
                assertEquals("before", Files.readString(target));
                ReactWorkspaceTransaction.CommitResult commit = transaction.commit();
                assertTrue(commit.success(), commit.message());
                assertEquals("after", Files.readString(target));
                assertEquals(java.util.List.of("file.txt"), commit.modifiedResources());
            }
        }
    }

    @Test
    void commitRejectsConcurrentProjectChangeAndKeepsExternalContent(@TempDir Path project)
            throws Exception {
        Path target = project.resolve("file.txt");
        Files.writeString(target, "before");
        try (ToolRegistry parent = new ToolRegistry()) {
            parent.setProjectPath(project.toString());
            try (ReactWorkspaceTransaction transaction =
                         ReactWorkspaceTransaction.open(parent, "turn-conflict")) {
                ToolOutput output = transaction.toolRegistry().executeToolOutput(
                        "write_file", "{\"path\":\"file.txt\",\"content\":\"agent\"}");
                assertTrue(output.isSuccess(), output.text());

                Files.writeString(target, "external");
                ReactWorkspaceTransaction.CommitResult commit = transaction.commit();

                assertFalse(commit.success());
                assertTrue(commit.message().contains("file.txt"), commit.message());
                assertEquals("external", Files.readString(target));
            }
        }
    }
}
