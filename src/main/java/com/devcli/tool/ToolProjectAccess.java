package com.devcli.tool;

import com.devcli.policy.PathGuard;
import com.devcli.lsp.LspDiagnosticReport;
import com.devcli.lsp.LspManager;
import com.devcli.rag.VectorStore;
import com.devcli.policy.SensitivePathPolicy;
import com.devcli.policy.WriteGlobSet;
import com.devcli.policy.PolicyException;
import com.devcli.snapshot.SnapshotService;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static com.devcli.tool.ToolContract.*;

/** 项目文件访问、版本登记、写入观察与既有 LSP 诊断。 */
final class ToolProjectAccess {
    private final ToolRegistry registry;
    ToolProjectAccess(ToolRegistry registry) { this.registry = registry; }
    /** 项目相对路径键；路径越界或无法解析时返回 null（调用方按人工审批处理）。 */
    String projectRelativePathOrNull(String path) {
        try {
            return registry.contextResourceKey(registry.resolveSafePath(path));
        } catch (RuntimeException unresolved) {
            return null;
        }
    }


    boolean isWritePathAllowed(String path) {
        List<String> globs = registry.access.allowedWriteGlobs.get();
        if (WriteGlobSet.isEmpty(globs)) return true;
        try {
            return WriteGlobSet.matches(globs, registry.contextResourceKey(registry.resolveSafePath(path)));
        } catch (RuntimeException ignored) {
            return false;
        }
    }


    Path resolveSafePath(String path) { return registry.pathGuard.resolveSafe(path); }

    /**
     * 写入类工具的路径解析：根围栏之后再过受保护路径策略。
     *
     * <p>与 HITL 开关无关，也不可被审批放行：关闭人工审批不会让凭据、私钥或 `.git` 变成可写。</p>
     */

    Path resolveSafeWritePath(String path) {
        Path safe = registry.pathGuard.resolveSafe(path);
        String denyReason = SensitivePathPolicy.denyReason(Path.of(registry.contextResourceKey(safe)));
        if (denyReason == null) {
            denyReason = SensitivePathPolicy.agentConfigDenyReason(registry.contextProjectRoot, safe);
        }
        Path authoritative = registry.contextProjectRoot.resolve(registry.contextResourceKey(safe));
        if (denyReason == null && !safe.equals(authoritative)) {
            denyReason = SensitivePathPolicy.agentConfigDenyReason(registry.contextProjectRoot, authoritative);
        }
        if (denyReason != null) {
            throw new PolicyException(denyReason + ": " + path);
        }
        return safe;
    }


    int maxWriteFileBytes() { return ToolRegistry.MAX_WRITE_FILE_BYTES; }


    String currentResourceLeaseStep() { return registry.access.resourceLeaseStep.get(); }


    void acquireWriteLease(String stepId, Path path) { registry.resourceLeaseManager.acquireWrite(stepId, path); }


    boolean isWriteLeaseValid(String stepId, Path path) { return registry.resourceLeaseManager.isLeaseValid(stepId, path); }


    void recordFileRead(Path safePath, String content, String stepId) {
        String resourceKey = registry.contextResourceKey(safePath);
        Path authoritativePath = registry.contextProjectRoot.resolve(resourceKey);
        if (content == null) {
            registry.contextVersionLedger.recordReadFile(stepId, resourceKey, authoritativePath);
        } else {
            registry.contextVersionLedger.recordRead(stepId, resourceKey, authoritativePath, content);
        }
    }


    void recordCodeEvidence(Path safePath, String chunkType, String symbolName,
                                   String symbolVersion, String sourceContent) {
        String resourceKey = registry.contextResourceKey(safePath);
        registry.contextVersionLedger.recordCodeEvidence(registry.currentResourceLeaseStep(), resourceKey,
                registry.contextProjectRoot.resolve(resourceKey), chunkType, symbolName,
                symbolVersion, sourceContent);
    }

    /**
     * 步骤真正结束时清理其读取观察，避免长会话无界增长。
     *
     * <p>不能并入 {@link #registry.releaseResourceLeases(String)}：租约释放发生在每次 Worker 调用结束
     * （一个步骤有初次 / 修复 / 重试多次调用），并入会让被拦后的重试失去屏障保护。
     */
    public void forgetStaleWriteScope(String stepId) {
        registry.contextVersionLedger.forgetScope(stepId);
    }


    String staleWriteReason(String stepId, Path safePath, String currentContent) {
        com.devcli.workspace.WriteGateResult result = registry.validateWrite(stepId, safePath, currentContent);
        return result.isAllowed() ? null : result.reason();
    }


    com.devcli.workspace.WriteGateResult validateWrite(
            String stepId, Path safePath, String currentContent) {
        return registry.contextVersionLedger.validateWrite(stepId, registry.contextResourceKey(safePath),
                safePath, currentContent, registry.contextProjectRoot, !registry.sharedContextVersionLedger);
    }


