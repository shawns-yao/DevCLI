package com.devcli.cli;

import com.devcli.tool.ToolContract;
import com.devcli.agent.Agent;
import com.devcli.agent.OrchestrationProfile;
import com.devcli.browser.BrowserConnectivityCheck;
import com.devcli.browser.BrowserGuard;
import com.devcli.browser.BrowserSession;
import com.devcli.browser.SensitivePagePolicy;
import com.devcli.config.DevCliConfig;
import com.devcli.extension.ExtensionRegistry;
import com.devcli.extension.ExtensionContract;
import com.devcli.cli.turn.TerminalInputCoordinator;
import com.devcli.agent.AgentTurnInbox;
import com.devcli.hitl.HitlToolRegistry;
import com.devcli.hitl.SwitchableHitlHandler;
import com.devcli.render.RendererHitlHandler;
import com.devcli.hitl.TerminalHitlHandler;
import com.devcli.hook.HookConfigLoader;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmClientFactory;
import com.devcli.render.Renderer;
import com.devcli.render.RendererFactory;
import com.devcli.render.inline.InlineRenderer;
import com.devcli.render.inline.FixedInputLineReader;
import com.devcli.render.inline.TerminalCapabilities;
import com.devcli.mcp.McpServer;
import com.devcli.mcp.McpServerManager;
import com.devcli.mcp.McpServerStatus;
import com.devcli.mcp.mention.AtMentionExpander;
import com.devcli.rag.CodeIndex;
import com.devcli.policy.PermissionMode;
import com.devcli.rag.CodeRetriever;
import com.devcli.rag.CodeRelation;
import com.devcli.rag.SearchResultFormatter;
import com.devcli.runtime.AgentSessionRuntime;
import com.devcli.runtime.api.RuntimeThreadStore;
import com.devcli.runtime.task.DurableTaskManager;
import com.devcli.runtime.task.TaskCommandFormatter;
import com.devcli.session.SessionTreeService;
import com.devcli.snapshot.SnapshotService;
import com.devcli.skill.SkillRegistry;
import com.devcli.skill.SkillContextBuffer;
import com.devcli.memory.RuleContext;
import com.devcli.tool.ToolRegistry;
import org.jline.terminal.Terminal;
import org.jline.reader.LineReader;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.devcli.cli.TerminalInputSupport.*;
import static com.devcli.cli.CliTurnRunner.*;
import static com.devcli.cli.CliPlanReview.*;
import static com.devcli.cli.CliSettings.*;
import static com.devcli.cli.CliDisplay.*;

/** CLI 启动装配与主交互循环。 */
final class CliApplication {
    private static final Logger log = LoggerFactory.getLogger(CliApplication.class);

    static final com.devcli.trace.RunEventTraceSink TRACE_SINK =
            new com.devcli.trace.RunEventTraceSink();

