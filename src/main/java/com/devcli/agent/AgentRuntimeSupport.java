package com.devcli.agent;

import com.devcli.memory.CompactBoundaryRuntimeState;
import com.devcli.memory.ConversationHistoryCompactor;
import com.devcli.memory.MemoryManager;
import com.devcli.memory.PostCompactRestoreContext;
import com.devcli.skill.SkillContextBuffer;
import com.devcli.skill.SkillIndexFormatter;
import com.devcli.skill.SkillRegistry;
import com.devcli.tool.ToolRegistry;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/** Agent、Plan 和 Team 共用的运行上下文装配。 */
final class AgentRuntimeSupport {
    private AgentRuntimeSupport() {
    }

    static void bindMemory(ToolRegistry toolRegistry, MemoryManager memoryManager) {
        toolRegistry.setContextProfile(memoryManager.getContextProfile());
        toolRegistry.setMemorySaver(memoryManager::storeFact);
        toolRegistry.setMemorySaveHandler(fact -> {
            MemoryManager.StoreResult result = memoryManager.storeFactWithPolicy(fact, true);
            return new ToolRegistry.MemorySaveResult(result.stored(), result.message());
        });
        toolRegistry.setMemoryListHandler(memoryManager::listLongTermMemory);
    }

    static void configureCompactor(ConversationHistoryCompactor compactor,
                                   MemoryManager memoryManager,
                                   ToolRegistry toolRegistry,
                                   java.util.function.Supplier<String> restoreSectionSupplier,
                                   java.util.function.Supplier<CompactBoundaryRuntimeState> runtimeStateSupplier) {
        compactor.setCompactionSummaryCache(memoryManager.getCompactionSummaryCache());
        compactor.setPostCompactContextSupplier(restoreSectionSupplier);
        compactor.setCompactBoundaryRuntimeStateSupplier(runtimeStateSupplier);
        compactor.setMicrocompactOutputRoot(Path.of(toolRegistry.getProjectPath()));
    }

    static String buildSkillIndex(SkillRegistry skillRegistry, String activationText,
                                  ToolRegistry toolRegistry, Logger log) {
        if (skillRegistry == null) {
            return "";
        }
        try {
            return SkillIndexFormatter.format(skillRegistry.enabledSkillsForText(
                    activationText, toolRegistry.getProjectPath()));
        } catch (Exception e) {
            log.warn("Failed to build skill index", e);
            return "";
        }
    }

    static String prependSkillBodies(SkillContextBuffer buffer, String content) {
        return prependSkillBodies(buffer, content, true);
    }

    static String prependSkillBodies(SkillContextBuffer buffer, String content, boolean consume) {
        if (buffer == null || buffer.isEmpty()) {
            return content;
        }
        String bodies = consume ? buffer.drain() : buffer.snapshot();
        return prependSkillBodies(bodies, content);
    }

    static String prependSkillBodies(String bodies, String content) {
        return bodies == null || bodies.isEmpty() ? content : bodies + "\n" + content;
    }

    static String buildPostCompactRestoreSection(String workingMemory,
                                                 ToolRegistry toolRegistry,
                                                 SkillContextBuffer skillContextBuffer) {
        List<PostCompactRestoreContext.Section> sections = new ArrayList<>();
        if (workingMemory != null && !workingMemory.isBlank()) {
            sections.add(new PostCompactRestoreContext.Section(
                    "工作记忆恢复", workingMemory.trim()));
        }
        String mcpTools = buildMcpPostCompactRestoreSection(toolRegistry);
        if (!mcpTools.isBlank()) {
            sections.add(new PostCompactRestoreContext.Section("MCP 工具状态", mcpTools));
        }
        if (skillContextBuffer != null) {
            String skills = skillContextBuffer.renderPostCompactRestoreSection();
            if (!skills.isBlank()) {
                sections.add(new PostCompactRestoreContext.Section("已调用 Skill 恢复", skills));
            }
        }
        return PostCompactRestoreContext.render(
                sections.toArray(PostCompactRestoreContext.Section[]::new));
    }

    static String buildMcpPostCompactRestoreSection(ToolRegistry toolRegistry) {
        String snapshot = toolRegistry.mcpToolSnapshot();
        if (snapshot == null || snapshot.isBlank()
                || "none".equalsIgnoreCase(snapshot.trim())) {
            return "";
        }
        return "- snapshot: " + snapshot.trim();
    }

    static CompactBoundaryRuntimeState buildCompactBoundaryRuntimeState(
            MemoryManager memoryManager,
            ToolRegistry toolRegistry,
            SkillContextBuffer skillContextBuffer,
            boolean entryState) {
        return new CompactBoundaryRuntimeState(
                skillContextBuffer == null ? List.of() : skillContextBuffer.activeSkillNames(),
                CompactBoundaryRuntimeState.mergeRagEpochSnapshots(
                        memoryManager.currentRagEpochSnapshot(),
                        toolRegistry.currentRagIndexEpochSnapshot()),
                toolRegistry.mcpToolSnapshot(),
                entryState);
    }
}
