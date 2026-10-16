package com.devcli.cli;

import com.devcli.agent.Agent;
import com.devcli.browser.BrowserAuditMetadata;
import com.devcli.config.DevCliConfig;
import com.devcli.hitl.SwitchableHitlHandler;
import com.devcli.llm.LlmClient;
import com.devcli.render.Renderer;
import com.devcli.render.StatusInfo;
import com.devcli.mcp.McpServerManager;
import com.devcli.mcp.McpServerStatus;
import com.devcli.hitl.ApprovalPolicy;
import com.devcli.policy.AuditLog;
import com.devcli.policy.PermissionMode;
import com.devcli.snapshot.RestoreResult;
import com.devcli.snapshot.SnapshotService;
import com.devcli.snapshot.TurnSnapshot;
import com.devcli.skill.SkillRegistry;
import com.devcli.util.AnsiStyle;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.devcli.cli.CliApplication.*;
import static com.devcli.cli.TerminalInputSupport.*;
import static com.devcli.cli.CliTurnRunner.*;
import static com.devcli.cli.CliPlanReview.*;
import static com.devcli.cli.CliSettings.*;

/** 命令结果、状态与启动界面的文本呈现。 */
final class CliDisplay {
    private static final Logger log = LoggerFactory.getLogger(CliDisplay.class);

    record StartupScreenInfo(
            String model,
            String provider,
            long mcpReady,
            int mcpTotal,
            int mcpTools,
            int skillsEnabled,
            int skillsTotal,
            String note
    ) {
    }

    static List<String> startupHints() {
        return List.of(
                "输入你的问题或任务",
                "输入 '/' 后按 Tab 补全命令",
                "输入 '@server:protocol://path' 可显式引用 MCP resource",
                "任务运行中按 ESC 取消当前任务",
                "默认模式是 ReAct"
        );
    }

    record SlashCommandHint(String insertText, String display, String description) {
    }

    /** 记忆写入提示里只展示摘要，避免长句刷屏；完整内容用 /memory 查看。 */
    static String abbreviateMemoryNotice(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", " ").replace('\r', ' ')
                .replace('\n', ' ').trim().replaceAll("\\s+", " ");
        return normalized.length() <= 60 ? normalized : normalized.substring(0, 57) + "...";
    }

