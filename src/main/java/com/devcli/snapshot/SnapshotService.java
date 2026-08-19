package com.devcli.snapshot;

import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;
import com.devcli.runtime.store.RecoveryEvidenceRef;
import com.devcli.runtime.store.RecoveryEvidenceSink;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class SnapshotService implements AutoCloseable {
    private final SideGitManager manager;
    private final ExecutorService executor;
    private volatile Future<?> lastAsyncTask;

    public SnapshotService(SideGitManager manager) {
        this.manager = manager;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "devcli-snapshot-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static SnapshotService forProject(Path projectRoot) {
        return new SnapshotService(new SideGitManager(projectRoot));
    }

    public <T> T runTurn(String mode, String input, ThrowingSupplier<T> supplier) throws Exception {
        String turnId = turnId(mode);
        String summary = summarize(mode, input);
        EvidenceContext evidence = currentEvidenceContext();
        snapshotBeforeTurn(turnId, summary, evidence);
        try {
            return supplier.get();
        } finally {
            snapshotAfterTurnAsync(turnId, summary, evidence);
        }
    }

    public void snapshotBeforeTurn(String turnId, String summary) {
        snapshotBeforeTurn(turnId, summary, currentEvidenceContext());
    }

    private TurnSnapshot snapshotBeforeTurn(String turnId, String summary,
                                            EvidenceContext evidence) {
        if (!manager.config().enabled()) {
            return null;
        }
        try {
            TurnSnapshot snapshot = manager.preTurnSnapshot(turnId, summary);
            recordEvidence(snapshot, turnId, "pre", evidence);
            return snapshot;
        } catch (Exception e) {
            System.err.println("⚠️ pre-turn 快照失败: " + e.getMessage());
            return null;
        }
    }

    public void snapshotAfterTurnAsync(String turnId, String summary) {
        snapshotAfterTurnAsync(turnId, summary, currentEvidenceContext());
    }

    private void snapshotAfterTurnAsync(String turnId, String summary,
                                        EvidenceContext evidence) {
        if (!manager.config().enabled()) {
            return;
        }
        lastAsyncTask = executor.submit(() -> {
            try {
                TurnSnapshot snapshot = manager.postTurnSnapshot(turnId, summary);
                recordEvidence(snapshot, turnId, "post", evidence);
            } catch (Exception e) {
                System.err.println("⚠️ post-turn 快照失败: " + e.getMessage());
            }
        });
    }

    public List<TurnSnapshot> listSnapshots(int limit) throws Exception {
        awaitIdle();
        return manager.listSnapshots(limit);
    }

    public RestoreResult restorePreTurn(int offset) throws Exception {
        awaitIdle();
        return manager.restorePreTurn(offset);
    }

    public String status() {
        return manager.formatStatus();
    }

    public String clean() {
        return manager.cleanSnapshots();
    }

    public SideGitManager manager() {
        return manager;
    }

    public void awaitIdle() throws Exception {
        Future<?> task = lastAsyncTask;
        if (task != null) {
            task.get(60, TimeUnit.SECONDS);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static String turnId(String mode) {
        String safeMode = mode == null || mode.isBlank() ? "turn" : mode.toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        return safeMode + "-" + Instant.now().toEpochMilli();
    }

    private static String summarize(String mode, String input) {
        String normalized = input == null ? "" : input.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 120) {
            normalized = normalized.substring(0, 120) + "...";
        }
        return "mode=" + (mode == null ? "turn" : mode) + "\ninput=" + normalized;
    }

    private static EvidenceContext currentEvidenceContext() {
        RunContext context = CancellationContext.currentRun();
        if (context == null) {
            return null;
        }
        return new EvidenceContext(context.runId(), context.threadId(), context.branchId(),
                context.evidenceSink());
    }

    private static void recordEvidence(TurnSnapshot snapshot, String turnId,
                                       String phase, EvidenceContext evidence) {
        if (snapshot == null || snapshot.commitId() == null || snapshot.commitId().isBlank()
                || evidence == null) {
            return;
        }
        String normalizedTurnId = turnId == null || turnId.isBlank() ? "turn" : turnId.trim();
        String logicalKey = normalizedTurnId + ":" + phase;
        Instant now = Instant.now();
        RecoveryEvidenceRef reference = new RecoveryEvidenceRef(
                evidence.runId(), evidence.threadId(), evidence.branchId(),
                RecoveryEvidenceRef.Kind.SIDE_GIT, logicalKey,
                snapshot.commitId(), "", RecoveryEvidenceRef.State.COMPLETED,
                now, now, 0);
        try {
            evidence.sink().record(reference);
        } catch (Exception e) {
            System.err.println("⚠️ Side-Git 恢复证据写入失败，快照已完成: " + e.getMessage());
        }
    }

    private record EvidenceContext(String runId, String threadId, String branchId,
                                   RecoveryEvidenceSink sink) {
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
