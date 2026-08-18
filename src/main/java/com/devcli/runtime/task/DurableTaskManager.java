package com.devcli.runtime.task;

import com.devcli.config.ConfigResolver;
import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;
import com.devcli.runtime.RunCoordinator;
import com.devcli.runtime.api.RuntimeThreadStore;
import com.devcli.runtime.store.RunStore;
import com.devcli.runtime.store.SqliteRunStore;

import java.io.Closeable;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 后台任务提交与本地 Worker；持久状态统一由 RunStore 管理。 */
public class DurableTaskManager implements Closeable {
    private final Path dbPath;
    private final Path projectPath;
    private final TaskRunner runner;
    private final int workerCount;
    private final RunStore runStore;
    private final RunCoordinator coordinator;
    private final boolean ownsRunStore;
    private final Map<String, RunningTask> runningTasks = new ConcurrentHashMap<>();
    private ExecutorService workers;
    private volatile boolean running;

    public DurableTaskManager(Path dbPath, TaskRunner runner, int workerCount) throws SQLException {
        this(dbPath, Path.of(System.getProperty("user.dir")), runner, workerCount);
    }

    public DurableTaskManager(Path dbPath, Path projectPath,
                              TaskRunner runner, int workerCount) throws SQLException {
        this(new SqliteRunStore(dbPath), projectPath, runner, workerCount, true);
    }

    public DurableTaskManager(RunStore runStore, Path projectPath,
                              TaskRunner runner, int workerCount) {
        this(runStore, projectPath, runner, workerCount, false);
    }

    public DurableTaskManager(RunStore runStore, Path projectPath, TaskRunner runner) {
        this(runStore, projectPath, runner, workerCount(), false);
    }

    private DurableTaskManager(RunStore runStore, Path projectPath,
                               TaskRunner runner, int workerCount, boolean ownsRunStore) {
        this.runStore = java.util.Objects.requireNonNull(runStore, "runStore");
        this.coordinator = new RunCoordinator(runStore);
        this.dbPath = runStore.dbPath();
        this.projectPath = java.util.Objects.requireNonNull(projectPath, "projectPath")
                .toAbsolutePath().normalize();
        this.runner = java.util.Objects.requireNonNull(runner, "runner");
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount 必须大于 0");
        }
        this.workerCount = workerCount;
        this.ownsRunStore = ownsRunStore;
        coordinator.recoverBackgroundRuns();
    }

    public static DurableTaskManager openDefault(TaskRunner runner) throws SQLException {
        SqliteRunStore store = new SqliteRunStore(defaultDbPath());
        boolean transferred = false;
        try {
            store.importLegacyTasks(legacyDbPath());
            DurableTaskManager manager = new DurableTaskManager(
                    store,
                    Path.of(System.getProperty("user.dir")),
                    runner,
                    workerCount(),
                    true);
            transferred = true;
            return manager;
        } finally {
            if (!transferred) {
                store.close();
            }
        }
    }

    /** 新任务默认与 Runtime API 共用 runtime.db。 */
    public static Path defaultDbPath() {
        return RuntimeThreadStore.defaultDbPath();
    }

    public static Path legacyDbPath() {
        String configured = ConfigResolver.stringValue(
                "devcli.task.dir",
                "DEVCLI_TASK_DIR",
                Path.of(System.getProperty("user.home"), ".devcli", "tasks").toString());
        return Path.of(configured).resolve("tasks.db").toAbsolutePath().normalize();
    }

    private static int workerCount() {
        return ConfigResolver.intValue(
                "devcli.task.workers", "DEVCLI_TASK_WORKERS", 2, 1, 64);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        workers = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable, "devcli-task-worker");
            thread.setDaemon(true);
            return thread;
        });
        for (int index = 0; index < workerCount; index++) {
            workers.submit(this::workerLoop);
        }
    }

    public synchronized DurableTask enqueue(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("任务内容不能为空");
        }
        RunStore.RunRecord run = coordinator.submitBackground(prompt);
        notifyAll();
        return toTask(run);
    }

    public synchronized List<DurableTask> list(int limit) {
        return coordinator.listBackground(limit).stream().map(DurableTaskManager::toTask).toList();
    }

    public synchronized Optional<DurableTask> find(String id) {
        return coordinator.find(id)
                .filter(run -> run.source() == RunStore.Source.BACKGROUND)
                .map(DurableTaskManager::toTask);
    }

    public synchronized boolean cancel(String id) {
        Optional<DurableTask> current = find(id);
        if (current.isEmpty() || current.get().terminal()) {
            return false;
        }
        RunningTask runningTask = runningTasks.remove(id);
        if (runningTask != null) {
            runningTask.context().cancel();
            runningTask.thread().interrupt();
        }
        boolean canceled = coordinator.cancel(id, "用户取消");
        if (canceled) {
            notifyAll();
        }
        return canceled;
    }

    public Path dbPath() {
        return dbPath;
    }

    private void workerLoop() {
        while (running) {
            try {
                Optional<RunStore.RunRecord> claimed = coordinator.claimBackground();
                if (claimed.isEmpty()) {
                    synchronized (this) {
                        wait(300);
                    }
                    continue;
                }
                execute(claimed.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // 单个任务已在 execute 中记录终态；领取异常不应终止 Worker。
            }
        }
    }

    private void execute(RunStore.RunRecord run) {
        String taskId = run.id();
        try (RunContext context = CancellationContext.startRunContext(projectPath)) {
            runningTasks.put(taskId, new RunningTask(Thread.currentThread(), context));
            String result = runner.run(run.prompt());
            synchronized (this) {
                Optional<DurableTask> latest = find(taskId);
                if (latest.isPresent() && latest.get().status() != TaskStatus.CANCELED) {
                    coordinator.complete(taskId, result);
                }
            }
        } catch (InterruptedException e) {
            Thread.interrupted();
            synchronized (this) {
                coordinator.cancel(taskId, "任务线程被中断");
            }
        } catch (Exception e) {
            synchronized (this) {
                Optional<DurableTask> latest = find(taskId);
                if (latest.isPresent() && latest.get().status() != TaskStatus.CANCELED) {
                    coordinator.fail(taskId, e.getMessage());
                }
            }
        } finally {
            runningTasks.remove(taskId);
        }
    }

    private static DurableTask toTask(RunStore.RunRecord run) {
        return new DurableTask(
                run.id(),
                switch (run.status()) {
                    case ENQUEUED -> TaskStatus.ENQUEUED;
                    case RUNNING -> TaskStatus.RUNNING;
                    case COMPLETED -> TaskStatus.COMPLETED;
                    case FAILED, REJECTED -> TaskStatus.FAILED;
                    case CANCELED -> TaskStatus.CANCELED;
                },
                run.prompt(),
                run.result(),
                run.error(),
                run.createdAt(),
                run.startedAt(),
                run.finishedAt(),
                run.durationMs());
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
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (ownsRunStore) {
            runStore.close();
        }
    }
}
