package com.devcli.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards runtime writes performed by parallel agent steps with timeout-based lease expiration.
 */
public class ResourceLeaseManager {
    private static final Logger log = LoggerFactory.getLogger(ResourceLeaseManager.class);
    private static final long LEASE_TIMEOUT_MS = 30_000; // 30 seconds

    private final Map<Path, LeaseEntry> writeOwners = new ConcurrentHashMap<>();

    private record LeaseEntry(String stepId, long acquireTime) {}

    public void acquireWrite(String stepId, Path path) {
        if (stepId == null || stepId.isBlank() || path == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        long now = System.currentTimeMillis();

        LeaseEntry previous = writeOwners.compute(normalized, (k, oldEntry) -> {
            if (oldEntry == null) {
                return new LeaseEntry(stepId, now);
            }

            // 检查租约是否超时
            if (now - oldEntry.acquireTime > LEASE_TIMEOUT_MS) {
                log.warn("租约超时，强制回收（仅日志记录，未接审计链）: {} (被回收者: {}, 持有时间: {}ms, 新持有者: {})",
                    normalized, oldEntry.stepId, now - oldEntry.acquireTime, stepId);
                return new LeaseEntry(stepId, now);
            }

            // 同一步骤重入
            if (oldEntry.stepId.equals(stepId)) {
                return oldEntry;
            }

            // 冲突：其他步骤持有且未超时
            return oldEntry;
        });

        // 冲突检查（compute 后再抛异常，避免破坏原子性）
        if (previous != null && !previous.stepId.equals(stepId)) {
            throw new ResourceLeaseException("资源写入冲突: " + normalized
                    + " 已由步骤 [" + previous.stepId + "] 持有，当前步骤 [" + stepId + "] 不能并发写入");
        }
    }

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
        return (now - entry.acquireTime) <= LEASE_TIMEOUT_MS;
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

    /**
     * 主动清理超时租约（可选，定时任务调用）
     */
    public int pruneExpiredLeases() {
        long now = System.currentTimeMillis();
        int removed = 0;

        for (var iterator = writeOwners.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (now - entry.getValue().acquireTime > LEASE_TIMEOUT_MS) {
                log.info("清理超时租约: {} (持有者: {})", entry.getKey(), entry.getValue().stepId);
                iterator.remove();
                removed++;
            }
        }

        return removed;
    }
}
