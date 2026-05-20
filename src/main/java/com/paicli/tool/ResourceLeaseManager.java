package com.paicli.tool;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards runtime writes performed by parallel agent steps.
 */
public class ResourceLeaseManager {
    private final Map<Path, String> writeOwners = new ConcurrentHashMap<>();

    public void acquireWrite(String stepId, Path path) {
        if (stepId == null || stepId.isBlank() || path == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        String previous = writeOwners.putIfAbsent(normalized, stepId);
        if (previous != null && !previous.equals(stepId)) {
            throw new ResourceLeaseException("资源写入冲突: " + normalized
                    + " 已由步骤 [" + previous + "] 持有，当前步骤 [" + stepId + "] 不能并发写入");
        }
    }

    public void releaseStep(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return;
        }
        writeOwners.entrySet().removeIf(entry -> stepId.equals(entry.getValue()));
    }

    public void clear() {
        writeOwners.clear();
    }
}
