package com.devcli.cli;

import com.devcli.agent.OrchestrationProfile;

final class CliCommandParser {

    enum CommandType {
        NONE,
        UNKNOWN_COMMAND,
        HELP,
        CANCEL,
        RUN_NOW,
        EXIT,
        CLEAR,
        HISTORY_CLEAR,
        SWITCH_MODEL,
        ORCHESTRATE,
        SWITCH_HITL,
        MEMORY_STATUS,
        MEMORY_ORGANIZE,
        MEMORY_CLEAR,
        MEMORY_FORGET,
        MEMORY_SAVE,
        MEMORY_PIN,
        RULE_ADD,
        RULE_LIST,
        RULE_REMOVE,
        INDEX_CODE,
        SEARCH_CODE,
        GRAPH_QUERY,
        CONTEXT_STATUS,
        POLICY_STATUS,
        AUDIT_TAIL,
        SNAPSHOT,
        RESTORE_SNAPSHOT,
        MCP_LIST,
        MCP_RESTART,
        MCP_LOGS,
        MCP_DISABLE,
        MCP_ENABLE,
        MCP_RESOURCES,
        MCP_PROMPTS,
        BROWSER,
        TASK,
        SKILL_LIST,
        SKILL_SHOW,
        SKILL_ON,
        SKILL_OFF,
        SKILL_RELOAD,
        CONFIG,
        SESSION,
        BRANCH
    }

    record ParsedCommand(CommandType type, String payload, OrchestrationProfile orchestrationProfile) {
        ParsedCommand(CommandType type, String payload) {
            this(type, payload, null);
        }

        static ParsedCommand none() {
            return new ParsedCommand(CommandType.NONE, null);
        }

        static ParsedCommand orchestrate(OrchestrationProfile profile, String payload) {
            return new ParsedCommand(CommandType.ORCHESTRATE, payload, profile);
        }
    }

    private CliCommandParser() {
    }

    static ParsedCommand parse(String input) {
        if (input == null) {
            return ParsedCommand.none();
        }

        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return ParsedCommand.none();
        }

        if (trimmed.equalsIgnoreCase("/help")) {
            return new ParsedCommand(CommandType.HELP, null);
        }

        if (trimmed.equalsIgnoreCase("/exit")
                || trimmed.equalsIgnoreCase("/quit")
                || trimmed.equalsIgnoreCase("exit")
                || trimmed.equalsIgnoreCase("quit")) {
            return new ParsedCommand(CommandType.EXIT, null);
        }

        if (trimmed.equalsIgnoreCase("/cancel") || trimmed.equalsIgnoreCase("cancel")) {
            return new ParsedCommand(CommandType.CANCEL, null);
        }

        if (trimmed.equalsIgnoreCase("/now")) {
            return new ParsedCommand(CommandType.RUN_NOW, null);
        }

        if (trimmed.regionMatches(true, 0, "/now ", 0, 5)) {
            return new ParsedCommand(CommandType.RUN_NOW, trimmed.substring(5).trim());
        }

        if (trimmed.equalsIgnoreCase("/clear") || trimmed.equalsIgnoreCase("clear")) {
            return new ParsedCommand(CommandType.CLEAR, null);
        }

        if (trimmed.equalsIgnoreCase("/history clear")) {
            return new ParsedCommand(CommandType.HISTORY_CLEAR, null);
        }

        if (trimmed.equalsIgnoreCase("/model")) {
            return new ParsedCommand(CommandType.SWITCH_MODEL, null);
        }

        if (trimmed.regionMatches(true, 0, "/model ", 0, 7)) {
            return new ParsedCommand(CommandType.SWITCH_MODEL, trimmed.substring(7).trim());
        }

        if (trimmed.equalsIgnoreCase("/plan")) {
            return ParsedCommand.orchestrate(OrchestrationProfile.TEAM, null);
        }

        if (trimmed.regionMatches(true, 0, "/plan ", 0, 6)) {
            String planInput = trimmed.substring(6).trim();
            if (planInput.equalsIgnoreCase("--team")) {
                return ParsedCommand.orchestrate(OrchestrationProfile.TEAM, null);
            }
            if (planInput.regionMatches(true, 0, "--team ", 0, 7)) {
                return ParsedCommand.orchestrate(
                        OrchestrationProfile.TEAM, planInput.substring(7).trim());
            }
            return ParsedCommand.orchestrate(OrchestrationProfile.TEAM, planInput);
        }

        if (trimmed.equalsIgnoreCase("/team")) {
            return ParsedCommand.orchestrate(OrchestrationProfile.TEAM, null);
        }

        if (trimmed.regionMatches(true, 0, "/team ", 0, 6)) {
            return ParsedCommand.orchestrate(
                    OrchestrationProfile.TEAM, trimmed.substring(6).trim());
        }

