package com.devcli.runtime.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 绑定单次 Run 的恢复证据写入边界。 */
@FunctionalInterface
public interface RecoveryEvidenceSink {
    Logger LOG = LoggerFactory.getLogger(RecoveryEvidenceSink.class);

    RecoveryEvidenceSink NO_OP = ref -> { };

    void record(RecoveryEvidenceRef ref);

    /**
     * 创建固定身份的 RunStore sink。写入失败只记录 warning，不能反向破坏本地恢复产物。
     */
    static RecoveryEvidenceSink forRun(RunStore store, String runId,
                                       String threadId, String branchId) {
        if (store == null || runId == null || runId.isBlank()) {
            return NO_OP;
        }
        String normalizedRunId = runId.trim();
        String normalizedThreadId = threadId == null ? "" : threadId.trim();
        String normalizedBranchId = branchId == null || branchId.isBlank()
                ? "main" : branchId.trim();
        return ref -> {
            if (ref == null) {
                return;
            }
            RecoveryEvidenceRef scoped = new RecoveryEvidenceRef(
                    normalizedRunId,
                    normalizedThreadId,
                    normalizedBranchId,
                    ref.kind(),
                    ref.logicalKey(),
                    ref.normalizedRef(),
                    ref.sha256(),
                    ref.state(),
                    ref.createdAt(),
                    ref.updatedAt(),
                    ref.version());
            try {
                store.upsertRecoveryEvidence(scoped);
            } catch (RuntimeException e) {
                LOG.warn("恢复证据写入失败，底层恢复操作继续: run={}, kind={}, key={}, error={}",
                        normalizedRunId, scoped.kind(), scoped.logicalKey(), e.getMessage());
            }
        };
    }

    static RecoveryEvidenceSink safe(RecoveryEvidenceSink sink) {
        return sink == null ? NO_OP : sink;
    }
}
