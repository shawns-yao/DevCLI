package com.devcli.runtime.task;

import com.devcli.runtime.RunCoordinator;
import com.devcli.runtime.RunContext;
import com.devcli.runtime.store.RunRecord;
import com.devcli.runtime.store.RunStatus;
import com.devcli.runtime.store.SqliteRunStore;
import com.devcli.runtime.store.SubmissionSource;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 后台 Run 的兼容提交器与 Worker，不再拥有独立数据库或状态机。 */
public class DurableTaskManager implements Closeable {
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);

    private final Path dbPath;
    private final Path projectPath;
    private final TaskRunner runner;
    private final int workerCount;
    private final SqliteRunStore store;
    private final RunCoordinator coordinator;
    private final Map<String, RunningTask> runningTasks = new ConcurrentHashMap<>();
    private ExecutorService workers;
    private volatile boolean running;

    public DurableTaskManager(Path dbPath, TaskRunner runner, int workerCount) throws SQLException {
        this(dbPath, Path.of(System.getProperty("user.dir")), runner, workerCount);
    }

    public DurableTaskManager(Path dbPath, Path projectPath,
                              TaskRunner runner, int workerCount) throws SQLException {
        this.dbPath = dbPath.toAbsolutePath().normalize();
        this.projectPath = projectPath.toAbsolutePath().normalize();
        this.runner = java.util.Objects.requireNonNull(runner, "runner");
        this.workerCount = Math.max(1, workerCount);
        this.store = new SqliteRunStore(this.dbPath);
        this.coordinator = new RunCoordinator(store, LEASE_DURATION);
        coordinator.reconcileStartup();
        migrateAdjacentLegacyTasks();
    }

    public static DurableTaskManager openDefault(TaskRunner runner) throws SQLException {
        Path unified = SqliteRunStore.defaultDbPath();
        DurableTaskManager manager = new DurableTaskManager(
                unified, Path.of(System.getProperty("user.dir")), runner, workerCount());
        manager.store.migrateLegacyTasks(defaultLegacyDbPath());
        return manager;
    }

    /** 兼容旧配置名；返回值已经改为统一 runtime.db。 */
    public static Path defaultDbPath() {
        return SqliteRunStore.defaultDbPath();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        workers = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable, "devcli-task-worker");
            thread.setDaemon(true);
            return thread;
        });
        for (int index = 0; index < workerCount; index++) workers.submit(this::workerLoop);
    }

    public synchronized DurableTask enqueue(String prompt) {
        RunRecord run = coordinator.submit(
                SubmissionSource.BACKGROUND, projectPath, prompt, "", "");
        notifyAll();
        return toTask(run);
    }

    public synchronized List<DurableTask> list(int limit) {
        return store.list(SubmissionSource.BACKGROUND, limit).stream()
                .map(DurableTaskManager::toTask).toList();
    }

    public synchronized Optional<DurableTask> find(String id) {
        return store.find(id)
                .filter(run -> run.source() == SubmissionSource.BACKGROUND)
                .map(DurableTaskManager::toTask);
    }

    public synchronized boolean cancel(String id) {
        RunRecord current = store.find(id).orElse(null);
        if (current == null || current.terminal()
                || current.source() != SubmissionSource.BACKGROUND) return false;
        RunningTask task = runningTasks.remove(id);
        if (task != null) {
            task.context().cancel();
            task.thread().interrupt();
        }
        RunRecord latest = store.find(id).orElse(current);
        boolean canceled = store.cancel(id, latest.version(), "用户取消",
                task == null ? null : task.context().budgetState());
        if (!canceled) {
            RunRecord afterRace = store.find(id).orElse(null);
            canceled = afterRace != null && afterRace.status() == RunStatus.CANCELED;
        }
        if (canceled) notifyAll();
        return canceled;
    }

    public Path dbPath() {
        return dbPath;
    }

    RunCoordinator coordinator() {
        return coordinator;
    }

    private void workerLoop() {
        String workerId = "background-" + UUID.randomUUID();
        while (running) {
            try {
                Optional<RunCoordinator.ClaimedRunContext> next =
                        coordinator.claimNext(SubmissionSource.BACKGROUND, workerId);
                if (next.isEmpty()) {
                    synchronized (this) {
                        wait(300);
                    }
                    continue;
                }
                execute(next.get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // 单个 Run 的失败在统一 RunStore 中收口，Worker 保持存活。
            }
        }
    }

    private void execute(RunCoordinator.ClaimedRunContext claimed) {
        String runId = claimed.run().id();
        RunContext context = claimed.context();
        runningTasks.put(runId, new RunningTask(Thread.currentThread(), context));
        try (claimed) {
            String result = runner.run(claimed.run().prompt());
            RunRecord latest = store.find(runId).orElse(claimed.run());
            if (latest.status() != RunStatus.CANCELED) {
                coordinator.complete(claimed, RunStatus.COMPLETED, result, "");
            }
        } catch (InterruptedException interrupted) {
            Thread.interrupted();
            cancelIfActive(runId, context, "任务线程被中断");
        } catch (Exception error) {
            RunRecord latest = store.find(runId).orElse(null);
            if (latest != null && latest.status() == RunStatus.RUNNING) {
                coordinator.complete(claimed, RunStatus.FAILED, "", error.getMessage());
            }
        } finally {
            runningTasks.remove(runId);
        }
    }

    private void cancelIfActive(String runId, RunContext context, String reason) {
        RunRecord latest = store.find(runId).orElse(null);
        if (latest != null && !latest.terminal()) {
            store.cancel(runId, latest.version(), reason, context.budgetState());
        }
    }

    private void migrateAdjacentLegacyTasks() {
        if (!"tasks.db".equalsIgnoreCase(dbPath.getFileName().toString())) return;
        // 直接传入旧文件名的测试或嵌入式调用仍可使用；表不存在时迁移器无操作。
        store.importLegacyTasksFromCurrentDatabase();
    }

    private static Path defaultLegacyDbPath() {
        String configured = System.getProperty("devcli.task.dir");
        if (configured == null || configured.isBlank()) configured = System.getenv("DEVCLI_TASK_DIR");
        if (configured == null || configured.isBlank()) {
            configured = Path.of(System.getProperty("user.home"), ".devcli", "tasks").toString();
        }
        return Path.of(configured).resolve("tasks.db");
    }

    private static int workerCount() {
        String configured = System.getProperty("devcli.task.workers");
        if (configured == null || configured.isBlank()) configured = System.getenv("DEVCLI_TASK_WORKERS");
        if (configured == null || configured.isBlank()) return 2;
        try {
            return Math.max(1, Integer.parseInt(configured.trim()));
        } catch (NumberFormatException ignored) {
            return 2;
        }
    }

    private static DurableTask toTask(RunRecord run) {
        return new DurableTask(
                run.id(), TaskStatus.from(run.status().value()), run.prompt(),
                run.result(), run.error(), run.createdAt(), run.startedAt(),
                run.finishedAt(), run.durationMs());
    }

    private record RunningTask(Thread thread, RunContext context) {
    }

    @Override
    public synchronized void close() {
        running = false;
        notifyAll();
        runningTasks.values().forEach(task -> task.context().cancel());
        if (workers != null) {
            workers.shutdownNow();
            try {
                workers.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        store.close();
    }
}
