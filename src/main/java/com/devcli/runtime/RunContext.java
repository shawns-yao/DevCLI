package com.devcli.runtime;

import com.devcli.budget.PricingCatalog;
import com.devcli.budget.RunBudget;
import com.devcli.budget.RunBudgetPolicy;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.observability.MetricRecorder;
import com.devcli.observability.RunTelemetry;

import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Instant;
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
    private final Path projectPath;
    private final CancellationToken cancellationToken;
    private final RunBudget runBudget;
    private volatile RunEventSink eventSink = RunEventSink.NO_OP;
    private volatile AttemptPersistence attemptPersistence = AttemptPersistence.NO_OP;
    private volatile RunPersistenceSink persistenceSink = RunPersistenceSink.NO_OP;
    private volatile MetricRecorder metricRecorder = MetricRecorder.NO_OP;
    private volatile RunTelemetry telemetry;
    private volatile String parentAttemptId = "";
    private final RunContext previous;
    private final Deque<AutoCloseable> ownedResources = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    RunContext(Path projectPath, RunContext previous) {
        this("run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                projectPath, previous, null);
    }

    RunContext(String runId, Path projectPath, RunContext previous,
               RunBudgetState restoredBudgetState) {
        this.runId = requireRunId(runId);
        this.projectPath = Objects.requireNonNull(projectPath, "projectPath").toAbsolutePath().normalize();
        this.cancellationToken = new CancellationToken();
        this.runBudget = restoredBudgetState == null
                ? RunBudget.create(this.runId, RunBudgetPolicy.fromConfiguration(), PricingCatalog.empty())
                : restoreBudget(this.runId, restoredBudgetState);
        this.previous = previous;
        this.telemetry = new RunTelemetry(this.runId, "", "", "", "", this.runId);
    }

    public String runId() {
        return runId;
    }

    public Path projectPath() {
        return projectPath;
    }

    public CancellationToken cancellationToken() {
        return cancellationToken;
    }

    public RunBudget runBudget() {
        return runBudget;
    }

    public RunEventSink eventSink() {
        return eventSink;
    }

    public AttemptPersistence attemptPersistence() {
        return attemptPersistence;
    }

    public RunPersistenceSink persistenceSink() {
        return persistenceSink;
    }

    public String parentAttemptId() {
        return parentAttemptId;
    }

    public RunTelemetry telemetry() {
        return telemetry;
    }

    public MetricRecorder metricRecorder() {
        return metricRecorder;
    }

    public RunContext configureObservability(RunTelemetry telemetry, MetricRecorder metrics) {
        this.telemetry = (telemetry == null ? RunTelemetry.empty() : telemetry)
                .merge(this.telemetry);
        this.metricRecorder = MetricRecorder.safe(metrics);
        return this;
    }

    public RunContext configureRuntimeServices(RunEventSink events,
                                               AttemptPersistence attempts,
                                               RunPersistenceSink persistence,
                                               String parentAttemptId) {
        RunEventSink configured = events == null ? RunEventSink.NO_OP : events;
        this.eventSink = telemetry == null
                ? configured : RunEventSink.contextual(configured, telemetry);
        this.attemptPersistence = attempts == null ? AttemptPersistence.NO_OP : attempts;
        this.persistenceSink = persistence == null ? RunPersistenceSink.NO_OP : persistence;
        this.parentAttemptId = parentAttemptId == null ? "" : parentAttemptId;
        return this;
    }

    public RunBudgetState budgetState() {
        RunBudget.Snapshot snapshot = runBudget.snapshot();
        return new RunBudgetState(
                RunBudgetState.CURRENT_SCHEMA_VERSION,
                runId,
                runBudget.policy(),
                snapshot,
                Instant.now());
    }

    /** RunStore 使用的稳定持久化载荷；恢复时不得清零已消耗预算。 */
    public record RunBudgetState(int schemaVersion, String runId,
                                 RunBudgetPolicy policy, RunBudget.Snapshot usage,
                                 Instant updatedAt) {
        public static final int CURRENT_SCHEMA_VERSION = 1;

        public RunBudgetState {
            schemaVersion = Math.max(1, schemaVersion);
            runId = runId == null ? "" : runId;
            policy = Objects.requireNonNull(policy, "policy");
            usage = usage == null
                    ? new RunBudget.Snapshot(0, 0, 0, 0, 0, 0,
                    BigDecimal.ZERO, "unknown", 0, 0,
                    RunBudget.Decision.CONTINUE, "")
                    : usage;
            updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        }
    }

    private static RunBudget restoreBudget(String runId, RunBudgetState state) {
        if (!state.runId().isBlank() && !runId.equals(state.runId())) {
            throw new IllegalArgumentException("预算状态不属于当前 run");
        }
        return RunBudget.restore(runId, state.policy(), PricingCatalog.empty(), state.usage());
    }

    private static String requireRunId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        return value.trim();
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
