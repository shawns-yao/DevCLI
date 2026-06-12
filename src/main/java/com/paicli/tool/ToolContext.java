package com.paicli.tool;

import com.paicli.lsp.LspManager;
import com.paicli.memory.ContextProfile;
import com.paicli.skill.SkillContextBuffer;
import com.paicli.skill.SkillRegistry;
import com.paicli.snapshot.SnapshotService;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 工具执行上下文
 *
 * 封装所有 ToolHandler 需要的依赖，避免每个 Handler 持有大量字段。
 */
public class ToolContext {
    private final PathGuard pathGuard;
    private final ResourceLeaseManager resourceLeaseManager;
    private final ThreadLocal<String> resourceLeaseStep;
    private final LspManager lspManager;
    private final SnapshotService snapshotService;
    private final SkillRegistry skillRegistry;
    private final SkillContextBuffer skillContextBuffer;
    private final ContextProfile contextProfile;
    private final Consumer<String> memorySaver;
    private final Function<String, ToolRegistry.MemorySaveResult> memorySaveHandler;
    private final Function<String, String> memoryListHandler;
    private final BiConsumer<String, String[]> writeFileObserver;

    public ToolContext(
        PathGuard pathGuard,
        ResourceLeaseManager resourceLeaseManager,
        ThreadLocal<String> resourceLeaseStep,
        LspManager lspManager,
        SnapshotService snapshotService,
        SkillRegistry skillRegistry,
        SkillContextBuffer skillContextBuffer,
        ContextProfile contextProfile,
        Consumer<String> memorySaver,
        Function<String, ToolRegistry.MemorySaveResult> memorySaveHandler,
        Function<String, String> memoryListHandler,
        BiConsumer<String, String[]> writeFileObserver
    ) {
        this.pathGuard = pathGuard;
        this.resourceLeaseManager = resourceLeaseManager;
        this.resourceLeaseStep = resourceLeaseStep;
        this.lspManager = lspManager;
        this.snapshotService = snapshotService;
        this.skillRegistry = skillRegistry;
        this.skillContextBuffer = skillContextBuffer;
        this.contextProfile = contextProfile;
        this.memorySaver = memorySaver;
        this.memorySaveHandler = memorySaveHandler;
        this.memoryListHandler = memoryListHandler;
        this.writeFileObserver = writeFileObserver;
    }

    public PathGuard getPathGuard() { return pathGuard; }
    public ResourceLeaseManager getResourceLeaseManager() { return resourceLeaseManager; }
    public ThreadLocal<String> getResourceLeaseStep() { return resourceLeaseStep; }
    public LspManager getLspManager() { return lspManager; }
    public SnapshotService getSnapshotService() { return snapshotService; }
    public SkillRegistry getSkillRegistry() { return skillRegistry; }
    public SkillContextBuffer getSkillContextBuffer() { return skillContextBuffer; }
    public ContextProfile getContextProfile() { return contextProfile; }
    public Consumer<String> getMemorySaver() { return memorySaver; }
    public Function<String, ToolRegistry.MemorySaveResult> getMemorySaveHandler() { return memorySaveHandler; }
    public Function<String, String> getMemoryListHandler() { return memoryListHandler; }
    public BiConsumer<String, String[]> getWriteFileObserver() { return writeFileObserver; }
}
