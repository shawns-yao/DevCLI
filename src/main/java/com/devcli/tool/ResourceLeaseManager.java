package com.devcli.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards runtime writes performed by parallel agent steps with idle-timeout lease expiration.
 *
 * <p>租约语义为"空闲超时"而非"绝对超时"：同一步骤每次申请写入都会刷新租约时间戳（续租），
 * 因此正在持续写入的慢步骤不会被误判超时而被抢占；只有真正空闲超过阈值的步骤才会被回收。
 * 这避免了"合法但慢的步骤被抢占后续写入被拒"的问题。
 *
 * <p>超时阈值可配：系统属性 {@code devcli.team.lease.timeout.ms} 优先，其次环境变量
 * {@code DEVCLI_TEAM_LEASE_TIMEOUT_MS}，缺省 30000ms；非法值回退缺省。
 *
 * <p>抢占（超时回收他人租约）通过 {@link PreemptionListener} 上报，由上层接入审计链。
 * 监听回调在 {@link Map#compute} 之外执行，避免在 ConcurrentHashMap 桶锁内做文件 IO。
 */
public class ResourceLeaseManager {
    private static final Logger log = LoggerFactory.getLogger(ResourceLeaseManager.class);
    private static final long DEFAULT_LEASE_TIMEOUT_MS = 30_000; // 30 seconds
    private static final String LEASE_TIMEOUT_PROPERTY = "devcli.team.lease.timeout.ms";
    private static final String LEASE_TIMEOUT_ENV = "DEVCLI_TEAM_LEASE_TIMEOUT_MS";

    /** 抢占事件监听：超时回收他人租约时触发，供上层写入审计。默认 no-op。 */
    @FunctionalInterface
    public interface PreemptionListener {
        void onPreempt(Path path, String evictedStepId, String newStepId, long heldMs);
    }

    private final long leaseTimeoutMs;
    private final Map<Path, LeaseEntry> writeOwners = new ConcurrentHashMap<>();
    private volatile PreemptionListener preemptionListener = (p, evicted, next, held) -> {};

    private record LeaseEntry(String stepId, long acquireTime) {}

    public ResourceLeaseManager() {
        this(resolveLeaseTimeoutMs());
    }

    ResourceLeaseManager(long leaseTimeoutMs) {
        this.leaseTimeoutMs = leaseTimeoutMs > 0 ? leaseTimeoutMs : DEFAULT_LEASE_TIMEOUT_MS;
    }

    /** 注入抢占审计监听；传 null 视为清除（恢复 no-op）。 */
    public void setPreemptionListener(PreemptionListener listener) {
        this.preemptionListener = listener == null ? (p, evicted, next, held) -> {} : listener;
    }

    long leaseTimeoutMs() {
        return leaseTimeoutMs;
    }

    public void acquireWrite(String stepId, Path path) {
        if (stepId == null || stepId.isBlank() || path == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        long now = System.currentTimeMillis();
        // compute 内不做 IO；抢占信息暂存，compute 返回后再回调监听。
        PreemptionEvent[] preemption = new PreemptionEvent[1];

        LeaseEntry result = writeOwners.compute(normalized, (k, oldEntry) -> {
            if (oldEntry == null) {
                return new LeaseEntry(stepId, now);
            }

            // 同一步骤重入：续租，刷新时间戳（避免慢但活跃的步骤被误判空闲超时）
            if (oldEntry.stepId.equals(stepId)) {
                return new LeaseEntry(stepId, now);
            }

            // 他人持有但已空闲超时：抢占回收
            if (now - oldEntry.acquireTime > leaseTimeoutMs) {
                preemption[0] = new PreemptionEvent(oldEntry.stepId, now - oldEntry.acquireTime);
                return new LeaseEntry(stepId, now);
            }

            // 冲突：其他步骤持有且未超时，保持原持有者
            return oldEntry;
        });

        // 抢占审计（在 compute 之外执行 IO）
        if (preemption[0] != null) {
            PreemptionEvent event = preemption[0];
            log.warn("租约空闲超时，强制回收: {} (被回收者: {}, 空闲: {}ms, 新持有者: {})",
                    normalized, event.evictedStepId, event.heldMs, stepId);
            try {
                preemptionListener.onPreempt(normalized, event.evictedStepId, stepId, event.heldMs);
            } catch (Exception e) {
                log.warn("租约抢占审计回调失败: {}", e.getMessage());
            }
        }

        // 冲突检查（compute 后再抛异常，避免破坏原子性）
        if (result != null && !result.stepId.equals(stepId)) {
            throw new ResourceLeaseException("资源写入冲突: " + normalized
                    + " 已由步骤 [" + result.stepId + "] 持有，当前步骤 [" + stepId + "] 不能并发写入");
        }
    }

    private record PreemptionEvent(String evictedStepId, long heldMs) {}

    /**
     * 检查指定步骤是否持有指定路径的有效租约。
     * 用于 write_file 执行前二次确认，防止租约超时后旧任务仍然写入。
     */
    public boolean isLeaseValid(String stepId, Path path) {
        if (stepId == null || stepId.isBlank() || path == null) {
            return false;
        }
        Path normalized = path.toAbsolutePath().normalize();
        LeaseEntry entry = writeOwners.get(normalized);
        if (entry == null || !entry.stepId.equals(stepId)) {
            return false;
        }
        long now = System.currentTimeMillis();
        return (now - entry.acquireTime) <= leaseTimeoutMs;
    }

    public void releaseStep(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return;
        }
        writeOwners.entrySet().removeIf(entry -> stepId.equals(entry.getValue().stepId));
    }

    public void clear() {
        writeOwners.clear();
    }

    int leaseCount() {
        return writeOwners.size();
    }

    /**
     * 主动清理超时租约（可选，定时任务调用）
     */
    public int pruneExpiredLeases() {
        long now = System.currentTimeMillis();
        int removed = 0;

        for (var iterator = writeOwners.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (now - entry.getValue().acquireTime > leaseTimeoutMs) {
                log.info("清理超时租约: {} (持有者: {})", entry.getKey(), entry.getValue().stepId);
                iterator.remove();
                removed++;
            }
        }

        return removed;
    }

    private static long resolveLeaseTimeoutMs() {
        String configured = System.getProperty(LEASE_TIMEOUT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(LEASE_TIMEOUT_ENV);
        }
        if (configured != null && !configured.isBlank()) {
            try {
                long parsed = Long.parseLong(configured.trim());
                if (parsed > 0) {
                    return parsed;
                }
                log.warn("租约超时配置非正数，回退默认 {}ms: {}", DEFAULT_LEASE_TIMEOUT_MS, configured);
            } catch (NumberFormatException e) {
                log.warn("租约超时配置非法，回退默认 {}ms: {}", DEFAULT_LEASE_TIMEOUT_MS, configured);
            }
        }
        return DEFAULT_LEASE_TIMEOUT_MS;
    }
}
