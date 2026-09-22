package com.devcli.tool.patch;

import java.util.ArrayList;
import java.util.List;

/** 在自动审批前识别补丁中的删除与改名，避免把高风险结构变更交给模型放行。 */
public final class ApplyPatchRiskInspector {

    private static final int MAX_DISPLAY_PATHS = 8;

    private ApplyPatchRiskInspector() {
    }

    /**
     * @return 需要人工确认时的风险说明；普通新增与内容修改返回 {@code null}
     */
    public static String explicitApprovalNotice(String patch) {
        List<PatchHunk> hunks;
        try {
            hunks = ApplyPatchParser.parse(patch);
        } catch (PatchException invalid) {
            // 参数与补丁语义仍由正式工具链拒绝；无效补丁不在审批层重复维护错误协议。
            return null;
        }

        int deletions = 0;
        int moves = 0;
        List<String> affected = new ArrayList<>();
        for (PatchHunk hunk : hunks) {
            if (hunk instanceof PatchHunk.DeleteFile delete) {
                deletions++;
                appendBounded(affected, delete.path());
            } else if (hunk instanceof PatchHunk.UpdateFile update
                    && update.movePath() != null && !update.movePath().isBlank()) {
                moves++;
                appendBounded(affected, update.path() + " -> " + update.movePath());
            }
        }
        if (deletions == 0 && moves == 0) {
            return null;
        }
        String suffix = affected.isEmpty() ? "" : "；目标: " + String.join(", ", affected);
        return "检测到补丁结构变更，必须单次确认；删除 " + deletions + " 个文件，改名 " + moves
                + " 个文件" + suffix + "。删除不进入回收站，恢复依赖已有快照或备份。";
    }

    private static void appendBounded(List<String> affected, String path) {
        if (affected.size() < MAX_DISPLAY_PATHS) {
            affected.add(path);
        }
    }
}
