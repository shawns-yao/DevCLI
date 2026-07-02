package com.devcli.tool.provider;

import com.devcli.snapshot.RestoreResult;
import com.devcli.tool.ToolRegistry;

public final class SnapshotToolProvider implements ToolProvider {
    @Override
    public void register(ToolContext context) {
        context.registerTool(new ToolRegistry.Tool(
                "revert_turn",
                "恢复到 Side-Git 记录的最近第 N 个 pre-turn 快照。会先记录 pre-restore 快照；属于高危写入操作，必须经 HITL 审批。",
                context.createToolParameters(new ToolParameter("offset", "integer", "要恢复的 pre-turn 快照序号，1 表示最近一次任务开始前", false)),
                args -> {
                    int offset = parseInt(args.get("offset"), 1);
                    try {
                        RestoreResult result = context.snapshotService().restorePreTurn(Math.max(1, offset));
                        return result.formatForCli();
                    } catch (Exception e) {
                        return "恢复快照失败: " + e.getMessage();
                    }
                }
        ));
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