    static List<SlashCommandHint> slashCommandHints() {
        return List.of(
                new SlashCommandHint("/model", "/model", "查看当前模型"),
                new SlashCommandHint("/model anthropic", "/model anthropic", "切换到 Anthropic Messages"),
                new SlashCommandHint("/model openai", "/model openai", "切换到 OpenAI 兼容端点"),
                new SlashCommandHint("/model glm-5.1", "/model glm-5.1", "切换到 GLM-5.1"),
                new SlashCommandHint("/model glm-5v-turbo", "/model glm-5v-turbo", "切换到 GLM-5V-Turbo 多模态"),
                new SlashCommandHint("/model deepseek", "/model deepseek", "切换到 DeepSeek（读取配置模型）"),
                new SlashCommandHint("/model step", "/model step", "切换到 StepFun（读取配置模型）"),
                new SlashCommandHint("/model kimi", "/model kimi", "切换到 Kimi（读取配置模型）"),
                new SlashCommandHint("/now ", "/now <任务内容>", "中断活动轮次并优先执行；空闲时直接执行"),
                new SlashCommandHint("/cancel", "/cancel", "取消当前活动轮次"),
                new SlashCommandHint("/plan", "/plan", "下一条任务使用 Plan 模式"),
                new SlashCommandHint("/plan ", "/plan <任务内容>", "直接用 Plan 模式执行这条任务"),
                new SlashCommandHint("/plan resume", "/plan resume [id]", "恢复中断的 Plan 任务"),
                new SlashCommandHint("/mode", "/mode", "查看当前权限模式与可选值"),
                new SlashCommandHint("/mode default", "/mode default", "危险操作逐个询问"),
                new SlashCommandHint("/mode plan", "/mode plan", "只读：不暴露写入与命令工具"),
                new SlashCommandHint("/mode acceptEdits", "/mode acceptEdits", "自动放行编辑类工具，其余仍询问"),
                new SlashCommandHint("/mode bypassPermissions", "/mode bypassPermissions", "全部直接放行，不询问"),
                new SlashCommandHint("/mode dontAsk", "/mode dontAsk", "不询问；未获授权的动作一律拒绝"),
                new SlashCommandHint("/mode auto", "/mode auto", "由分类器逐个判断；分类器不可用时一律拒绝"),
                new SlashCommandHint("/grant", "/grant", "查看持久基线与本轮授权例外"),
                new SlashCommandHint("/grant write", "/grant write", "本轮追加授权写入项目内文件"),
                new SlashCommandHint("/grant write ", "/grant write <路径 glob>", "本轮追加授权给定项目相对路径"),
                new SlashCommandHint("/grant net ", "/grant net <域名>", "本轮追加授权访问该域名及其子域"),
                new SlashCommandHint("/grant all", "/grant all", "本轮追加授权项目内写入"),
                new SlashCommandHint("/grant status", "/grant status", "查看基线、本轮例外与放行缓存"),
                new SlashCommandHint("/grant off", "/grant off", "取消本轮例外并清空放行缓存"),
                new SlashCommandHint("/browser", "/browser", "查看浏览器会话状态"),
                new SlashCommandHint("/browser connect", "/browser connect", "复用已允许远程调试的登录态 Chrome"),
                new SlashCommandHint("/browser connect ", "/browser connect <port>", "旧式 CDP 端口连接"),
                new SlashCommandHint("/browser status", "/browser status", "查看浏览器会话状态"),
                new SlashCommandHint("/browser tabs", "/browser tabs", "查看 shared 模式真实 Chrome tab"),
                new SlashCommandHint("/browser disconnect", "/browser disconnect", "切回 isolated 浏览器模式"),
                new SlashCommandHint("/task", "/task", "查看后台任务列表"),
                new SlashCommandHint("/task add ", "/task add <任务内容>", "提交后台任务"),
                new SlashCommandHint("/task cancel ", "/task cancel <task_id>", "取消后台任务"),
                new SlashCommandHint("/task log ", "/task log <task_id>", "查看后台任务结果"),
                new SlashCommandHint("/mcp", "/mcp", "查看 MCP server 状态"),
                new SlashCommandHint("/mcp restart ", "/mcp restart <name>", "重启 MCP server"),
                new SlashCommandHint("/mcp logs ", "/mcp logs <name>", "查看 MCP server 日志"),
                new SlashCommandHint("/mcp disable ", "/mcp disable <name>", "禁用 MCP server"),
                new SlashCommandHint("/mcp enable ", "/mcp enable <name>", "启用 MCP server"),
                new SlashCommandHint("/mcp resources ", "/mcp resources <name>", "查看 MCP resources"),
                new SlashCommandHint("/mcp prompts ", "/mcp prompts <name>", "查看 MCP prompts"),
                new SlashCommandHint("/policy", "/policy", "查看安全策略状态"),
                new SlashCommandHint("/config", "/config", "打开配置 palette（只读视图 + 切换提示）"),
                new SlashCommandHint("/audit", "/audit", "查看今日最近 10 条危险工具审计"),
                new SlashCommandHint("/audit ", "/audit [N]", "查看今日最近 N 条危险工具审计"),
                new SlashCommandHint("/snapshot", "/snapshot", "查看最近 Side-Git 快照"),
                new SlashCommandHint("/snapshot status", "/snapshot status", "查看 Side-Git 快照状态"),
                new SlashCommandHint("/snapshot clean", "/snapshot clean", "清理当前项目 Side-Git 快照"),
                new SlashCommandHint("/session", "/session", "查看持久会话状态"),
                new SlashCommandHint("/session tree", "/session tree", "查看持久会话树"),
                new SlashCommandHint("/session fork ", "/session fork <name> [eventId]", "从当前节点创建分支"),
                new SlashCommandHint("/session use ", "/session use <branch>", "切换到持久分支"),
                new SlashCommandHint("/session new", "/session new", "创建新会话"),
                new SlashCommandHint("/session clear", "/session clear", "清空当前上下文并保留旧树"),
                new SlashCommandHint("/branch", "/branch", "/session 的兼容别名"),
                new SlashCommandHint("/restore ", "/restore <N>", "恢复到最近第 N 个 pre-turn 快照"),
                new SlashCommandHint("/trace", "/trace", "查看最近一次运行的执行追踪"),
                new SlashCommandHint("/trace list", "/trace list", "列出最近运行"),
                new SlashCommandHint("/trace ", "/trace <runId>", "查看指定运行时间线"),
                new SlashCommandHint("/index", "/index", "索引当前代码库"),
                new SlashCommandHint("/index ", "/index [路径]", "索引指定路径代码库"),
                new SlashCommandHint("/search ", "/search <查询>", "语义检索代码"),
                new SlashCommandHint("/graph ", "/graph <类名>", "查看代码关系图谱"),
                new SlashCommandHint("/clear", "/clear", "清空当前对话历史"),
                new SlashCommandHint("/history clear", "/history clear", "清空本机输入历史和会话归档"),
                new SlashCommandHint("/context", "/context", "查看上下文和记忆状态"),
                new SlashCommandHint("/compact", "/compact", "压缩旧对话，保留最近 1 轮原文"),
                new SlashCommandHint("/compact --keep ", "/compact --keep <N>", "压缩旧对话，保留最近 N 轮原文"),
                new SlashCommandHint("/memory", "/memory", "查看记忆状态"),
                new SlashCommandHint("/memory export", "/memory export", "导出可读 Markdown 记忆审计快照"),
                new SlashCommandHint("/memory organize", "/memory organize", "查看过期项与作用域冲突统计"),
                new SlashCommandHint("/memory organize apply", "/memory organize apply", "重建全局与项目记忆索引"),
                new SlashCommandHint("/memory clear", "/memory clear", "清空长期记忆"),
                new SlashCommandHint("/memory forget", "/memory forget <id>", "删除单条长期记忆"),
                new SlashCommandHint("/save ", "/save <事实内容>", "手动保存关键事实到长期记忆"),
                new SlashCommandHint("/rule add ", "/rule add <强约束>", "添加每轮强制执行的规则"),
                new SlashCommandHint("/rule list", "/rule list", "查看规则和旧 pinned 待迁移项"),
                new SlashCommandHint("/rule remove ", "/rule remove <id>", "删除显式规则"),
                new SlashCommandHint("/skill", "/skill", "查看 skill 列表"),
                new SlashCommandHint("/skill list", "/skill list", "查看 skill 列表"),
                new SlashCommandHint("/skill show ", "/skill show <name>", "查看 SKILL.md 全文"),
                new SlashCommandHint("/skill on ", "/skill on <name>", "启用 skill"),
                new SlashCommandHint("/skill off ", "/skill off <name>", "禁用 skill"),
                new SlashCommandHint("/skill trust project", "/skill trust project", "信任项目 skill 目录"),
                new SlashCommandHint("/skill untrust project", "/skill untrust project", "取消信任项目 skill 目录"),
                new SlashCommandHint("/skill reload", "/skill reload", "重新扫描 skill 目录"),
                new SlashCommandHint("/help", "/help", "查看命令帮助"),
                new SlashCommandHint("/exit", "/exit", "退出 DevCLI"),
                new SlashCommandHint("/quit", "/quit", "退出 DevCLI")
        );
    }

