package com.devcli.memory;

import com.devcli.agent.Agent;
import com.devcli.hitl.ApprovalRequest;
import com.devcli.hitl.ApprovalResult;
import com.devcli.hitl.HitlHandler;
import com.devcli.hitl.HitlToolRegistry;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 定向测试：从记忆工具入口执行真实审批管线和文件存储，不调用模型。 */
class ProjectMemoryScopeToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private String previousMemoryRoot;
    private Path memoryRoot;

    @BeforeEach
    void configureMemoryRoot() {
        previousMemoryRoot = System.getProperty(MemoryPaths.MEMORY_DIR_PROPERTY);
        memoryRoot = tempDir.resolve("memory");
        System.setProperty(MemoryPaths.MEMORY_DIR_PROPERTY, memoryRoot.toString());
    }

    @AfterEach
    void restoreMemoryRoot() {
        if (previousMemoryRoot == null) System.clearProperty(MemoryPaths.MEMORY_DIR_PROPERTY);
        else System.setProperty(MemoryPaths.MEMORY_DIR_PROPERTY, previousMemoryRoot);
    }

    @Test
    void sharesMemoriesAcrossMainWorktreeLinkedWorktreeAndSubdirectories() throws Exception {
        Path main = createRepository(tempDir.resolve("main"));
        Path linked = createWorktree(main);
        Path nested = Files.createDirectories(linked.resolve("module/src"));
        Path other = createRepository(tempDir.resolve("other"));

        try (MemorySession session = open(main)) {
            session.save("build", "构建配置", "使用 mvn.cmd package。", "project");
            session.save("language", "回答语言", "默认使用简体中文。", "global");
        }
        try (MemorySession session = open(nested)) {
            assertTrue(session.list().contains("构建配置"));
            session.save("build", "构建配置", "使用 mvn.cmd test。", "project");
        }
        try (MemorySession session = open(Files.createDirectories(main.resolve("module")))) {
            assertTrue(session.list().contains("构建配置"));
            assertTrue(Files.readString(sharedDir(main).resolve("build.md"))
                    .contains("使用 mvn.cmd test。"));
        }
        try (MemorySession session = open(other)) {
            assertFalse(session.list().contains("构建配置"));
            assertTrue(session.list().contains("回答语言"));
        }
        assertFalse(Files.exists(legacyDir(linked).resolve("build.md")));
        assertFalse(Files.exists(legacyDir(nested).resolve("build.md")));
    }

    @Test
    void keepsOrdinaryDirectoriesAndNestedRepositoriesIsolated() throws Exception {
        Path plain = Files.createDirectories(tempDir.resolve("plain"));
        Path child = Files.createDirectories(plain.resolve("child"));
        try (MemorySession session = open(plain)) {
            session.save("plain", "普通目录事实", "当前目录使用独立配置。", "project");
        }
        try (MemorySession session = open(child)) {
            assertFalse(session.list().contains("普通目录事实"));
        }

        Path main = createRepository(tempDir.resolve("main"));
        Path inner = createRepository(main.resolve("inner"));
        try (MemorySession session = open(main)) {
            session.save("outer", "外层仓库事实", "外层仓库使用独立配置。", "project");
        }
        try (MemorySession session = open(inner)) {
            assertFalse(session.list().contains("外层仓库事实"));
        }
        Path broken = Files.createDirectories(main.resolve("broken"));
        Files.writeString(broken.resolve(".git"), "gitdir: missing-metadata\n");
        try (MemorySession session = open(broken)) {
            assertFalse(session.list().contains("外层仓库事实"));
            session.save("broken", "元数据损坏时的事实", "继续按当前目录保存。", "project");
        }
        assertTrue(Files.isRegularFile(legacyDir(broken).resolve("broken.md")));
        assertFalse(Files.exists(sharedDir(main).resolve("broken.md")));
    }

    @Test
    void migratesOldTopicsWithoutReplacingSharedTopicsOrCopyingIndexes() throws Exception {
        Path main = createRepository(tempDir.resolve("main"));
        Path linked = createWorktree(main);
        try (MemorySession session = open(main)) {
            session.save("build", "构建配置", "共享目录的构建配置。", "project");
        }
        Path sharedBuild = sharedDir(main).resolve("build.md");
        byte[] sharedBefore = Files.readAllBytes(sharedBuild);
        Path old = Files.createDirectories(legacyDir(linked));
        Path conflict = old.resolve("build.md");
        String oldBuild = topic("旧构建配置", "旧工作树的构建配置。");
        Files.writeString(conflict, oldBuild);
        Path reference = Files.createDirectories(old.resolve("references")).resolve("guide.MD");
        String oldReference = topic("操作说明", "操作说明的完整正文。");
        Files.writeString(reference, oldReference);
        Files.writeString(old.resolve("MEMORY.md"), "obsolete-index\n");

        try (MemorySession session = open(linked)) {
            String listing = session.list();
            assertTrue(listing.contains("操作说明"), listing);
            assertTrue(listing.contains("构建配置"), listing);
            assertFalse(listing.contains("旧构建配置"), listing);
            assertTrue(session.status().contains("旧项目记忆迁入 1 条 / 冲突保留 1 条 / 迁移失败 0 条"));
        }
        assertEquals(-1, Files.mismatch(reference, sharedDir(main).resolve("references/guide.MD")));
        assertEquals(oldReference, Files.readString(reference));
        assertEquals(oldBuild, Files.readString(conflict));
        assertTrue(java.util.Arrays.equals(sharedBefore, Files.readAllBytes(sharedBuild)));
        try (MemorySession session = open(linked)) {
            assertTrue(session.status().contains("冲突保留 1 条"));
            assertFalse(session.status().contains("旧项目记忆迁入 1 条"));
        }
    }

    @Test
    void completedMigrationDoesNotRestoreRemovedTopics() throws Exception {
        Path main = createRepository(tempDir.resolve("main"));
        Path linked = createWorktree(main);
        Path old = Files.createDirectories(legacyDir(linked)).resolve("guide.md");
        Files.writeString(old, topic("操作说明", "这条内容之后将被遗忘。"));
        try (MemorySession session = open(linked)) {
            assertTrue(session.list().contains("操作说明"));
        }

        // Markdown 允许用户直接编辑；删除迁入的主题后，旧源文件不能使内容复活。
        Files.delete(sharedDir(main).resolve("guide.md"));
        try (MemorySession session = open(linked)) {
            assertFalse(session.list().contains("操作说明"));
        }
        assertTrue(Files.isRegularFile(old));
    }

    @Test
    void reportsFailedMigrationAndRetriesAfterSourceIsRepaired() throws Exception {
        Path main = createRepository(tempDir.resolve("main"));
        Path linked = createWorktree(main);
        Path old = Files.createDirectories(legacyDir(linked)).resolve("guide.md");
        Files.write(old, new byte[] {(byte) 0xc3, 0x28});
        try (MemorySession session = open(linked)) {
            assertTrue(session.status().contains("迁移失败 1 条"));
            assertFalse(Files.exists(sharedDir(main).resolve("guide.md")));
        }
        Files.writeString(old, topic("操作说明", "已修复的操作说明。"));
        try (MemorySession session = open(linked)) {
            assertTrue(session.list().contains("操作说明"));
            assertTrue(session.status().contains("旧项目记忆迁入 1 条"));
        }
    }

    private MemorySession open(Path project) {
        HitlToolRegistry registry = new HitlToolRegistry(new HitlHandler() {
            @Override
            public ApprovalResult requestApproval(ApprovalRequest request) {
                return ApprovalResult.approve();
            }

            @Override
            public boolean isEnabled() { return true; }

            @Override
            public void setEnabled(boolean enabled) { }
        });
        registry.setProjectPath(project.toString());
        Agent agent = new Agent(null, registry);
        agent.getMemoryManager().setActiveProjectScope(project.toString());
        return new MemorySession(agent, registry);
    }

    private Path sharedDir(Path main) {
        return MemoryPaths.projectMemoryDir(memoryRoot, main.toString());
    }

    private Path legacyDir(Path project) {
        // 旧持久化布局的测试夹具，不调用新作用域解析器。
        return memoryRoot.resolve("projects")
                .resolve(MemoryPaths.compressPath(project.toAbsolutePath().normalize().toString()))
                .resolve("memory");
    }

    private static String topic(String name, String body) {
        return "---\nname: " + name + "\ndescription: 迁移测试主题\ntype: project\n"
                + "created_at: 2026-09-01T00:00:00Z\nupdated_at: 2026-09-20T00:00:00Z\n"
                + "revision: 3\n---\n\n" + body + "\n";
    }

    private static Path createRepository(Path root) throws Exception {
        Files.createDirectories(root);
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            git.commit().setAllowEmpty(true)
                    .setAuthor("DevCLI Test", "devcli-test@example.invalid")
                    .setCommitter("DevCLI Test", "devcli-test@example.invalid")
                    .setMessage("chore: 初始化测试仓库").call();
        }
        return root;
    }

    private Path createWorktree(Path main) throws Exception {
        Path linked = tempDir.resolve("linked");
        Process process = new ProcessBuilder(List.of("git", "-C", main.toString(),
                "worktree", "add", "--detach", linked.toString(), "HEAD"))
                .redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("创建测试工作树超时");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return linked;
    }

    private record MemorySession(Agent agent, HitlToolRegistry registry) implements AutoCloseable {
        void save(String name, String description, String content, String scope) throws Exception {
            ToolOutput output = registry.executeToolOutput("save_memory", JSON.writeValueAsString(
                    Map.of("name", name, "description", description, "fact", content,
                            "type", scope.equals("global") ? "user" : "project", "scope", scope)));
            assertEquals(ToolStatus.SUCCESS, output.status(), output.text());
        }

        String list() {
            ToolOutput output = registry.executeToolOutput("list_memory", "{}");
            assertEquals(ToolStatus.SUCCESS, output.status(), output.text());
            return output.text();
        }

        String status() {
            return agent.getMemoryManager().getSystemStatus();
        }

        @Override
        public void close() {
            try {
                agent.close();
            } finally {
                registry.close();
            }
        }
    }
}
