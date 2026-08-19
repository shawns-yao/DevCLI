package com.devcli.runtime;

import com.devcli.runtime.store.RecoveryEvidenceSink;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次 Agent 执行的隔离上下文。
 *
 * <p>运行级状态必须由调用链显式绑定到当前线程，不能通过进程级全局变量共享。
 * 子线程可继承上下文；预先创建的线程池不会读取其他运行的状态。</p>
 */
public final class RunContext implements AutoCloseable {
    private final String runId;
    private final String threadId;
    private final String branchId;
    private final Path projectPath;
    private final CancellationToken cancellationToken;
    private final RecoveryEvidenceSink evidenceSink;
    private final RunContext previous;
    private final Deque<AutoCloseable> ownedResources = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    RunContext(Path projectPath, RunContext previous) {
        this(projectPath, newRunId(), "", "main", RecoveryEvidenceSink.NO_OP, previous);
    }

    RunContext(Path projectPath, String runId, String threadId, String branchId,
               RecoveryEvidenceSink evidenceSink, RunContext previous) {
        this.runId = runId == null || runId.isBlank() ? newRunId() : runId.trim();
        this.threadId = threadId == null ? "" : threadId.trim();
        this.branchId = branchId == null || branchId.isBlank() ? "main" : branchId.trim();
        this.projectPath = Objects.requireNonNull(projectPath, "projectPath").toAbsolutePath().normalize();
        this.cancellationToken = new CancellationToken();
        this.evidenceSink = RecoveryEvidenceSink.safe(evidenceSink);
        this.previous = previous;
    }

    public static String newRunId() {
        return "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public String runId() {
        return runId;
    }

    public String threadId() {
        return threadId;
    }

    public String branchId() {
        return branchId;
    }

    public Path projectPath() {
        return projectPath;
    }

    public CancellationToken cancellationToken() {
        return cancellationToken;
    }

    public RecoveryEvidenceSink evidenceSink() {
        return evidenceSink;
    }

    public boolean isCancelled() {
        return cancellationToken.isCancelled();
    }

    public void cancel() {
        cancellationToken.cancel();
    }

    public synchronized <T extends AutoCloseable> T own(T resource) {
        if (resource == null) {
            return null;
        }
        if (closed.get()) {
            throw new IllegalStateException("RunContext 已关闭，不能继续注册资源");
        }
        ownedResources.push(resource);
        return resource;
    }

    RunContext previous() {
        return previous;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        while (true) {
            AutoCloseable resource;
            synchronized (this) {
                resource = ownedResources.pollFirst();
            }
            if (resource == null) {
                break;
            }
            try {
                resource.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = new IllegalStateException("关闭运行资源失败", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        CancellationContext.clear(this);
        if (failure != null) {
            throw failure;
        }
    }
}