    static void printGrant(com.devcli.policy.TaskGrant exception, PrintStream ui) {
        ui.println("🎫 本轮例外：" + describeGrant(exception));
        ui.println("   例外只作用于下一条任务（含编排），执行后自动还原。");
        ui.println("   跨会话生效的持久授权写在 ~/.devcli/config.json 的 permissions.allow 里。");
        ui.println("   策略边界（项目根、命令黑名单）不受任何授权影响，越界仍会被直接拒绝。\n");
    }

    static void printGrantUsage(PrintStream ui, com.devcli.policy.TaskGrant exception) {
        ui.println("🎫 本轮例外：" + describeGrant(exception));
        ui.println("   /grant write             - 本轮追加授权写入项目内任意文件");
        ui.println("   /grant write src/** ...  - 本轮追加授权给定项目相对路径");
        ui.println("   /grant net github.com    - 本轮追加授权访问该域名及其子域");
        ui.println("   /grant all               - 本轮追加授权项目内写入");
        ui.println("   /grant status            - 查看本轮例外、规则层与放行缓存");
        ui.println("   /grant off               - 取消本轮例外并清空放行缓存");
        ui.println("   只作用于下一条任务；跨会话的持久授权请改用 permissions.allow 规则。\n");
    }

    static void printGrantStatus(PrintStream ui,
                                         com.devcli.policy.TaskGrant exception,
                                         SwitchableHitlHandler hitlHandler) {
        java.util.Set<String> tools = hitlHandler.approvedAllTools();
        java.util.Set<String> servers = hitlHandler.approvedAllServers();
        ui.println("🎫 授权状态：");
        ui.println("   本轮例外：" + describeGrant(exception));
        ui.println("   规则层（持久）：" + (permissionRuleSummary.isEmpty()
                ? "无（只按逐次审批判定）" : permissionRuleSummary));
        if (tools.isEmpty() && servers.isEmpty()) {
            ui.println("   放行缓存：空");
        } else {
            ui.println("   放行缓存：工具 " + (tools.isEmpty() ? "无" : String.join("、", tools))
                    + "；MCP server " + (servers.isEmpty() ? "无" : String.join("、", servers)));
        }
        ui.println("   放行缓存只在本轮任务内有效，每轮任务开始时自动清空。\n");
    }

