package com.devcli.cli;

import com.devcli.agent.Agent;
import com.devcli.llm.LlmClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI 进程内的对话分支管理器。Runtime API 的持久分支使用 SQLite；CLI 只保留本次进程快照。
 */
final class CliConversationBranchManager {
    private final Agent agent;
    private final Map<String, List<LlmClient.Message>> branches = new LinkedHashMap<>();
    private String currentBranch = "main";

    CliConversationBranchManager(Agent agent) {
        this.agent = agent;
        branches.put(currentBranch, agent.getConversationHistory());
    }

    String currentBranch() {
        return currentBranch;
    }

    List<String> branchNames() {
        return List.copyOf(branches.keySet());
    }

    String create(String name) {
        String normalized = normalize(name);
        if (branches.containsKey(normalized)) {
            return "分支已存在: " + normalized;
        }
        branches.put(normalized, agent.getConversationHistory());
        return "已创建分支: " + normalized;
    }

    String use(String name) {
        String normalized = normalize(name);
        List<LlmClient.Message> target = branches.get(normalized);
        if (target == null) {
            return "未找到分支: " + normalized;
        }
        branches.put(currentBranch, agent.getConversationHistory());
        agent.clearHistory();
        if (target.size() > 1) {
            agent.seedHistory(target.subList(1, target.size()));
        }
        currentBranch = normalized;
        return "已切换到分支: " + normalized;
    }

    String status() {
        return "当前分支: " + currentBranch + "\n可用分支: " + String.join(", ", branches.keySet());
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("分支名称不能为空");
        }
        String normalized = value.trim();
        if (!normalized.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("分支名称只能包含字母、数字、点、下划线和连字符");
        }
        return normalized;
    }
}
