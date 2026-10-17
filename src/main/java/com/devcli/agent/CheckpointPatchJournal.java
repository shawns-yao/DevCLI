package com.devcli.agent;

import com.devcli.plan.ExecutionArtifact;
import com.devcli.workspace.FileModeSnapshot;
import com.devcli.workspace.PatchSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

import static com.devcli.agent.AgentCheckpoint.*;

/** PatchSet 写前日志、延迟补丁文件与恢复对账；元数据仍由 checkpoint 持有。 */
final class CheckpointPatchJournal {
    private static final Logger log = LoggerFactory.getLogger(CheckpointPatchJournal.class);
    private final AgentCheckpoint checkpoint;
    CheckpointPatchJournal(AgentCheckpoint checkpoint) { this.checkpoint = checkpoint; }

    void preparePatchCommit(String stepId, Path projectRoot,
                            PatchSet patchSet, ExecutionArtifact intendedArtifact) throws IOException {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId is required");
        }
        Path root = normalizeProjectRoot(projectRoot);
        Path journal = patchJournalDir(stepId);
        deleteTree(journal);
        PatchJournalPolicy.secureDirectory(journal);

        List<PendingPatchEntry> entries = new ArrayList<>();
        try {
            for (PatchSet.FileChange change : patchSet.changes()) {
                Path target = resolveSafe(root, change.relativePath());
                String currentHash = currentHash(target);
                if (!currentHash.equals(change.beforeHash())) {
                    throw new IOException("PatchSet 写前日志前置版本冲突: " + change.relativePath());
                }
                if (!modeMatches(target, change.beforeMode())) {
                    throw new IOException("PatchSet 写前日志前置权限冲突: " + change.relativePath());
                }
                boolean backupPresent = !PatchSet.isMissingHash(change.beforeHash());
                if (backupPresent) {
                    Path backup = resolveSafe(journal, change.relativePath());
                    PatchJournalPolicy.secureDirectory(backup.resolveSibling(".").normalize());
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                    PatchJournalPolicy.secureFile(backup);
                }
                entries.add(new PendingPatchEntry(
                        change.relativePath(), change.type(), change.beforeHash(),
                        change.afterHash(), backupPresent,
                        change.beforeMode(), change.afterMode()));
            }
            ExecutionArtifact intended = intendedArtifact == null
                    ? ExecutionArtifact.pending(stepId)
                    : intendedArtifact.withModifiedResources(
                    entries.stream().map(PendingPatchEntry::relativePath).toList());
            checkpoint.pendingPatchCommits().put(stepId,
                    new PendingPatchCommit(stepId, entries, intended));
            checkpoint.setTimestamp(System.currentTimeMillis());
            checkpoint.saveStrict();
        } catch (IOException | RuntimeException e) {
            checkpoint.pendingPatchCommits().remove(stepId);
            deleteTree(journal);
            throw e;
        }
    }

    void preserveDeferredPatch(String stepId, PatchSet patchSet,
                                                   String failureReason) throws IOException {
        String normalizedStepId = AgentCheckpoint.normalizeRequired(stepId, "stepId");
        if (patchSet == null || patchSet.isEmpty()) {
            return;
        }
        String storageKey = UUID.randomUUID().toString();
        Path directory = deferredPatchDir(normalizedStepId, storageKey);
        PatchJournalPolicy.secureDirectory(directory);
        List<DeferredPatchEntry> entries = new ArrayList<>();
        DeferredPatch previous = checkpoint.deferredPatches().get(normalizedStepId);
        try {
            for (PatchSet.FileChange change : patchSet.changes()) {
                if (change.type() == PatchSet.ChangeType.DELETE) {
                    if (!PatchSet.isMissingHash(change.afterHash())) {
                        throw new IOException("延迟补丁删除项 afterHash 非法: "
                                + change.relativePath());
                    }
                } else {
                    String actualHash = PatchSet.hash(change.content());
                    if (!actualHash.equals(change.afterHash())) {
                        throw new IOException("延迟补丁内容哈希不匹配: "
                                + change.relativePath());
                    }
                    Path contentFile = resolveSafe(directory.resolve("after"),
                            change.relativePath());
                    PatchJournalPolicy.secureDirectory(contentFile.resolveSibling(".").normalize());
                    Files.write(contentFile, change.content());
                    PatchJournalPolicy.secureFile(contentFile);
                }
                entries.add(new DeferredPatchEntry(
                        change.relativePath(), change.type(), change.beforeHash(),
                        change.afterHash(), change.beforeMode(), change.afterMode()));
            }
            checkpoint.deferredPatches().put(normalizedStepId, new DeferredPatch(
                    normalizedStepId, storageKey, entries,
                    failureReason, System.currentTimeMillis()));
            checkpoint.setTimestamp(System.currentTimeMillis());
            checkpoint.saveStrict();
            if (previous != null) {
                try {
                    deleteTree(deferredPatchDir(previous.stepId(), previous.storageKey()));
                } catch (IOException cleanupFailure) {
                    log.warn("清理旧待验证 PatchSet 失败: step={}, error={}",
                            normalizedStepId, cleanupFailure.getMessage());
                }
            }
        } catch (IOException | RuntimeException e) {
            if (previous == null) {
                checkpoint.deferredPatches().remove(normalizedStepId);
            } else {
                checkpoint.deferredPatches().put(normalizedStepId, previous);
            }
            deleteTree(directory);
            throw e;
        }
    }

    PatchSet loadDeferredPatch(String stepId) throws IOException {
        String normalizedStepId = AgentCheckpoint.normalizeRequired(stepId, "stepId");
        DeferredPatch deferred = checkpoint.deferredPatches().get(normalizedStepId);
        if (deferred == null) {
            return new PatchSet(List.of());
        }
        Path directory = deferredPatchDir(deferred.stepId(), deferred.storageKey());
        List<PatchSet.FileChange> changes = new ArrayList<>();
        for (DeferredPatchEntry entry : deferred.entries()) {
            byte[] content = entry.type() == PatchSet.ChangeType.DELETE
                    ? new byte[0]
                    : Files.readAllBytes(resolveSafe(directory.resolve("after"),
                    entry.relativePath()));
            if (entry.type() != PatchSet.ChangeType.DELETE
                    && !PatchSet.hash(content).equals(entry.afterHash())) {
                throw new IOException("待验证 PatchSet 内容损坏: " + entry.relativePath());
            }
            changes.add(new PatchSet.FileChange(
                    entry.relativePath(), entry.type(), entry.beforeHash(), entry.afterHash(),
                    content, entry.beforeMode(), entry.afterMode()));
        }
        return new PatchSet(changes);
    }

    void cleanupDeferredPatchFiles(DeferredPatch deferredPatch) {
        if (deferredPatch == null) {
            return;
        }
        try {
            deleteTree(deferredPatchDir(
                    deferredPatch.stepId(), deferredPatch.storageKey()));
        } catch (IOException cleanupFailure) {
            log.warn("清理待验证 PatchSet 失败: step={}, error={}",
                    deferredPatch.stepId(), cleanupFailure.getMessage());
        }
    }

    void clearDeferredPatch(String stepId) throws IOException {
        if (stepId == null || stepId.isBlank()) {
            return;
        }
        DeferredPatch removed = checkpoint.deferredPatches().remove(stepId);
        if (removed == null) {
            return;
        }
        checkpoint.setTimestamp(System.currentTimeMillis());
        try {
            checkpoint.saveStrict();
        } catch (IOException e) {
            checkpoint.deferredPatches().put(stepId, removed);
            throw e;
        }
        cleanupDeferredPatchFiles(removed);
    }

    void cleanupPatchJournal(String stepId) {
        try {
            deleteTree(patchJournalDir(stepId));
        } catch (IOException e) {
            log.warn("清理 PatchSet 写前日志失败: step={}, error={}", stepId, e.getMessage());
        }
    }

    PatchReconcileResult reconcilePendingPatchCommits(Path projectRoot) throws IOException {
        Map<String, PatchReconcileAction> actions = new LinkedHashMap<>();
        Map<String, List<String>> failures = new LinkedHashMap<>();
        if (checkpoint.pendingPatchCommits().isEmpty()) {
            return new PatchReconcileResult(actions, failures);
        }

        Path root = normalizeProjectRoot(projectRoot);
        List<String> completedJournals = new ArrayList<>();
        for (PendingPatchCommit pending : new ArrayList<>(checkpoint.pendingPatchCommits().values())) {
            boolean allBefore = true;
            boolean allAfter = true;
            for (PendingPatchEntry entry : pending.entries()) {
                Path target = resolveSafe(root, entry.relativePath());
                String current = currentHash(target);
                allBefore &= current.equals(entry.beforeHash())
                        && modeMatches(target, entry.beforeMode());
                allAfter &= current.equals(entry.afterHash())
                        && modeMatches(target, entry.afterMode());
            }

            if (allAfter) {
                checkpoint.promoteCompletedArtifact(pending);
                checkpoint.pendingPatchCommits().remove(pending.stepId());
                completedJournals.add(pending.stepId());
                actions.put(pending.stepId(), PatchReconcileAction.PROMOTED_COMPLETED);
                continue;
            }
            if (allBefore) {
                checkpoint.pendingPatchCommits().remove(pending.stepId());
                completedJournals.add(pending.stepId());
                actions.put(pending.stepId(), PatchReconcileAction.CONTINUE_PENDING);
                continue;
            }

            List<String> rollbackFailures = rollbackPendingPatch(root, pending);
            if (rollbackFailures.isEmpty()) {
                checkpoint.pendingPatchCommits().remove(pending.stepId());
                completedJournals.add(pending.stepId());
                actions.put(pending.stepId(), PatchReconcileAction.ROLLED_BACK);
            } else {
                actions.put(pending.stepId(), PatchReconcileAction.ROLLBACK_FAILED);
                failures.put(pending.stepId(), rollbackFailures);
            }
        }

        checkpoint.setTimestamp(System.currentTimeMillis());
        checkpoint.saveStrict();
        completedJournals.forEach(this::cleanupPatchJournal);
        return new PatchReconcileResult(actions, failures);
    }

    private List<String> rollbackPendingPatch(Path projectRoot, PendingPatchCommit pending) {
        List<String> failures = new ArrayList<>();
        List<PendingPatchEntry> entries = new ArrayList<>(pending.entries());
        entries.sort(Comparator.comparingInt((PendingPatchEntry entry) ->
                Path.of(entry.relativePath()).getNameCount()).reversed());
        for (PendingPatchEntry entry : entries) {
            Path target = resolveSafe(projectRoot, entry.relativePath());
            try {
                if (entry.backupPresent()) {
                    Path backup = resolveSafe(patchJournalDir(pending.stepId()), entry.relativePath());
                    Path directory = target.resolveSibling(".").normalize();
                    Files.createDirectories(directory);
                    Path temporary = Files.createTempFile(
                            directory, ".devcli-recovery-", ".tmp");
                    try {
                        Files.copy(backup, temporary, StandardCopyOption.REPLACE_EXISTING);
                        try {
                            Files.move(temporary, target,
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException atomicFailure) {
                            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } finally {
                        Files.deleteIfExists(temporary);
                    }
                } else {
                    Files.deleteIfExists(target);
                }
                if (entry.backupPresent() && entry.beforeMode() != null) {
                    entry.beforeMode().apply(target);
                }
                String restored = currentHash(target);
                if (!restored.equals(entry.beforeHash())) {
                    failures.add(entry.relativePath() + ": 回滚后哈希不匹配");
                } else if (!modeMatches(target, entry.beforeMode())) {
                    failures.add(entry.relativePath() + ": 回滚后权限不匹配");
                }
            } catch (IOException | RuntimeException e) {
                failures.add(entry.relativePath() + ": "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        return failures;
    }

    Path patchJournalRoot() {
        String safeId = checkpoint.getOrchestrationId() == null || checkpoint.getOrchestrationId().isBlank()
                ? "checkpoint"
                : checkpoint.getOrchestrationId().replaceAll("[^a-zA-Z0-9._-]", "-");
        return AgentCheckpoint.getCheckpointDir().resolve(safeId + ".patch-journal");
    }

    private Path patchJournalDir(String stepId) {
        String safeStep = stepId == null || stepId.isBlank()
                ? "step"
                : stepId.replaceAll("[^a-zA-Z0-9._-]", "-");
        Path root = patchJournalRoot().normalize();
        Path journal = root.resolve("step-" + safeStep).normalize();
        if (journal.equals(root) || !journal.startsWith(root)) {
            throw new IllegalArgumentException("invalid patch journal step id");
        }
        return journal;
    }

    private Path deferredPatchDir(String stepId, String storageKey) {
        String safeStep = stepId == null || stepId.isBlank()
                ? "step"
                : stepId.replaceAll("[^a-zA-Z0-9._-]", "-");
        String safeStorage = storageKey == null || storageKey.isBlank()
                ? "legacy"
                : storageKey.replaceAll("[^a-zA-Z0-9._-]", "-");
        Path root = patchJournalRoot().normalize();
        Path directory = root.resolve("deferred-step-" + safeStep + "-" + safeStorage).normalize();
        if (directory.equals(root) || !directory.startsWith(root)) {
            throw new IllegalArgumentException("invalid deferred patch step id");
        }
        return directory;
    }

    private static Path normalizeProjectRoot(Path projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot is required");
        }
        return projectRoot.toAbsolutePath().normalize();
    }

    private static Path resolveSafe(Path root, String relativePath) {
        Path relative = Path.of(relativePath);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("patch journal path must be relative: " + relativePath);
        }
        Path resolved = root.resolve(relative).normalize();
        if (resolved.equals(root) || !resolved.startsWith(root)) {
            throw new IllegalArgumentException("patch journal path escapes root: " + relativePath);
        }
        return resolved;
    }

    private static String currentHash(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return "<missing>";
            }
            return PatchSet.hash(Files.readAllBytes(path));
        } catch (IOException e) {
            return "<unreadable>";
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static boolean modeMatches(Path path, FileModeSnapshot expected) {
        if (expected == null) {
            return true;
        }
        try {
            return expected.matches(path);
        } catch (IOException e) {
            return false;
        }
    }
}