    static String describeGrant(com.devcli.policy.TaskGrant grant) {
        String compact = grant == null ? "" : grant.compactSummary();
        return compact.isEmpty() ? "无（需要审批的操作仍逐次确认）" : compact;
    }

    static void printSlashCommandHelp() {        printSlashCommandHelp(System.out);
    }

    static void printSlashCommandHelp(PrintStream out) {
        out.println("可用命令：");
        for (SlashCommandHint hint : slashCommandHints()) {
            out.println("   " + hint.display() + " - " + hint.description());
        }
        out.println();
    }

    static String formatSlashCommandChoices(int terminalWidth) {
        List<String> commands = slashCommandHints().stream()
                .map(SlashCommandHint::display)
                .distinct()
                .toList();
        int maxLen = commands.stream().mapToInt(String::length).max().orElse(12);
        int colWidth = Math.min(Math.max(maxLen + 4, 18), Math.max(18, terminalWidth));
        int columns = Math.max(1, Math.min(4, terminalWidth / colWidth));
        int rows = (int) Math.ceil(commands.size() / (double) columns);

        StringBuilder sb = new StringBuilder();
        sb.append("可用命令（Tab 补全，Enter 执行）：\n");
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int index = col * rows + row;
                if (index >= commands.size()) {
                    continue;
                }
                String command = commands.get(index);
                sb.append(command);
                if (col < columns - 1) {
                    sb.append(" ".repeat(Math.max(2, colWidth - command.length())));
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * /config 命令处理：用 renderer.openPalette 展示当前配置项列表。
     * 当前是只读视图——选中一项后提示对应的 CLI 命令，由用户自己执行。
     */
    static void handleConfigPalette(Renderer renderer,
                                            DevCliConfig config,
                                            LlmClient llmClient,
                                            PermissionMode permissionMode,
                                            com.devcli.skill.SkillRegistry skillRegistry) {
        var items = java.util.List.of(
                "模型: " + (llmClient == null ? "(none)" : llmClient.getModelName() + " / " + llmClient.getProviderName()),
                "默认 Provider: " + (config == null ? "(none)" : config.getDefaultProvider()),
                "权限模式: " + (permissionMode == null ? "(none)" : permissionMode.id()),
                "Skill 启用数: " + (skillRegistry == null ? 0 : skillRegistry.enabledSkills().size()),
                "渲染器: " + renderer.getClass().getSimpleName(),
                "配置文件: ~/.devcli/config.json (只读视图，编辑请用编辑器)"
        );
        int selected = renderer.openPalette("配置 / config", items);
        if (selected < 0) {
            renderer.stream().println("(已关闭)");
            return;
        }
        String hint = switch (selected) {
            case 0, 1 -> "💡 默认 Anthropic Messages: /model anthropic；其它: /model openai|glm-5.1|deepseek|step|kimi";
            case 2 -> "💡 切换权限模式: /mode " + String.join("|", PermissionMode.ids());
            case 3 -> "💡 管理 Skill: /skill list / /skill on <name> / /skill off <name>";
            case 4 -> "💡 切换渲染器（重启后生效）: DEVCLI_RENDERER=inline|plain";
            case 5 -> "💡 当前不在终端内编辑 config.json，建议在编辑器里改完重启";
            default -> "(unknown)";
        };
        renderer.stream().println(hint);
    }

    static void printPolicyStatus(PrintStream out, Agent reactAgent) {
        out.println("🛡️ 安全策略状态：");
        out.println("   项目根: " + reactAgent.getToolRegistry().getProjectPath());
        out.println("   危险工具: " + String.join(", ", ApprovalPolicy.getDangerousTools()) + "，以及所有 mcp__ 前缀工具");
        out.println("   路径围栏: 强制限定在项目根之内（read_file / write_file / list_dir / create_project）");
        out.println("   命令黑名单: sudo / rm -rf 全盘 / mkfs / dd of=/dev / fork bomb / curl|sh / find / / chmod 777 / / shutdown");
        out.println("   写入文件上限: 5MB");
        out.println("   命令执行上限: 60 秒，输出 8KB（截断）");
        out.println("   审计目录: " + reactAgent.getToolRegistry().getAuditLog().getAuditDir());
        out.println();
    }

    static void printMcpCommandResult(PrintStream out, String result) {
        out.println(result);
        out.println();
    }

    static void printAuditTail(PrintStream out, Agent reactAgent, String payload) {
        int requested = parseAuditCount(payload, 10);
        List<AuditLog.AuditEntry> entries = reactAgent.getToolRegistry().getAuditLog().readRecent(requested);
        if (entries.isEmpty()) {
            out.println("📭 今日尚无审计记录\n");
            return;
        }
        out.println("📋 最近 " + entries.size() + " 条危险工具审计：");
        for (AuditLog.AuditEntry entry : entries) {
            out.printf("   [%s] %s %s (%dms, approver=%s)%n",
                    entry.outcome().toUpperCase(),
                    entry.timestamp(),
                    entry.tool(),
                    entry.durationMs(),
                    entry.approver());
            if (entry.reason() != null && !entry.reason().isBlank()) {
                out.println("        原因: " + entry.reason());
            }
            BrowserAuditMetadata metadata = entry.metadata();
            if (metadata != null) {
                out.println("        浏览器: mode=" + metadata.browserMode()
                        + ", sensitive=" + metadata.sensitive()
                        + (metadata.targetUrl() == null ? "" : ", url=" + metadata.targetUrl()));
            }
        }
        out.println();
    }

    static void printSnapshotCommand(PrintStream out, SnapshotService snapshotService, String payload) {
        String normalized = payload == null || payload.isBlank() ? "list" : payload.trim().toLowerCase();
        if ("status".equals(normalized)) {
            out.println(snapshotService.status());
            out.println();
            return;
        }
        if ("clean".equals(normalized)) {
            out.println(snapshotService.clean());
            out.println();
            return;
        }
        if (!"list".equals(normalized)) {
            out.println("""
                    ❌ 未知 /snapshot 子命令: %s
                    可用命令：
                      /snapshot
                      /snapshot status
                      /snapshot clean
                      /restore <N>
                    """.formatted(payload).trim());
            out.println();
            return;
        }
        try {
            List<TurnSnapshot> snapshots = snapshotService.listSnapshots(20);
            if (snapshots.isEmpty()) {
                out.println("📭 暂无 Side-Git 快照\n");
                return;
            }
            out.println("📸 最近 " + snapshots.size() + " 条 Side-Git 快照：");
            int preTurnIndex = 0;
            for (TurnSnapshot snapshot : snapshots) {
                String restoreHint = "";
                if ("pre-turn".equals(snapshot.phase().label())) {
                    preTurnIndex++;
                    restoreHint = "  /restore " + preTurnIndex;
                }
                out.printf("   %s %-11s %-18s %s%s%n",
                        snapshot.shortCommitId(),
                        snapshot.phase().label(),
                        snapshot.turnId(),
                        snapshot.createdAt(),
                        restoreHint);
            }
            out.println();
        } catch (Exception e) {
            out.println("❌ 读取快照失败: " + e.getMessage() + "\n");
        }
    }

    static void printRestoreCommand(PrintStream out, SnapshotService snapshotService, String payload) {
        int offset = parseAuditCount(payload, 1);
        try {
            RestoreResult result = snapshotService.restorePreTurn(offset);
            out.println(result.formatForCli());
            out.println();
        } catch (Exception e) {
            out.println("❌ 恢复快照失败: " + e.getMessage() + "\n");
        }
    }

    static int parseAuditCount(String payload, int defaultN) {
        if (payload == null || payload.isBlank()) return defaultN;
        try {
            int n = Integer.parseInt(payload.trim());
            return Math.max(1, Math.min(n, 100));
        } catch (NumberFormatException e) {
            return defaultN;
        }
    }

    static void printStartupHints(PrintStream out) {
        out.println("💡 提示:");
        for (String hint : startupHints()) {
            out.println("   - " + hint);
        }
        out.println();
    }

    static StartupScreenInfo startupScreenInfo(LlmClient llmClient,
                                                       McpServerManager mcpServerManager,
                                                       SkillRegistry skillRegistry,
                                                       String note) {
        long ready = mcpServerManager.servers().stream()
                .filter(server -> server.status() == McpServerStatus.READY)
                .count();
        int total = mcpServerManager.servers().size();
        int tools = mcpServerManager.servers().stream()
                .mapToInt(server -> server.tools().size())
                .sum();
        int skillTotal = skillRegistry.allSkills().size();
        int skillEnabled = skillRegistry.enabledSkills().size();
        return new StartupScreenInfo(
                llmClient.getModelName(),
                llmClient.getProviderName(),
                ready,
                total,
                tools,
                skillEnabled,
                skillTotal,
                note == null ? "" : note.trim()
        );
    }

    static StatusInfo statusInfo(LlmClient llmClient,
                                         PermissionMode permissionMode,
                                         String phase,
                                         McpServerManager mcpServerManager,
                                         SkillRegistry skillRegistry) {
        String normalizedPhase = phase == null || phase.isBlank() ? "idle" : phase;
        String modeId = permissionMode == null ? null : permissionMode.id();
        StatusInfo base = "idle".equals(normalizedPhase)
                ? StatusInfo.idle(llmClient.getModelName(), llmClient.maxContextWindow(), modeId)
                : StatusInfo.active(llmClient.getModelName(), llmClient.maxContextWindow(),
                modeId, normalizedPhase);
        return base.withEnvironment(mcpStatusSummary(mcpServerManager), skillStatusSummary(skillRegistry))
                .withGrantSummary(permissionRuleSummary);
    }

    static String mcpStatusSummary(McpServerManager mcpServerManager) {
        if (mcpServerManager == null || mcpServerManager.servers().isEmpty()) {
            return "MCP 0";
        }
        long ready = mcpServerManager.servers().stream()
                .filter(server -> server.status() == McpServerStatus.READY)
                .count();
        return "MCP " + ready + "/" + mcpServerManager.servers().size();
    }

    static String skillStatusSummary(SkillRegistry skillRegistry) {
        if (skillRegistry == null || skillRegistry.allSkills().isEmpty()) {
            return "Skill 0";
        }
        return "Skill " + skillRegistry.enabledSkills().size() + "/" + skillRegistry.allSkills().size();
    }

    static String appendStartupNote(String current, String next) {
        if (next == null || next.isBlank()) {
            return current == null ? "" : current;
        }
        if (current == null || current.isBlank()) {
            return next;
        }
        return current + "\n" + next;
    }

    static void printStartupScreen(PrintStream out, StartupScreenInfo info) {
        for (String line : startupScreenLines(info)) {
            out.println(line);
        }
    }

    static List<String> startupScreenLines(StartupScreenInfo info) {
        List<String> lines = new ArrayList<>(startupBannerLines(info));
        lines.add("");
        return lines;
    }

    static List<String> startupBannerLines() {
        return startupBannerLines(new StartupScreenInfo(
                "auto",
                "model",
                0,
                0,
                0,
                0,
                0,
                ""));
    }

    static List<String> startupBannerLines(StartupScreenInfo info) {
        String model = info.model() == null || info.model().isBlank() ? "auto" : info.model();
        String provider = info.provider() == null || info.provider().isBlank() ? "model" : info.provider();
        String mcp = info.mcpTotal() <= 0
                ? "MCP not configured"
                : "MCP " + info.mcpReady() + "/" + info.mcpTotal() + " · " + info.mcpTools() + " tools";
        String skills = info.skillsTotal() <= 0
                ? "0 skills"
                : info.skillsEnabled() + "/" + info.skillsTotal() + " skills";
        String ready = "Model " + model + " (" + provider + ")";
        String state = mcp + " · " + skills;
        List<String> lines = new ArrayList<>(List.of(
                AnsiStyle.emphasis("DevCLI") + "  " + AnsiStyle.subtle("v" + VERSION),
                AnsiStyle.subtle(ready),
                AnsiStyle.subtle(state),
                AnsiStyle.subtle(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString()),
                "",
                AnsiStyle.subtle("/help  ·  /model  ·  /mcp  ·  /skill")
        ));
        if (info.note() != null && !info.note().isBlank()) {
            lines.add("");
            lines.add(AnsiStyle.subtle(info.note().replace('\n', ' ')));
        }
        return lines;
    }
}
