package com.devcli.tool;

/** 主注册表与其 fork 共享的写入交接状态；不替代工作区租约或 PatchSet 校验。 */
public final class SynchronousWorkerGate {
    private boolean workerRunning;
    private int mainMutations;

    public synchronized Lease tryEnterWorker() {
        if (workerRunning || mainMutations != 0) return null;
        workerRunning = true;
        return new Lease(true);
    }

    synchronized Lease tryEnterMainMutation() {
        if (workerRunning) return null;
        mainMutations++;
        return new Lease(false);
    }

    public final class Lease implements AutoCloseable {
        private final boolean worker;
        private boolean closed;

        private Lease(boolean worker) { this.worker = worker; }

        @Override public void close() {
            synchronized (SynchronousWorkerGate.this) {
                if (closed) return;
                closed = true;
                if (worker) workerRunning = false;
                else mainMutations--;
            }
        }
    }
}
