package com.devcli.snapshot;

import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;

import java.time.Instant;

public record TurnSnapshot(
        String commitId,
        SnapshotPhase phase,
        String turnId,
        Instant createdAt,
        String summary
) {
    public String shortCommitId() {
        return commitId == null || commitId.length() <= 10 ? commitId : commitId.substring(0, 10);
    }

    /** 快照正文仍归 Side-Git；RunStore 只保存可对账引用。 */
    public void linkToCurrentRun() {
        RunContext context = CancellationContext.currentRun();
        if (context == null || commitId == null || commitId.isBlank()) return;
        try {
            context.persistenceSink().saveRecoveryReferences("", "", "side-git:" + commitId);
        } catch (Exception ignored) {
            // 快照本体已经成功，不因索引引用写入失败改变快照结果。
        }
    }
}