    public static void main(String[] args) {
        configureAwtForCli();
        if (RuntimeCommandLauncher.isServeCommand(args)) {
            configureLogging();
            RuntimeCommandLauncher.startAndBlock(args);
            return;
        }

        configureLogging();

        if (PrintCommandLauncher.isPrintCommand(args)) {
            int code = PrintCommandLauncher.execute(args,
                    new PrintStream(System.out, true, StandardCharsets.UTF_8), System.err);
            if (code != 0) System.exit(code);
            return;
        }

        DevCliConfig config = DevCliConfig.load();
        com.devcli.policy.PermissionRuleSet permissionRules = loadPermissionRules(config);
        permissionRuleSummary = permissionRules.compactSummary();
        LlmClient llmClient = LlmClientFactory.createFromConfig(config);
        if (llmClient == null) {
            System.err.println("❌ 错误: 未找到可用的 API Key");
            System.err.println("请在 .env 文件中添加 ANTHROPIC_AUTH_TOKEN、OPENAI_API_KEY、GLM_API_KEY、DEEPSEEK_API_KEY、STEP_API_KEY 或 KIMI_API_KEY");
            return;
        }
        AtomicReference<LlmClient> llmClientRef = new AtomicReference<>(llmClient);

        try (Terminal terminal = buildTerminal()) {
            TerminalHitlHandler terminalHitlHandler = new TerminalHitlHandler(false);
            SwitchableHitlHandler hitlHandler = new SwitchableHitlHandler(terminalHitlHandler);
            // 审批通道在有终端交互时始终可用：「要不要问」由权限模式决定，不再由这个开关决定。
            // 关掉它会让本该询问的动作静默阻塞在 stdin，比直接拒绝更难排查。
            hitlHandler.setEnabled(true);
            HitlToolRegistry hitlToolRegistry = new HitlToolRegistry(hitlHandler)
                    .withPermissionRules(permissionRules)
                    .withPermissionMode(startupPermissionMode(config))
                    .withPermissionClassifier(permissionClassifier(llmClientRef));
            BrowserSession browserSession = new BrowserSession();
            BrowserConnectivityCheck browserConnectivityCheck = new BrowserConnectivityCheck();
            hitlToolRegistry.setBrowserGuard(new BrowserGuard(browserSession, new SensitivePagePolicy()));
            McpServerManager mcpServerManager = new McpServerManager(hitlToolRegistry, Path.of("."));
            ExtensionRegistry extensionRegistry = new ExtensionRegistry();
            mcpServerManager.setExtensionObserver(servers -> extensionRegistry.replaceKind(
                    ExtensionContract.Kind.MCP_SERVER,
                    servers.stream().map(server -> ExtensionRegistry.fromMcpServer(
                            server.name(), server.config())).toList()));
            AtomicReference<SkillRegistry> skillRegistryRef = new AtomicReference<>();
            BrowserCommandHandler browserCommands = new BrowserCommandHandler(
                    browserSession, browserConnectivityCheck, mcpServerManager,
                    hitlToolRegistry, hitlHandler);
            hitlToolRegistry.setBrowserConnector(browserCommands);

            Renderer renderer = RendererFactory.create(RendererFactory.resolveMode(), terminal);
            org.jline.reader.impl.LineReaderImpl lineReader = renderer instanceof InlineRenderer && TerminalCapabilities.supportsScrollRegion(terminal)
                    ? new FixedInputLineReader(terminal)
                    : new org.jline.reader.impl.LineReaderImpl(terminal);
            lineReader.setHistory(new DevCliHistory());
            lineReader.setCompleter(new DevCliCompleter(mcpServerManager::resourceCandidates,
                            () -> skillRegistryRef.get() == null ? List.of() : skillRegistryRef.get().allSkills(),
                            () -> extensionRegistry));
            lineReader.setHighlighter(new DevCliHighlighter());
            lineReader.option(LineReader.Option.BRACKETED_PASTE, true);
            lineReader.option(LineReader.Option.AUTO_LIST, true);
            lineReader.option(LineReader.Option.AUTO_MENU, true);
            configureHistory(lineReader, Path.of(System.getProperty("user.home")));
            configureSlashCommandHint(lineReader);
            configureJLineInteractiveWidgets(lineReader);

            // JLine-first：启动输出、命令输出、Agent 流式内容都走同一条 Renderer.stream() 通道。
            // inline 首屏要挂到 LineReader 首次初始化回调里，避免在 readLine 接管屏幕前用裸输出抢光标。
            TerminalInputCoordinator terminalInput = new TerminalInputCoordinator(terminal, lineReader);
            RendererHitlHandler rendererHitl = new RendererHitlHandler(renderer, hitlHandler.isEnabled(),
                    request -> terminalInput.interact(() -> renderer.promptApproval(request)));
            hitlHandler.setDelegate(rendererHitl);
            renderer.bindLineReader(lineReader);
            PrintStream ui = renderer.stream();
            renderer.start();
            var projectPermissions = com.devcli.config.ProjectPermissionsStore.openDefault();
            CliProjectTrust.bind(hitlToolRegistry, projectPermissions, Path.of("."), hitlHandler);
            renderer.updateStatus(statusInfo(llmClient, hitlToolRegistry.currentPermissionMode(), "idle", mcpServerManager, null));

            // 统一关停协调器:JVM 多个 shutdown hook 无顺序保证,
            // 这里全部收敛到单一 hook,按显式 order 关闭(依赖方先关,被依赖资源后关)。
            ShutdownCoordinator shutdown = new ShutdownCoordinator();

            String startupNote = "";
            try {
                McpConfigBootstrapResult bootstrapResult = ensureDefaultMcpConfig(Path.of(System.getProperty("user.home")));
                if (!bootstrapResult.message().isBlank()) {
                    startupNote = bootstrapResult.message();
                }
                mcpServerManager.loadConfiguredServers();
                mcpServerManager.startAll(ui, mcpStartupWait());
                shutdown.register(30, "mcpServerManager", mcpServerManager::close);
            } catch (Exception e) {
                startupNote = "MCP 初始化失败: " + e.getMessage();
            }
            AtMentionExpander mentionExpander = new AtMentionExpander(mcpServerManager, Path.of("."));
            LocalPathMentionExpander localPathMentionExpander = new LocalPathMentionExpander(Path.of("."));

            // === Skill 系统初始化 ===
            Path home = Path.of(System.getProperty("user.home"));
            Path skillsCacheDir = home.resolve(".devcli/skills-cache");
            Path userSkillsDir = home.resolve(".devcli/skills");
            Path projectSkillsDir = Path.of(".devcli/skills").toAbsolutePath();
            try {
                new com.devcli.skill.SkillBuiltinExtractor(skillsCacheDir,
                        diagnostic -> TRACE_SINK.emit(new com.devcli.event.RunEvent.CustomMessage(
                                "skill.diagnostic", diagnostic.message(), java.util.Map.of(
                                "code", diagnostic.code(), "path", diagnostic.path())))).extractAll();
            } catch (Exception e) {
                startupNote = appendStartupNote(startupNote, "内置 skill 解压失败: " + e.getMessage());
            }
            com.devcli.skill.SkillStateStore skillStateStore = new com.devcli.skill.SkillStateStore(home.resolve(".devcli/skills.json"));
            skillStateStore.setDiagnosticSink(diagnostic -> TRACE_SINK.emit(
                    new com.devcli.event.RunEvent.CustomMessage(
                            "skill.diagnostic", diagnostic.message(), java.util.Map.of(
                            "code", diagnostic.code(), "path", diagnostic.path()))));
            com.devcli.skill.SkillRegistry skillRegistry = new com.devcli.skill.SkillRegistry(
                    skillsCacheDir, userSkillsDir, projectSkillsDir, skillStateStore,
                    com.devcli.skill.SkillRegistry.sharedUserDirectories(home));
            skillRegistry.setAvailableDependencies(
                    () -> hitlToolRegistry.searchableTools().stream()
                            .map(com.devcli.tool.ToolContract.Tool::name)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    () -> mcpServerManager.servers().stream()
                            .filter(server -> server.status() == com.devcli.mcp.McpServerStatus.READY)
                            .map(com.devcli.mcp.McpServer::name)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            skillRegistry.setDiagnosticSink(diagnostic -> TRACE_SINK.emit(
                    new com.devcli.event.RunEvent.CustomMessage(
                            "skill.diagnostic", diagnostic.message(), java.util.Map.of(
                            "code", diagnostic.code(), "path", diagnostic.path()))));
            skillRegistry.reload();
            skillRegistryRef.set(skillRegistry);
            skillRegistry.allSkills().forEach(skill -> extensionRegistry.registerOrReplace(
                    ExtensionRegistry.fromSkill(skill)));
            try {
                syncHookExtensions(extensionRegistry);
            } catch (Exception e) {
                startupNote = appendStartupNote(startupNote, "Hook 目录加载失败: " + e.getMessage());
            }
            slashCommandHints().forEach(hint -> extensionRegistry.registerOrReplace(
                    ExtensionRegistry.command(hint.insertText(), hint.description())));
            com.devcli.skill.SkillContextBuffer skillContextBuffer = new com.devcli.skill.SkillContextBuffer();
            hitlToolRegistry.setSkillRegistry(skillRegistry);
            hitlToolRegistry.setSkillContextBuffer(skillContextBuffer);

            Agent reactAgent = new Agent(llmClient, hitlToolRegistry);
            // Execution Trace：结构化运行事件自动落 ~/.devcli/traces，/trace 查看。
            reactAgent.setRunEventSink(TRACE_SINK);
            AgentSessionRuntime reactSession = AgentSessionRuntime.adoptOwned(
                    reactAgent, Path.of(reactAgent.getToolRegistry().getProjectPath()));
            RuntimeThreadStore cliRunStore = RuntimeCommandLauncher.openRuntimeStore();
            shutdown.register(15, "cliRunStore", cliRunStore::close);
            SessionTreeService sessionTree = SessionTreeService.open(reactAgent, cliRunStore);
            CliSessionArchive sessionArchive = CliSessionArchive.fromEnvironment();
            shutdown.register(20, "reactSession", reactSession::close);
            reactAgent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
            reactAgent.setSkillRegistry(skillRegistry);
            reactAgent.setSkillContextBuffer(skillContextBuffer);

            // 长期记忆写入可见化：显式保存、证据失效与 Curator 晋升落库都必须可见。
            reactAgent.getMemoryManager().setAutoSaveListener(fact -> ui.println(
                    "🧠 已记入长期记忆 [" + fact.memoryType() + "/" + fact.source() + "]: "
                            + abbreviateMemoryNotice(fact.content())
                            + "\n   规则: " + fact.reasonCode()
                            + " | 删除: /memory forget " + fact.id()));

            // === Rule Context 初始化 ===
            // 规则文件和显式强约束每轮注入，但不计入记忆层。
            com.devcli.memory.RuleContext ruleContext = new com.devcli.memory.RuleContext(
                    com.devcli.memory.LongTermMemory.resolveMemoryDir());
            ruleContext.reloadFiles(java.nio.file.Path.of(".").toAbsolutePath().normalize());
            reactAgent.setRuleContextSupplier(ruleContext::renderForPrompt);

            DurableTaskManager taskManager = RuntimeCommandLauncher.openTaskManager(llmClientRef, cliRunStore);
            taskManager.start();
            // 后台任务管理器可能仍在驱动无头 Agent(依赖 MCP / 记忆资源),必须最先关闭。
            shutdown.register(10, "taskManager", taskManager::close);
            renderer.updateStatus(statusInfo(llmClient, hitlToolRegistry.currentPermissionMode(), "idle", mcpServerManager, skillRegistry));
            StartupScreenInfo startupScreenInfo = startupScreenInfo(llmClient, mcpServerManager, skillRegistry, startupNote);
            if (renderer instanceof InlineRenderer inline) {
                inline.installStartupScreen(startupScreenLines(startupScreenInfo));
            } else {
                printStartupScreen(ui, startupScreenInfo);
            }
            OrchestrationProfile nextTaskOrchestrationProfile = null;
            com.devcli.policy.TaskGrant nextTaskGrant = com.devcli.policy.TaskGrant.NONE;
            AgentTurnInbox turnInbox = reactAgent.getTurnInbox();
            String pendingDraft = "";

            reactAgent.setRenderer(renderer);
            reactAgent.setPermissionModeSupplier(() -> hitlToolRegistry.currentPermissionMode().id());
            reactAgent.getToolRegistry().setWriteFileObserver(
                    (path, ba) -> renderer.appendDiff(path, ba[0], ba[1]));

            // Day 3：inline 模式绑 Ctrl+O 到 BlockRegistry.toggleLast 实现折叠块展开/收起
            boolean spaciousPrompt = false;
            if (renderer instanceof InlineRenderer inline) {
                bindCtrlOToFoldableBlocks(lineReader, inline);
            }
            spaciousPrompt = defaultSpaciousPrompt(spaciousPrompt);
            bindCtrlVToClipboardImage(lineReader);
            bindEscToClearInput(lineReader);

            while (true) {
                PromptInput promptInput;
                AgentTurnInbox.Item queuedPrompt = pollPendingAgentPrompt(turnInbox);
                try {
                    if (queuedPrompt != null) {
                        promptInput = PromptInput.submitted(queuedPrompt.text());
                    } else {
                        promptInput = readPromptInput(terminal, lineReader, renderer,
                                nextTaskOrchestrationProfile != null, spaciousPrompt, pendingDraft);
                        pendingDraft = "";
                    }
                } catch (UserInterruptException e) {
                    continue;  // Ctrl+C 跳过
                } catch (EndOfFileException e) {
                    break;  // Ctrl+D 退出
                }
                if (renderer instanceof InlineRenderer inline) {
                    inline.clearAcceptedInput(promptInput.text());
                }

                if (promptInput.canceled()) {
                    if (nextTaskOrchestrationProfile != null) {
                        ui.println("↩️ 已取消待执行的 "
                                + nextTaskOrchestrationProfile.displayName() + "，回到默认 ReAct。\n");
                        nextTaskOrchestrationProfile = null;
                    }
                    continue;
                }

                String input = promptInput.text().trim();

                if (input.isEmpty()) {
                    continue;
                }

                CliCommandParser.ParsedCommand command = CliCommandParser.parse(input);
                switch (command.type()) {
                    case UNKNOWN_COMMAND -> {
                        ui.println("❌ 未知命令: " + command.payload());
                        printSlashCommandHelp(ui);
                        continue;
                    }
                    case HELP -> {
                        printSlashCommandHelp(ui);
                        continue;
                    }
                    case EXIT -> {
                        ui.println("\n👋 再见!");
                        renderer.close();
                        return;
                    }
                    case CANCEL -> {
                        ui.println("当前没有正在运行的任务。\n");
                        continue;
                    }
                    case RUN_NOW -> {
                        if (command.payload() == null || command.payload().isBlank()) {
                            ui.println("当前没有正在运行的任务。\n");
                            continue;
                        }
                        input = command.payload();
                    }
                    case CLEAR -> {
                        String cleared = sessionTree.clearCurrent();
                        hitlHandler.clearApprovedAll();
                        ui.println(cleared);
                        ui.println("长期记忆和工作区保持不变\n");
                        continue;
                    }
                    case HISTORY_CLEAR -> {
                        clearLineReaderHistory(lineReader);
                        sessionArchive.clearAll();
                        ui.println("🧹 输入历史和已启用的会话归档已清空\n");
                        continue;
                    }
                    case CONTEXT_STATUS -> {
                        ui.println("📋 上下文状态：");
                        ui.println(reactAgent.getContextStatus());
                        ui.println();
                        continue;
                    }
                    case COMPACT -> {
                        try {
                            int keep = CliCommandParser.compactKeepRounds(command.payload());
                            ui.println("正在压缩历史上下文……");
                            com.devcli.memory.CompactionResult result = reactAgent.compactHistoryNow(keep);
                            if (result.compacted()) {
                                String report = "上下文已压缩：" + result.beforeTokens() + " → "
                                        + result.afterTokens() + " Token，保留最近 " + keep + " 轮原文";
                                ui.println(report);
                                sessionTree.recordTurn("react", input, input, report,
                                        reactAgent.getConversationHistory()).ifPresent(ui::println);
                            } else {
                                ui.println("压缩未应用，原始上下文已保留；可能没有可压缩的旧轮次、摘要校验失败或空间收益不足。");
                            }
                        } catch (IllegalArgumentException invalid) {
                            ui.println(invalid.getMessage());
                        }
                        ui.println();
                        continue;
                    }
                    case MEMORY_EXPORT -> {
                        com.devcli.memory.LongTermMemory longTermMemory =
                                reactAgent.getMemoryManager().getLongTermMemory();
                        java.util.List<com.devcli.memory.MemoryEntry> entries = longTermMemory.getAll();
                        String markdown = longTermMemory.exportMarkdown();
                        try {
                            java.nio.file.Path auditFile = longTermMemory.rootDir().resolve("memory-audit.md");
                            java.nio.file.Files.createDirectories(auditFile.getParent());
                            java.nio.file.Files.writeString(auditFile, markdown,
                                    java.nio.charset.StandardCharsets.UTF_8);
                            ui.println("已导出 " + entries.size() + " 条长期记忆到 " + auditFile);
                            ui.println("只读审计快照，不会回写记忆库；删除条目用 /memory forget <id>。\n");
                        } catch (java.io.IOException e) {
                            ui.println("导出记忆审计快照失败：" + e.getMessage() + "\n");
                        }
                        continue;
                    }
                    case MEMORY_STATUS -> {
                        ui.println("📋 记忆系统状态：");
                        ui.println(reactAgent.getMemoryManager().getSystemStatus());
                        ui.println(ruleContext.getStatusSummary());
                        ui.println("   /memory organize - 查看过期项与作用域冲突统计");
                        ui.println("   /memory export - 导出可读 Markdown 审计快照");
                        ui.println("   /memory organize apply - 重建全局与项目记忆索引");
                        ui.println("   /memory clear [global|project] - 清空长期记忆（默认只清当前写入作用域）");
                        ui.println("   /memory forget <id> - 删除单条长期记忆");
                        ui.println("   /save <事实> - 手动保存到长期记忆（Retrievable）");
                        ui.println("   /save --pin <事实> - 已废弃，请改用 /save 或 /rule add");
                        ui.println();
                        continue;
                    }
                    case MEMORY_ORGANIZE -> {
                        if ("apply".equalsIgnoreCase(command.payload())) {
                            int rebuilt = reactAgent.getMemoryManager().getLongTermMemory().rebuildIndexes();
                            ui.println("已重建 " + rebuilt + " 个作用域的长期记忆索引。");
                        } else {
                            ui.println(reactAgent.getMemoryManager().getLongTermMemory().maintenanceReport());
                        }
                        ui.println();
                        continue;
                    }
                    case MEMORY_PENDING -> {
                        ui.println("当前版本不再使用后台记忆候选队列；自动冲突会保留原记忆，显式 /save 才会更新。");
                        ui.println();
                        continue;
                    }
                    case MEMORY_CONFIRM, MEMORY_REJECT -> {
                        ui.println("当前版本已移除记忆候选队列，无需 confirm/reject。\n");
                        continue;
                    }
                    case MEMORY_CLEAR -> {
                        ui.println(reactAgent.getMemoryManager().clearLongTerm(command.payload()));
                        ui.println();
                        continue;
                    }
                    case MEMORY_FORGET -> {
                        String forgetId = command.payload();
                        if (forgetId == null || forgetId.isBlank()) {
                            ui.println("❌ 请提供要删除的记忆 id，例如 /memory forget fact-1a2b3c4d\n");
                        } else if (reactAgent.getMemoryManager().forgetLongTermMemory(forgetId)) {
                            ui.println("🧹 已删除长期记忆 " + forgetId + "\n");
                        } else {
                            ui.println("❌ 未找到长期记忆 " + forgetId + "（可用 /memory 查看现有条目）\n");
                        }
                        ui.println();
                        continue;
                    }
                    case MEMORY_SAVE -> {
                        String fact = command.payload();
                        if (fact == null || fact.isEmpty()) {
                            ui.println("❌ 请提供要保存的内容，例如 /save 这个项目使用Java 17\n");
                        } else {
                            com.devcli.memory.MemoryManager.StoreResult result =
                                    reactAgent.getMemoryManager().storeFactWithPolicy(fact, true);
                            if (result.stored()) {
                                ui.println("💾 " + result.message() + ": "
                                        + com.devcli.policy.SensitiveDataRedactor.redact(fact) + "\n");
                            } else {
                                ui.println("⚠️ " + result.message() + "\n");
                            }
                        }
                        continue;
                    }
                    case RULE_ADD -> {
                        String rule = command.payload();
                        if (rule == null || rule.isEmpty()) {
                            ui.println("❌ 请提供强约束，例如 /rule add 禁止修改生成目录\n");
                        } else {
                            try {
                                com.devcli.memory.RuleContext.Rule added = ruleContext.addRule(rule, "user-cli");
                                ui.println("已添加强约束: " + added.content);
                                ui.println("   (id=" + added.id + " · 规则上下文每轮生效)\n");
                            } catch (IllegalArgumentException e) {
                                ui.println("无法添加规则: " + e.getMessage() + "\n");
                            }
                        }
                        continue;
                    }
                    case RULE_LIST -> {
                        ui.println(ruleContext.renderManagementReport());
                        ui.println();
                        continue;
                    }
                    case RULE_REMOVE -> {
                        String ruleId = command.payload();
                        if (ruleId == null || ruleId.isBlank()) {
                            ui.println("请提供规则 id，例如 /rule remove rule-1a2b3c4d\n");
                        } else if (ruleContext.removeRule(ruleId)) {
                            ui.println("已删除规则 " + ruleId + "\n");
                        } else {
                            ui.println("未找到规则 " + ruleId + "，可用 /rule list 查看\n");
                        }
                        continue;
                    }
                    case MEMORY_PIN -> {
                        ui.println("/save --pin 已废弃：稳定事实请用 /save，强约束请用 /rule add\n");
                        continue;
                    }
                    case ORCHESTRATE -> {
                        OrchestrationProfile requestedProfile = command.orchestrationProfile() == null
                                ? OrchestrationProfile.TEAM
                                : command.orchestrationProfile();
                        if (command.payload() == null || command.payload().isEmpty()) {
                            nextTaskOrchestrationProfile = requestedProfile;
                            ui.println("下一条任务将使用 " + requestedProfile.displayName()
                                    + " 编排配置，输入任务前按 ESC 可取消，执行完成后自动回到默认 ReAct。\n");
                            continue;
                        }
                        input = command.payload();
                    }
                    case SWITCH_MODEL -> {
                        String selection = command.payload();
                        if (selection == null || selection.isEmpty()) {
                            ui.println("🤖 当前模型: " + llmClient.getModelName() + " (" + llmClient.getProviderName() + ")");
                            ui.println("   GLM 明确模型：");
                            ui.println("   /model glm-5.1       - 切换到 GLM-5.1");
                            ui.println("   /model glm-5v-turbo  - 切换到 GLM-5V-Turbo 多模态");
                            ui.println("   默认 provider：");
                            ui.println("   /model anthropic    - 切换到 Anthropic Messages（读取 ANTHROPIC_* 配置）");
                            ui.println("   /model openai       - 切换到 OpenAI 兼容端点（读取 OPENAI_* 配置）");
                            ui.println("   其它 provider 使用你配置里的具体模型：");
                            ui.println("   /model deepseek      - 切换到 DeepSeek（读取配置模型）");
                            ui.println("   /model step          - 切换到 StepFun（读取配置模型）");
                            ui.println("   /model kimi          - 切换到 Kimi（读取配置模型）\n");
                        } else {
                            ModelSelection target = resolveModelSelection(selection);
                            if (target.explicitModel()) {
                                ensureProviderConfig(config, target.provider()).setModel(target.model());
                            }
                            LlmClient newClient = LlmClientFactory.create(target.provider(), config);
                            if (newClient == null) {
                                ui.println("❌ 切换失败：未配置 " + target.provider() + " 的 API Key\n");
                            } else {
                                llmClient = newClient;
                                llmClientRef.set(newClient);
                                config.setDefaultProvider(target.provider());
                                config.save();
                                reactAgent.setLlmClient(llmClient);
                                ui.println("✅ 已切换到: " + llmClient.getModelName() + " (" + llmClient.getProviderName() + ")");
                                ui.println("   上下文策略: " + reactAgent.getMemoryManager().getContextProfile().summary());
                                ui.println("   对话上下文已保留，使用 /clear 可清空\n");
                                renderer.updateStatus(statusInfo(llmClient, hitlToolRegistry.currentPermissionMode(), "idle", mcpServerManager, skillRegistry));
                            }
                        }
                        continue;
                    }
                    case PERMISSION_MODE -> {
                        String payload = command.payload();
                        if (payload == null || payload.isBlank()) {
                            PermissionMode active = hitlToolRegistry.currentPermissionMode();
                            ui.println("🔐 当前权限模式：" + active.id() + " — " + active.description());
                            ui.println(PermissionMode.usage());
                            ui.println();
                        } else {
                            try {
                                PermissionMode next = PermissionMode.parse(payload);
                                hitlToolRegistry.withPermissionMode(next);
                                // 放行缓存按模式边界收敛：换模式后旧的「全部放行」不再代表当前授权
                                hitlHandler.clearApprovedAll();
                                ui.println("🔐 权限模式已切换：" + next.id() + " — " + next.description());
                                if (next.capability() == PermissionMode.Capability.READ_ONLY) {
                                    ui.println("   只读在任务入口收窄能力范围：写入、命令与外部副作用工具"
                                            + "不会暴露给模型，也不会执行。");
                                }
                                ui.println();
                            } catch (IllegalArgumentException invalid) {
                                ui.println("❌ " + invalid.getMessage());
                                ui.println(PermissionMode.usage());
                                ui.println();
                            }
                        }
                        renderer.updateStatus(statusInfo(llmClient, hitlToolRegistry.currentPermissionMode(), "idle", mcpServerManager, skillRegistry));
                        continue;
                    }
                    case GRANT -> {
                        String payload = command.payload();
                        if (payload == null) {
                            printGrantUsage(ui, nextTaskGrant);
                            continue;
                        }
                        String[] tokens = payload.trim().split("\\s+");
                        String mode = tokens[0].toLowerCase(java.util.Locale.ROOT);
                        java.util.List<String> arguments = java.util.List.of(tokens)
                                .subList(1, tokens.length);
                        if (!arguments.isEmpty() && !"write".equals(mode) && !"net".equals(mode)) {
                            ui.println("❌ /grant " + mode + " 不接受参数。\n");
                            continue;
                        }
                        if ("net".equals(mode) && arguments.isEmpty()) {
                            ui.println("❌ 请给出要授权的域名，例如 /grant net github.com\n");
                            continue;
                        }
                        try {
                            switch (mode) {
                                case "off" -> {
                                    nextTaskGrant = com.devcli.policy.TaskGrant.NONE;
                                    // 撤销必须覆盖全部放行来源：只清例外却留着本轮「全部放行」缓存，
                                    // 用户会以为授权已收回，而实际上审批仍在自动通过。
                                    hitlHandler.clearApprovedAll();
                                    ui.println("🔓 已取消本轮例外，并清空本轮「全部放行」缓存。");
                                    ui.println("   持久基线不受影响；如需修改请编辑 ~/.devcli/config.json 后重启。\n");
                                }
                                case "status" -> printGrantStatus(ui, nextTaskGrant, hitlHandler);
                                case "write" -> {
                                    java.util.List<String> effective = arguments.isEmpty()
                                            ? java.util.List.of(com.devcli.policy.WriteGlobSet.WHOLE_PROJECT)
                                            : java.util.List.copyOf(arguments);
                                    nextTaskGrant = nextTaskGrant.withWriteGlobs(effective);
                                    printGrant(nextTaskGrant, ui);
                                }
                                case "net" -> {
                                    nextTaskGrant = nextTaskGrant.withNetworkHosts(
                                            java.util.List.copyOf(arguments));
                                    printGrant(nextTaskGrant, ui);
                                }
                                default -> {
                                    nextTaskGrant = new com.devcli.policy.TaskGrant(
                                            java.util.List.of(com.devcli.policy.WriteGlobSet.WHOLE_PROJECT),
                                            nextTaskGrant.networkHosts(), false);
                                    printGrant(nextTaskGrant, ui);
                                }
                            }
                        } catch (IllegalArgumentException invalidArgument) {
                            ui.println("❌ " + invalidArgument.getMessage() + "；授权未被修改。\n");
                        }
                        continue;
                    }
                    case POLICY_STATUS -> {
                        printPolicyStatus(ui, reactAgent);
                        continue;
                    }
                    case CONFIG -> {
                        handleConfigPalette(renderer, config, llmClient,
                                hitlToolRegistry.currentPermissionMode(), skillRegistry);
                        continue;
                    }
                    case AUDIT_TAIL -> {
                        printAuditTail(ui, reactAgent, command.payload());
                        continue;
                    }
                    case SNAPSHOT -> {
                        printSnapshotCommand(ui, reactAgent.getToolRegistry().getSnapshotService(), command.payload());
                        continue;
                    }
                    case RESTORE_SNAPSHOT -> {
                        printRestoreCommand(ui, reactAgent.getToolRegistry().getSnapshotService(), command.payload());
                        continue;
                    }
                    case TRACE -> {
                        com.devcli.trace.TraceQuery traceQuery = new com.devcli.trace.TraceQuery();
                        if ("list".equalsIgnoreCase(command.payload())) {
                            ui.println(traceQuery.renderList(10));
                        } else {
                            ui.println(traceQuery.renderRun(command.payload()));
                        }
                        continue;
                    }
                    case MCP_LIST -> {
                        ui.println(mcpServerManager.formatStatus());
                        ui.println();
                        continue;
                    }
                    case MCP_RESTART -> {
                        printMcpCommandResult(ui, mcpServerManager.restart(command.payload()));
                        renderer.updateStatus(statusInfo(llmClient, hitlToolRegistry.currentPermissionMode(), "idle", mcpServerManager, skillRegistry));
                        continue;
                    }
                    case MCP_LOGS -> {
                        printMcpCommandResult(ui, mcpServerManager.logs(command.payload()));
                        continue;
                    }
                    case MCP_DISABLE -> {
                        printMcpCommandResult(ui, mcpServerManager.disable(command.payload()));
                        renderer.updateStatus(statusInfo(llmClient, hitlToolRegistry.currentPermissionMode(), "idle", mcpServerManager, skillRegistry));
                        continue;
                    }
                    case MCP_ENABLE -> {
                        printMcpCommandResult(ui, mcpServerManager.enable(command.payload()));
                        renderer.updateStatus(statusInfo(llmClient, hitlToolRegistry.currentPermissionMode(), "idle", mcpServerManager, skillRegistry));
                        continue;
                    }
                    case MCP_RESOURCES -> {
                        printMcpCommandResult(ui, mcpServerManager.resources(command.payload()));
                        continue;
                    }
                    case MCP_PROMPTS -> {
                        printMcpCommandResult(ui, mcpServerManager.prompts(command.payload()));
                        continue;
                    }
                    case BROWSER -> {
                        printMcpCommandResult(ui, browserCommands.handle(command.payload()));
                        continue;
                    }
                    case TASK -> {
                        printMcpCommandResult(ui, TaskCommandFormatter.handle(taskManager, command.payload()));
                        continue;
                    }
                    case SKILL_LIST -> {
                        ui.println(SkillCommandHandler.list(skillRegistry));
                        continue;
                    }
                    case SKILL_SHOW -> {
                        ui.println(SkillCommandHandler.show(skillRegistry, command.payload()));
                        continue;
                    }
                    case SKILL_ON -> {
                        ui.println(SkillCommandHandler.enable(skillRegistry, skillStateStore, command.payload()));
                        renderer.updateStatus(statusInfo(llmClient, hitlToolRegistry.currentPermissionMode(), "idle", mcpServerManager, skillRegistry));
                        continue;
                    }
                    case SKILL_OFF -> {
                        ui.println(SkillCommandHandler.disable(skillRegistry, skillStateStore, command.payload()));
                        renderer.updateStatus(statusInfo(llmClient, hitlToolRegistry.currentPermissionMode(), "idle", mcpServerManager, skillRegistry));
                        continue;
                    }
                    case SKILL_TRUST_PROJECT -> {
                        ui.println(SkillCommandHandler.trustProject(skillRegistry));
                        renderer.updateStatus(statusInfo(llmClient, hitlToolRegistry.currentPermissionMode(), "idle", mcpServerManager, skillRegistry));
                        continue;
                    }
                    case SKILL_UNTRUST_PROJECT -> {
                        ui.println(SkillCommandHandler.untrustProject(skillRegistry));
                        renderer.updateStatus(statusInfo(llmClient, hitlToolRegistry.currentPermissionMode(), "idle", mcpServerManager, skillRegistry));
                        continue;
                    }
                    case SKILL_RELOAD -> {
                        skillRegistry.reload();
                        try {
                            syncHookExtensions(extensionRegistry);
                        } catch (Exception e) {
                            ui.println("⚠️ Hook 目录同步失败: " + e.getMessage());
                        }
                        ui.println("🔄 已重新扫描 skill 目录");
                        ui.println(SkillCommandHandler.startupSummary(skillRegistry));
                        ui.println("✅ 下一轮 LLM 调用生效");
                        renderer.updateStatus(statusInfo(llmClient, hitlToolRegistry.currentPermissionMode(), "idle", mcpServerManager, skillRegistry));
                        continue;
                    }
                    case SESSION, BRANCH -> {
                        try {
                            if (command.type() == CliCommandParser.CommandType.BRANCH
                                    && sessionTree.consumeLegacyAliasNotice()) {
                                ui.println("/branch 已兼容映射到 /session，后续请使用 /session");
                            }
                            String payload = command.payload() == null ? "status" : command.payload().trim();
                            if (payload.equalsIgnoreCase("status")) {
                                ui.println(sessionTree.status());
                            } else if (payload.equalsIgnoreCase("list") || payload.equalsIgnoreCase("tree")) {
                                ui.println(sessionTree.renderTree());
                            } else if (payload.equalsIgnoreCase("new")) {
                                ui.println(sessionTree.newSession());
                            } else if (payload.equalsIgnoreCase("clear")) {
                                ui.println(sessionTree.clearCurrent());
                            } else if (payload.regionMatches(true, 0, "create ", 0, 7)) {
                                ui.println(sessionTree.fork(payload.substring(7).trim()));
                            } else if (payload.regionMatches(true, 0, "fork ", 0, 5)) {
                                ui.println(handleSessionFork(sessionTree, payload.substring(5).trim()));
                            } else if (payload.regionMatches(true, 0, "use ", 0, 4)) {
                                ui.println(sessionTree.use(payload.substring(4).trim()));
                            } else if (payload.regionMatches(true, 0, "use-thread ", 0, 11)) {
                                ui.println(sessionTree.useThread(payload.substring(11).trim()));
                            } else {
                                ui.println("用法: /session status|tree|fork <name> [eventId]|use <branch>|new|clear|use-thread <threadId>");
                            }
                        } catch (IllegalArgumentException e) {
                            ui.println("❌ " + e.getMessage());
                        }
                        continue;
                    }
                    case INDEX_CODE -> {
                        String indexPath = command.payload() != null ? command.payload() : ".";
                        CodeIndex indexer = new CodeIndex(ui::println);
                        indexer.index(indexPath);
                        ui.println();

                        // 切换工具项目围栏时同步取得对应项目的信任决定。
                        String absPath = new File(indexPath).getAbsolutePath();
                        reactAgent.getToolRegistry().setProjectPath(absPath);
                        CliProjectTrust.bind(hitlToolRegistry, projectPermissions, Path.of(absPath), hitlHandler);
                        continue;
                    }
                    case SEARCH_CODE -> {
                        String query = command.payload();
                        if (query == null || query.isEmpty()) {
                            ui.println("❌ 请提供检索关键词，例如 /search 用户登录实现\n");
                            continue;
                        }
                        ui.println("🔍 检索: " + query);
                        try (CodeRetriever retriever = new CodeRetriever(".")) {
                            var stats = retriever.getStats();
                            if (stats.chunkCount() == 0) {
                                ui.println("⚠️ 代码库尚未索引，请先使用 /index 命令\n");
                                continue;
                            }
                            List<com.devcli.rag.VectorStore.SearchResult> results = retriever.hybridSearch(query, 5);
                            if (results.isEmpty()) {
                                ui.println("📭 未找到相关代码\n");
                            } else {
                                ui.println(SearchResultFormatter.formatForCli(query, results) + "\n");
                            }
                        } catch (Exception e) {
                            ui.println("❌ 检索失败: " + e.getMessage() + "\n");
                        }
                        continue;
                    }
                    case GRAPH_QUERY -> {
                        String className = command.payload();
                        if (className == null || className.isEmpty()) {
                            ui.println("❌ 请提供类名，例如 /graph Main\n");
                            continue;
                        }
                        ui.println("🕸️ 查询类关系图谱: " + className);
                        try (CodeRetriever retriever = new CodeRetriever(".")) {
                            var stats = retriever.getStats();
                            if (stats.chunkCount() == 0) {
                                ui.println("⚠️ 代码库尚未索引，请先使用 /index 命令\n");
                                continue;
                            }
                            List<CodeRelation> relations = retriever.getRelationGraph(className);
                            if (relations.isEmpty()) {
                                ui.println("📭 未找到相关关系\n");
                            } else {
                                ui.println("📋 找到 " + relations.size() + " 条关系:\n");
                                for (CodeRelation rel : relations) {
                                    String arrow = rel.relationType().equals("contains") ? "├── contains -->"
                                            : rel.relationType().equals("extends") ? "└── extends -->"
                                            : rel.relationType().equals("implements") ? "└── implements -->"
                                            : rel.relationType().equals("calls") ? "├── calls -->"
                                            : "├── " + rel.relationType() + " -->";
                                    ui.printf("   %s %s [%s]%n", rel.fromName(), arrow,
                                            rel.toName() != null ? rel.toName() : "unknown");
                                }
                                ui.println();
                            }
                        } catch (Exception e) {
                            ui.println("❌ 查询失败: " + e.getMessage() + "\n");
                        }
                        continue;
                    }
                    case NONE -> {
                    }
                }

                // 运行 Agent
                String submittedInput = input;
                int toolDefinitionTokens = com.devcli.memory.TokenBudget.estimateToolDefinitionsTokens(
                        reactAgent.getToolRegistry().getToolDefinitions());
                int historyTriggerTokens = reactAgent.getMemoryManager().getContextProfile()
                        .historyTriggerTokens(toolDefinitionTokens);
                int historyTokens = com.devcli.memory.TokenBudget.estimateMessagesTokens(
                        reactAgent.getConversationHistory());
                int submittedTokens = com.devcli.memory.TokenBudget.estimateMessagesTokens(
                        List.of(LlmClient.Message.user(input)));
                int remainingAttachmentTokens = Math.max(
                        0, historyTriggerTokens - historyTokens - submittedTokens);
                input = mentionExpander.expand(input, remainingAttachmentTokens);
                submittedTokens = com.devcli.memory.TokenBudget.estimateMessagesTokens(
                        List.of(LlmClient.Message.user(input)));
                remainingAttachmentTokens = Math.max(
                        0, historyTriggerTokens - historyTokens - submittedTokens);
                input = localPathMentionExpander.expand(input, remainingAttachmentTokens);
                if (!(renderer instanceof InlineRenderer)) {
                    ui.println();
                }
                renderer.beginTurn();
                if (renderer instanceof InlineRenderer inline) {
                    inline.printSubmittedPrompt(submittedInput);
                } else {
                    printSubmittedPrompt(ui, submittedInput);
                }
                final String taskInput = input;
                Callable<String> runTask;
                String snapshotMode;
                AtomicReference<AgentSessionRuntime.RunResult> reactResult = new AtomicReference<>();
                OrchestrationProfile orchestrationProfile = command.type() == CliCommandParser.CommandType.ORCHESTRATE
                        ? command.orchestrationProfile()
                        : nextTaskOrchestrationProfile;
                // 能力范围与任务级授权都作用于整轮（含编排）：波次线程池与隔离注册表在轮次内创建，
                // 会继承当前轮次的范围与授权，因此不需要再按路径区分。
                // 只读由权限模式声明（plan），不再是独立开关：模式是粘性的，直到 /mode 切走。
                boolean readOnlyTurn = hitlToolRegistry.currentPermissionMode().capability()
                        == PermissionMode.Capability.READ_ONLY;
                // 有效授权 = 持久基线 ∪ 本轮例外：基线跨会话生效，例外只作用于这一轮。
                // 合并是并集，例外只能加不能减，避免用户从「基线是常态」推不出结果。
                // 持久授权由规则层承载（permissions.allow）；/grant 只表达「本轮例外」，
                // 一轮结束即还原，不再与持久基线求并集。
                com.devcli.policy.TaskGrant grantForTurn = nextTaskGrant;
                nextTaskGrant = com.devcli.policy.TaskGrant.NONE;
                ToolRegistry reactToolRegistry = reactAgent.getToolRegistry();
                if (readOnlyTurn && orchestrationProfile != null) {
                    // 只读编排必然失败：Worker 必须写入隔离工作区。与其跑到中途收获一片
                    // CAPABILITY_DENIED，不如直接拒绝并说明；模式是粘性的，用户可自行切回。
                    ui.println("❌ 只读模式（plan）无法执行 " + orchestrationProfile.displayName()
                            + " 编排：编排必须写入隔离工作区。请先 /mode default 再执行，"
                            + "或直接对普通任务使用 plan 模式。\n");
                    continue;
                }
                if (orchestrationProfile != null) {
                    snapshotMode = orchestrationProfile.snapshotMode();
                    LlmClient activeClient = llmClient;
                    OrchestrationTaskRunner orchestrationTaskRunner = new OrchestrationTaskRunner(
                            config,
                            reactAgent,
                            mcpServerManager,
                            ruleContext,
                            skillRegistry,
                            skillContextBuffer,
                            createTeamPlanReviewHandler(lineReader, ui, terminalInput),
                            TRACE_SINK,
                            ui);
                    runTask = () -> {
                        reactAgent.recordUserIntent(submittedInput);
                        return reactToolRegistry.runWithTaskGrant(grantForTurn,
                                () -> orchestrationTaskRunner.run(activeClient, taskInput));
                    };
                } else {
                    snapshotMode = "react";
                    java.util.function.Supplier<String> reactTurn =
                            () -> {
                                AgentSessionRuntime.RunResult result = reactSession.runInCurrentContext(submittedInput, taskInput);
                                reactResult.set(result);
                                return result.output();
                            };
                    java.util.function.Supplier<String> grantedTurn =
                            () -> reactToolRegistry.runWithTaskGrant(grantForTurn, reactTurn);
                    runTask = readOnlyTurn
                            ? () -> reactToolRegistry.runWithToolAccess(ToolContract.ToolAccessScope.READ_ONLY,
                                    grantedTurn)
                            : grantedTurn::get;
                }
                // 审批放行缓存按任务边界收敛：一次「全部放行」只在本轮任务内有效，
                // 不跨用户消息静默继承；/plan 或 Team 的一次运行属于同一任务，内部仍共享放行。
                hitlHandler.clearApprovedAll();
                SnapshotService snapshotService = reactAgent.getToolRegistry().getSnapshotService();
                renderer.updateStatus(statusInfo(llmClient, hitlToolRegistry.currentPermissionMode(), snapshotMode, mcpServerManager, skillRegistry));
                // 审批和计划确认统一切回输入线程，运行期间可以继续编辑和提交消息。
                boolean acceptActiveTurnInput = TerminalCapabilities.supportsAnsi(terminal);
                TurnRunResult turnResult = runWithCancelSupport(terminal,
                        lineReader,
                        renderer,
                        ui,
                        Path.of(reactAgent.getToolRegistry().getProjectPath()),
                        turnInbox,
                        terminalInput,
                        acceptActiveTurnInput,
                        pendingDraft,
                        () -> snapshotService.runTurn(snapshotMode, taskInput, runTask::call));
                String response = turnResult.response();
                pendingDraft = turnResult.draft();
                if (!"react".equals(snapshotMode)) {
                    renderer.updateStatus(statusInfo(llmClient, hitlToolRegistry.currentPermissionMode(), "idle", mcpServerManager, skillRegistry));
                }
                nextTaskOrchestrationProfile = null;
                String displayResponse = reactResult.get() == null ? response : reactResult.get().displayOutput();
                if (displayResponse != null && !displayResponse.isBlank()) {
                    ui.println(displayResponse);
                    ui.println();
                }
                List<LlmClient.Message> persistedMessages = "react".equals(snapshotMode)
                        ? reactAgent.getConversationHistory()
                        : List.of();
                sessionTree.recordTurn(
                                snapshotMode, submittedInput, taskInput, response, persistedMessages)
                        .ifPresent(ui::println);
                sessionArchive.recordTurn(
                        sessionTree.threadId(), sessionTree.activeBranchId(),
                        snapshotMode, submittedInput, taskInput, response, persistedMessages);
                if (!turnResult.executionQuiesced()) {
                    ui.println("取消后执行线程未在限定时间内退出，为避免上下文并发写入，DevCLI 已停止接收新任务。");
                    break;
                }
            }
            ui.println("\n👋 再见!");
            reactSession.close();
            renderer.close();

        } catch (IOException e) {
            System.err.println("❌ 终端初始化失败: " + e.getMessage());
            throw new RuntimeException("终端初始化失败", e);
        }
    }

    static String handleSessionFork(SessionTreeService sessionTree, String arguments) {
        if (arguments == null || arguments.isBlank()) {
            throw new IllegalArgumentException("分支名称不能为空");
        }
        String[] parts = arguments.trim().split("\\s+");
        if (parts.length == 1) {
            return sessionTree.fork(parts[0]);
        }
        if (parts.length == 2) {
            try {
                long eventId = Long.parseLong(parts[1]);
                if (eventId < 0) {
                    throw new NumberFormatException("negative");
                }
                return sessionTree.fork(parts[0], eventId);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("eventId 必须为非负整数");
            }
        }
        throw new IllegalArgumentException("用法: /session fork <name> [eventId]");
    }

    static void syncHookExtensions(ExtensionRegistry extensionRegistry) throws IOException {
        extensionRegistry.replaceKind(
                com.devcli.extension.ExtensionContract.Kind.HOOK,
                HookConfigLoader.load(Path.of(".")).stream()
                        .map(ExtensionRegistry::fromHook)
                        .toList());
    }
}