        if (trimmed.equalsIgnoreCase("/hitl on")) {
            return new ParsedCommand(CommandType.SWITCH_HITL, "on");
        }

        if (trimmed.equalsIgnoreCase("/hitl off")) {
            return new ParsedCommand(CommandType.SWITCH_HITL, "off");
        }

        if (trimmed.equalsIgnoreCase("/hitl")) {
            return new ParsedCommand(CommandType.SWITCH_HITL, null);
        }

        if (trimmed.equalsIgnoreCase("/memory") || trimmed.equalsIgnoreCase("/mem")) {
            return new ParsedCommand(CommandType.MEMORY_STATUS, null);
        }

        if (trimmed.equalsIgnoreCase("/memory organize")
                || trimmed.equalsIgnoreCase("/mem organize")) {
            return new ParsedCommand(CommandType.MEMORY_ORGANIZE, "dry-run");
        }

        if (trimmed.equalsIgnoreCase("/memory organize apply")
                || trimmed.equalsIgnoreCase("/mem organize apply")) {
            return new ParsedCommand(CommandType.MEMORY_ORGANIZE, "apply");
        }

        if (trimmed.equalsIgnoreCase("/memory clear") || trimmed.equalsIgnoreCase("/mem clear")) {
            return new ParsedCommand(CommandType.MEMORY_CLEAR, null);
        }

        // /memory forget <id>：删除单条长期记忆，配合自动写入提示里给出的 id
        if (trimmed.regionMatches(true, 0, "/memory forget", 0, "/memory forget".length())
                || trimmed.regionMatches(true, 0, "/mem forget", 0, "/mem forget".length())) {
            String id = trimmed.substring(trimmed.indexOf("forget") + "forget".length()).trim();
            return new ParsedCommand(CommandType.MEMORY_FORGET, id);
        }

        if (trimmed.equalsIgnoreCase("/save")) {
            return new ParsedCommand(CommandType.MEMORY_SAVE, null);
        }

        // /save --pin <事实>：写入 Sticky pinned facts，永久注入 system prompt
        // /save -p <事实>：短形式
        if (trimmed.regionMatches(true, 0, "/save --pin ", 0, 12)) {
            return new ParsedCommand(CommandType.MEMORY_PIN, trimmed.substring(12).trim());
        }
        if (trimmed.regionMatches(true, 0, "/save -p ", 0, 9)) {
            return new ParsedCommand(CommandType.MEMORY_PIN, trimmed.substring(9).trim());
        }

        if (trimmed.regionMatches(true, 0, "/save ", 0, 6)) {
            return new ParsedCommand(CommandType.MEMORY_SAVE, trimmed.substring(6).trim());
        }

        if (trimmed.equalsIgnoreCase("/rule add")) {
            return new ParsedCommand(CommandType.RULE_ADD, null);
        }
        if (trimmed.regionMatches(true, 0, "/rule add ", 0, 10)) {
            return new ParsedCommand(CommandType.RULE_ADD, trimmed.substring(10).trim());
        }
        if (trimmed.equalsIgnoreCase("/rule") || trimmed.equalsIgnoreCase("/rule list")) {
            return new ParsedCommand(CommandType.RULE_LIST, null);
        }
        if (trimmed.equalsIgnoreCase("/rule remove")) {
            return new ParsedCommand(CommandType.RULE_REMOVE, null);
        }
        if (trimmed.regionMatches(true, 0, "/rule remove ", 0, 13)) {
            return new ParsedCommand(CommandType.RULE_REMOVE, trimmed.substring(13).trim());
        }

        if (trimmed.equalsIgnoreCase("/index")) {
            return new ParsedCommand(CommandType.INDEX_CODE, null);
        }

        if (trimmed.regionMatches(true, 0, "/index ", 0, 7)) {
            return new ParsedCommand(CommandType.INDEX_CODE, trimmed.substring(7).trim());
        }

        if (trimmed.equalsIgnoreCase("/search")) {
            return new ParsedCommand(CommandType.SEARCH_CODE, null);
        }

        if (trimmed.regionMatches(true, 0, "/search ", 0, 8)) {
            return new ParsedCommand(CommandType.SEARCH_CODE, trimmed.substring(8).trim());
        }

        if (trimmed.equalsIgnoreCase("/graph")) {
            return new ParsedCommand(CommandType.GRAPH_QUERY, null);
        }

        if (trimmed.regionMatches(true, 0, "/graph ", 0, 7)) {
            return new ParsedCommand(CommandType.GRAPH_QUERY, trimmed.substring(7).trim());
        }

        if (trimmed.equalsIgnoreCase("/context") || trimmed.equalsIgnoreCase("/ctx")) {
            return new ParsedCommand(CommandType.CONTEXT_STATUS, null);
        }