    void recordFileWrite(String displayPath, Path safePath, String before, String content, String stepId) {
        // 文件内容发生变化后，当前 Registry 中的内容型只读缓存不再可信。
        registry.toolResultCache.clear();
        if (stepId != null && !stepId.isBlank()) {
            registry.stepModifiedFiles
                    .computeIfAbsent(stepId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                    .add(safePath.toString());
        }
        String resourceKey = registry.contextResourceKey(safePath);
        if (registry.sharedContextVersionLedger) {
            registry.contextVersionLedger.recordLocalWrite(stepId, resourceKey, safePath, before, content);
        } else {
            registry.contextVersionLedger.publishWrite(stepId, resourceKey, safePath, content);
            registry.markRagIndexDirty(List.of(resourceKey));
        }
        try {
            registry.writeFileObserver.accept(displayPath, new String[]{before, content});
        } catch (Exception ignored) {
            // observer 失败不能影响 write_file 主路径
        }
        runPostEditLspHook(displayPath, safePath);
    }

    /** 项目内容成功落盘后发布索引失效；索引不可用不回滚已经完成的文件写入。 */
    void markRagIndexDirty(Collection<String> relativePaths) {
        if (relativePaths == null || relativePaths.isEmpty()) return;
        try (VectorStore store = new VectorStore(registry.contextProjectRoot.toString())) {
            store.markDirtyFiles(relativePaths.stream().filter(Objects::nonNull).toList());
        } catch (Exception e) {
            ToolRegistry.log.warn("发布代码索引失效标记失败，检索时将回退实时候选校验: {}", e.getMessage());
        }
    }

    /** 隔离工作区补丁归并到主项目后清理主 Registry 的只读结果缓存。 */
    void invalidateToolResultCache() {
        registry.toolResultCache.clear();
    }

    String contextResourceKey(Path path) {
        Path physicalRoot = Path.of(registry.projectPath).toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(physicalRoot)) {
            throw new IllegalArgumentException("context resource escapes project root: " + path);
        }
        return physicalRoot.relativize(normalized).toString().replace('\\', '/');
    }

    java.util.Map<String, String> refreshStaleContext(String stepId) {
        return registry.contextVersionLedger.refreshPending(stepId, registry.contextProjectRoot);
    }

    /**
     * 注册 write_file 写入观察者：参数 (path, [before, after])，
     * before == null 表示新建文件或读不出原文。
     * 用于把 write_file 接到行内 diff 渲染等只读副作用里；
     * 观察者抛异常不影响 write_file 主路径。
     */
    void setWriteFileObserver(java.util.function.BiConsumer<String, String[]> observer) {
        registry.writeFileObserver = observer == null ? (p, ba) -> {} : observer;
    }

    void setLspManager(LspManager lspManager) {
        registry.lspManager = lspManager == null ? new LspManager(registry.projectPath) : lspManager;
        registry.lspManager.setProjectPath(registry.projectPath);
    }

    LspDiagnosticReport flushPendingLspDiagnostics() {
        return registry.lspManager == null ? LspDiagnosticReport.EMPTY : registry.lspManager.flushPendingDiagnostics();
    }

    SnapshotService getSnapshotService() {
        return registry.snapshotService;
    }

    void setSnapshotService(SnapshotService snapshotService) {
        registry.snapshotService = snapshotService == null ? SnapshotService.forProject(Path.of(registry.projectPath)) : snapshotService;
        registry.customSnapshotService = snapshotService != null;
    }

    void runPostEditLspHook(String displayPath, Path safePath) {
        try {
            if (registry.lspManager != null) {
                registry.lspManager.runPostEditLspHook(displayPath, safePath);
            }
        } catch (Exception ignored) {
            // LSP 诊断是 post-edit 辅助信号，失败不能影响工具主结果。
        }
    }

    /**
     * 设置代码检索的项目路径
     */
    void setProjectPath(String projectPath) {
        registry.ragToolProvider.closeCachedCodeRetriever();
        registry.toolResultCache.clear();
        registry.projectPath = projectPath;
        registry.delegateRoles = DelegateRoleCatalog.load(Path.of(projectPath));
        registry.registerTool(DelegateTaskTool.definition(registry));
        if (!registry.sharedContextVersionLedger) {
            registry.contextVersionLedger.clear();
            registry.contextVersionLedger = new com.devcli.workspace.ContextVersionLedger();
            registry.contextProjectRoot = Path.of(projectPath).toAbsolutePath().normalize();
        }
        registry.pathGuard = new PathGuard(projectPath);
        registry.lspManager.setProjectPath(projectPath);
        if (!registry.customSnapshotService) {
            registry.snapshotService.close();
            registry.snapshotService = SnapshotService.forProject(Path.of(projectPath));
        }
    }
}
