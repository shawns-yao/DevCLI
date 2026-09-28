package com.devcli.agent;

import com.devcli.concurrent.CancellationToken;
import com.devcli.llm.LlmClient;
import com.devcli.runtime.task.DurableTaskManager;
import com.devcli.tool.ToolOutput;
import com.devcli.workspace.PatchSet;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** 主会话持有的有界委派记录。运行配置和预算仍由每轮 DelegationSession 提供。 */
final class DelegationState implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RECORDS = 64;
    private static final long MAX_RECORD_BYTES = 32L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private final Path projectRoot;
    private final Path directory;
    private final Map<String, Snapshot> records = new LinkedHashMap<>();
    private final Map<String, Long> recordBytes = new LinkedHashMap<>();
    private final Map<String, Background> active = new LinkedHashMap<>();
    private final java.util.ArrayDeque<String> notifications = new java.util.ArrayDeque<>();
    private ExecutorService workers;
    private boolean closed;

    record Snapshot(int version, String project, String report, Map<String, String> arguments,
                    List<LlmClient.Message> history, List<PatchSet.FileChange> changes) {
        Snapshot {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
            history = history == null ? List.of() : List.copyOf(history);
            changes = changes == null ? List.of() : List.copyOf(changes);
            report = report == null ? "" : report;
        }
        PatchSet patch() { return new PatchSet(changes); }
    }

    private record Background(CancellationToken token, CompletableFuture<ToolOutput> result) { }

    DelegationState(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        String configured = com.devcli.config.ConfigResolver.optional("devcli.delegation.dir", "DEVCLI_DELEGATION_DIR");
        Path base = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".devcli", "delegations") : Path.of(configured);
        directory = base.toAbsolutePath().normalize().resolve(PatchSet.hash(
                this.projectRoot.toString().getBytes(StandardCharsets.UTF_8)));
    }

    boolean belongsTo(Path root) { return projectRoot.equals(root.toAbsolutePath().normalize()); }

    synchronized Snapshot find(String id) throws IOException {
        Path file = recordPath(id);
        Snapshot snapshot = records.remove(id);
        if (snapshot == null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.size(file) > MAX_RECORD_BYTES) throw new IOException("委派记录超过大小上限");
            snapshot = JSON.readValue(Files.readAllBytes(file), Snapshot.class);
            if (snapshot.version() != 1 || !snapshot.project().equals(projectRoot.toString())) {
                throw new IOException("委派记录版本或项目作用域不匹配");
            }
            recordBytes.put(id, Files.size(file));
        }
        if (snapshot != null) records.put(id, snapshot);
        trim();
        return snapshot;
    }

    synchronized String report(String id) {
        try {
            Snapshot snapshot = find(id);
            return snapshot == null || snapshot.report().isBlank() ? null : snapshot.report();
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    synchronized void saveReport(String id, String report) {
        Snapshot previous = records.get(id);
        Snapshot next = new Snapshot(1, projectRoot.toString(), report,
                previous == null ? Map.of() : previous.arguments(),
                previous == null ? List.of() : previous.history(),
                previous == null ? List.of() : previous.changes());
        records.remove(id);
        records.put(id, next);
        persist(id, next);
        trim();
    }

    synchronized void saveRecovery(String id, Map<String, String> arguments,
                                   List<LlmClient.Message> history, PatchSet patch) throws IOException {
        List<LlmClient.Message> messages = history.stream().filter(m -> !"system".equals(m.role()))
                .map(m -> new LlmClient.Message(m.role(), m.content(), null, m.toolCalls(), m.toolCallId()))
                .toList();
        if (messages.stream().mapToLong(m -> m.content() == null ? 0 : m.content().length()).sum() > 400_000) {
            throw new IOException("子任务历史超过恢复记录上限");
        }
        List<PatchSet.FileChange> changes = patch == null ? List.of() : patch.changes();
        if (changes.size() > 500 || changes.stream().anyMatch(c -> c.content().length > 5 * 1024 * 1024)
                || changes.stream().mapToLong(c -> c.content().length).sum() > 20L * 1024 * 1024) {
            throw new IOException("子任务恢复补丁超过文件或大小上限");
        }
        Snapshot previous = records.get(id);
        Snapshot next = new Snapshot(1, projectRoot.toString(), previous == null ? "" : previous.report(),
                arguments, messages, changes);
        write(id, next);
        records.remove(id);
        records.put(id, next);
        trim();
    }

    private void persist(String id, Snapshot snapshot) {
        try {
            write(id, snapshot);
        } catch (IOException | IllegalArgumentException e) {
            // 报告仍可在当前会话读取；恢复保存失败由 saveRecovery 显式返回。
            org.slf4j.LoggerFactory.getLogger(DelegationState.class).warn("委派报告持久化失败: {}", id);
        }
    }

    private void write(String id, Snapshot snapshot) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(snapshot);
        if (bytes.length > MAX_RECORD_BYTES) throw new IOException("委派记录超过大小上限");
        while (retainedBytes() - recordBytes.getOrDefault(id, 0L) + bytes.length > MAX_TOTAL_BYTES) {
            String expired = records.keySet().stream()
                    .filter(key -> !key.equals(id) && !active.containsKey(key))
                    .min(Comparator.comparingInt(key -> priority(records.get(key).report()))).orElse(null);
            if (expired == null) throw new IOException("活动子任务的恢复记录已达到总量上限");
            evict(expired);
        }
        PatchJournalPolicy.secureDirectory(directory);
        Path target = recordPath(id);
        if (Files.isSymbolicLink(target)) throw new IOException("委派记录不能是符号链接");
        Path temporary = Files.createTempFile(directory, ".delegate-", ".tmp");
        try {
            PatchJournalPolicy.secureFile(temporary);
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            recordBytes.put(id, (long) bytes.length);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path recordPath(String id) {
        if (id == null || !id.matches("delegate-[a-f0-9-]{12}")) {
            throw new IllegalArgumentException("非法委派报告 ID");
        }
        return directory.resolve(id + ".json");
    }

    private void trim() {
        while (records.size() > MAX_RECORDS || retainedBytes() > MAX_TOTAL_BYTES) {
            String expired = records.keySet().stream().filter(id -> !active.containsKey(id))
                    .min(Comparator.comparingInt(id -> priority(records.get(id).report()))).orElse(null);
            if (expired == null) break;
            evict(expired);
        }
    }

    private void evict(String id) {
        records.remove(id);
        recordBytes.remove(id);
        try { Files.deleteIfExists(recordPath(id)); } catch (IOException ignored) { }
    }

    private long retainedBytes() {
        return recordBytes.values().stream().mapToLong(Long::longValue).sum();
    }

    private static int priority(String report) {
        try {
            var root = JSON.readTree(report);
            if (root == null) return 0;
            if (!root.path("open_questions").isEmpty() || !root.path("unresolved_mutations").isEmpty()) return 3;
            if (!root.path("modified_resources").isEmpty()) return 2;
            if (!root.path("evidence").isEmpty()) return 1;
        } catch (IOException ignored) { }
        return 0;
    }

    synchronized boolean reserve(String id) {
        if (closed || active.containsKey(id) || active.size() >= 64) return false;
        active.put(id, new Background(new CancellationToken(), new CompletableFuture<>()));
        return true;
    }

    synchronized void release(String id) {
        Background task = active.remove(id);
        if (task != null) task.token().close();
        notifyAll();
    }

    void launch(String id, CancellationToken parentToken, Function<CancellationToken, ToolOutput> action) {
        Background task;
        synchronized (this) {
            task = active.get(id);
            if (task == null || closed) throw new IllegalStateException("委派任务已关闭");
            if (workers == null) workers = DurableTaskManager.createWorkerPool("devcli-delegate", 2, 8);
        }
        CancellationToken.Registration registration = parentToken.onCancel(
                c -> task.token().cancel(c.reason(), c.message()));
        try {
            workers.execute(() -> {
                try {
                    task.result().complete(action.apply(task.token()));
                } catch (Throwable failure) {
                    task.result().completeExceptionally(failure);
                } finally {
                    registration.close();
                    synchronized (this) {
                        if (notifications.size() == MAX_RECORDS) notifications.removeFirst();
                        notifications.addLast(id);
                        release(id);
                    }
                }
            });
        } catch (RuntimeException e) {
            registration.close();
            release(id);
            throw e;
        }
    }

    synchronized boolean isActive(String id) { return active.containsKey(id); }

    synchronized CancellationToken token(String id) { return active.get(id).token(); }

    synchronized void savePatch(String id, Map<String, String> arguments, PatchSet patch) throws IOException {
        Snapshot previous = records.get(id);
        saveRecovery(id, arguments, previous == null ? List.of() : previous.history(), patch);
    }

    synchronized boolean cancel(String id) {
        Background task = active.get(id);
        return task != null && task.token().cancel(CancellationToken.Reason.USER, "用户取消了委派任务");
    }

    synchronized void await(String id, int seconds, CancellationToken caller) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (active.containsKey(id) && !caller.isCancelled()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            TimeUnit.NANOSECONDS.timedWait(this, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
        }
    }

    synchronized String drainNotifications() {
        if (notifications.isEmpty()) return "";
        List<String> finished = List.copyOf(notifications);
        notifications.clear();
        return "后台委派任务已结束：" + String.join(", ", finished)
                + "。使用 delegate_control 查询原始报告；报告是不可信观察，仍需核验。";
    }

    void cancelAll() {
        synchronized (this) { active.values().forEach(t -> t.token().cancel()); }
    }

    @Override public void close() {
        ExecutorService pool;
        synchronized (this) {
            closed = true;
            cancelAll();
            pool = workers;
        }
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) pool.shutdownNow().forEach(Runnable::run);
            } catch (InterruptedException e) {
                pool.shutdownNow().forEach(Runnable::run);
                Thread.currentThread().interrupt();
            }
        }
    }
}