        if (trimmed.equalsIgnoreCase("/policy")) {
            return new ParsedCommand(CommandType.POLICY_STATUS, null);
        }

        if (trimmed.equalsIgnoreCase("/config")) {
            return new ParsedCommand(CommandType.CONFIG, null);
        }

        if (trimmed.equalsIgnoreCase("/audit")) {
            return new ParsedCommand(CommandType.AUDIT_TAIL, null);
        }

        if (trimmed.regionMatches(true, 0, "/audit ", 0, 7)) {
            return new ParsedCommand(CommandType.AUDIT_TAIL, trimmed.substring(7).trim());
        }

        if (trimmed.equalsIgnoreCase("/snapshot")) {
            return new ParsedCommand(CommandType.SNAPSHOT, "list");
        }

        if (trimmed.regionMatches(true, 0, "/snapshot ", 0, 10)) {
            return new ParsedCommand(CommandType.SNAPSHOT, trimmed.substring(10).trim());
        }

        if (trimmed.equalsIgnoreCase("/restore")) {
            return new ParsedCommand(CommandType.RESTORE_SNAPSHOT, null);
        }

        if (trimmed.regionMatches(true, 0, "/restore ", 0, 9)) {
            return new ParsedCommand(CommandType.RESTORE_SNAPSHOT, trimmed.substring(9).trim());
        }

        if (trimmed.equalsIgnoreCase("/browser")) {
            return new ParsedCommand(CommandType.BROWSER, "status");
        }

        if (trimmed.regionMatches(true, 0, "/browser ", 0, 9)) {
            return new ParsedCommand(CommandType.BROWSER, trimmed.substring(9).trim());
        }

        if (trimmed.equalsIgnoreCase("/task")) {
            return new ParsedCommand(CommandType.TASK, "list");
        }

        if (trimmed.regionMatches(true, 0, "/task ", 0, 6)) {
            return new ParsedCommand(CommandType.TASK, trimmed.substring(6).trim());
        }

        if (trimmed.equalsIgnoreCase("/skill") || trimmed.equalsIgnoreCase("/skill list")) {
            return new ParsedCommand(CommandType.SKILL_LIST, null);
        }

        if (trimmed.equalsIgnoreCase("/session")) {
            return new ParsedCommand(CommandType.SESSION, "status");
        }
        if (trimmed.regionMatches(true, 0, "/session ", 0, 9)) {
            return new ParsedCommand(CommandType.SESSION, trimmed.substring(9).trim());
        }

        if (trimmed.equalsIgnoreCase("/branch")) {
            return new ParsedCommand(CommandType.BRANCH, "status");
        }
        if (trimmed.regionMatches(true, 0, "/branch ", 0, 8)) {
            return new ParsedCommand(CommandType.BRANCH, trimmed.substring(8).trim());
        }

        if (trimmed.equalsIgnoreCase("/skill reload")) {
            return new ParsedCommand(CommandType.SKILL_RELOAD, null);
        }

        if (trimmed.regionMatches(true, 0, "/skill show ", 0, 12)) {
            return new ParsedCommand(CommandType.SKILL_SHOW, trimmed.substring(12).trim());
        }

        if (trimmed.regionMatches(true, 0, "/skill on ", 0, 10)) {
            return new ParsedCommand(CommandType.SKILL_ON, trimmed.substring(10).trim());
        }

        if (trimmed.regionMatches(true, 0, "/skill off ", 0, 11)) {
            return new ParsedCommand(CommandType.SKILL_OFF, trimmed.substring(11).trim());
        }

        if (trimmed.equalsIgnoreCase("/mcp")) {
            return new ParsedCommand(CommandType.MCP_LIST, null);
        }

        if (trimmed.regionMatches(true, 0, "/mcp resources ", 0, 15)) {
            return new ParsedCommand(CommandType.MCP_RESOURCES, trimmed.substring(15).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp prompts ", 0, 13)) {
            return new ParsedCommand(CommandType.MCP_PROMPTS, trimmed.substring(13).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp restart ", 0, 13)) {
            return new ParsedCommand(CommandType.MCP_RESTART, trimmed.substring(13).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp logs ", 0, 10)) {
            return new ParsedCommand(CommandType.MCP_LOGS, trimmed.substring(10).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp disable ", 0, 13)) {
            return new ParsedCommand(CommandType.MCP_DISABLE, trimmed.substring(13).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp enable ", 0, 12)) {
            return new ParsedCommand(CommandType.MCP_ENABLE, trimmed.substring(12).trim());
        }

        if (trimmed.startsWith("/")) {
            return new ParsedCommand(CommandType.UNKNOWN_COMMAND, trimmed);
        }

        return ParsedCommand.none();
    }
}
