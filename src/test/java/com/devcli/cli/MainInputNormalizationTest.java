package com.devcli.cli;

import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainInputNormalizationTest {

    @Test
    void keepsMultilinePasteStructure() {
        String normalized = TerminalInputSupport.prepareSeedBuffer("请把任务拆成可并行的 DAG:\n1. 读 pom.xml\r\n2. 列出 src/main/java");

        assertEquals("请把任务拆成可并行的 DAG:\n1. 读 pom.xml\n2. 列出 src/main/java", normalized);
    }

    @Test
    void keepsSingleLineInputUntouched() {
        String normalized = TerminalInputSupport.prepareSeedBuffer("帮我读取 pom.xml");

        assertEquals("帮我读取 pom.xml", normalized);
    }

    @Test
    void normalizesLegacyCarriageReturnsWithoutChangingTextLayout() {
        String normalized = TerminalInputSupport.prepareSeedBuffer("第一行\r第二行\r\n第三行");

        assertEquals("第一行\n第二行\n第三行", normalized);
    }

    @Test
    void startupHintsKeepSlashCommandDetailsOutOfInitialScreen() {
        List<String> hints = CliDisplay.startupHints();

        assertTrue(hints.stream().anyMatch(hint -> hint.contains("输入 '/' 后按 Tab 补全命令")));
        assertTrue(hints.stream().noneMatch(hint -> hint.contains("/model")));
        assertTrue(hints.stream().noneMatch(hint -> hint.contains("/index [路径]")));
        assertTrue(hints.stream().noneMatch(hint -> hint.contains("/skill list")));
    }

    @Test
    void startupBannerUsesOpenLayoutWithoutRightBorder() {
        List<String> lines = CliDisplay.startupBannerLines();

        assertTrue(lines.stream().anyMatch(line -> line.contains("DevCLI")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("PaiCLI")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("DecCLL")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("π")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("v" + BuildVersion.VALUE)));
        assertTrue(lines.size() <= 6, "startup must stay compact");
        assertTrue(lines.stream().noneMatch(line -> line.contains("██████╗")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("Tips for getting started")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("/help")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("for shortcuts")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("────────────────")));
        assertTrue(lines.stream().noneMatch(line -> line.endsWith("║")),
                "banner should not depend on a padded right border");
    }

    @Test
    void slashCommandTailTipsExposeCommandDescriptions() {
        var tips = TerminalInputSupport.slashCommandTailTips();

        assertTrue(tips.containsKey("/model"));
        assertTrue(tips.get("/model").getMainDesc().get(0).toString().contains("查看当前模型"));
        assertTrue(tips.containsKey("/plan <任务内容>"));
    }

    @Test
    void promptDoesNotUseBottomSpaciousModeByDefault() {
        assertFalse(TerminalInputSupport.defaultSpaciousPrompt(false));
        assertFalse(TerminalInputSupport.defaultSpaciousPrompt(true));
    }

    @Test
    void mcpStartupWaitCanBeTunedForTerminalSmoke() {
        String old = System.getProperty("devcli.mcp.startup.wait.seconds");
        try {
            System.setProperty("devcli.mcp.startup.wait.seconds", "2");

            assertEquals(Duration.ofSeconds(2), CliSettings.mcpStartupWait());
        } finally {
            restoreProperty("devcli.mcp.startup.wait.seconds", old);
        }
    }

    @Test
    void invalidMcpStartupWaitFallsBackToThirtySeconds() {
        String old = System.getProperty("devcli.mcp.startup.wait.seconds");
        try {
            for (String value : List.of("0", "-1", "invalid")) {
                System.setProperty("devcli.mcp.startup.wait.seconds", value);
                assertEquals(Duration.ofSeconds(30), CliSettings.mcpStartupWait());
            }
        } finally {
            restoreProperty("devcli.mcp.startup.wait.seconds", old);
        }
    }

    @Test
    void submittedPromptIsRenderedBackIntoTranscript() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        TerminalInputSupport.printSubmittedPrompt(new PrintStream(sink, true, StandardCharsets.UTF_8), "  是谁？  ");

        String emitted = sink.toString(StandardCharsets.UTF_8);
        assertTrue(emitted.contains(">"), emitted);
        assertTrue(emitted.contains("是谁？"), emitted);
        assertTrue(emitted.endsWith("\n"), emitted);
        assertFalse(emitted.endsWith("\n\n"), emitted);
    }

    @Test
    void submittedSingleLinePromptDoesNotAddExtraBlankLine() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        TerminalInputSupport.printSubmittedPrompt(new PrintStream(sink, true, StandardCharsets.UTF_8), "你好啊");

        String emitted = sink.toString(StandardCharsets.UTF_8);
        assertEquals(1, emitted.chars().filter(ch -> ch == '\n').count(), emitted);
        assertTrue(emitted.contains(">"), emitted);
        assertTrue(emitted.contains("你好啊"), emitted);
    }

    @Test
    void configuresAwtHeadlessOnMac() {
        String oldOs = System.getProperty("os.name");
        String oldHeadless = System.getProperty("java.awt.headless");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.clearProperty("java.awt.headless");

            TerminalInputSupport.configureAwtForCli();

            assertEquals("true", System.getProperty("java.awt.headless"));
        } finally {
            restoreProperty("os.name", oldOs);
            restoreProperty("java.awt.headless", oldHeadless);
        }
    }

    @Test
    void doesNotForceAwtHeadlessOnNonMac() {
        String oldOs = System.getProperty("os.name");
        String oldHeadless = System.getProperty("java.awt.headless");
        try {
            System.setProperty("os.name", "Linux");
            System.clearProperty("java.awt.headless");

            TerminalInputSupport.configureAwtForCli();

            assertFalse(System.getProperties().containsKey("java.awt.headless"));
        } finally {
            restoreProperty("os.name", oldOs);
            restoreProperty("java.awt.headless", oldHeadless);
        }
    }

    @Test
    void clearsCurrentInputBufferForEscWidget() throws Exception {
        LineReader lineReader = newLineReader();
        lineReader.getBuffer().write("@image:</tmp/shot.png> 这张图呢");

        TerminalInputSupport.clearInputBuffer(lineReader);

        assertEquals("", lineReader.getBuffer().toString());
    }

    @Test
    void slashCommandHintsIncludeRagSlashCommands() {
        List<String> commands = CliDisplay.slashCommandHints().stream()
                .map(CliDisplay.SlashCommandHint::display)
                .toList();

        assertTrue(commands.contains("/help"));
        assertTrue(commands.contains("/now <任务内容>"));
        assertTrue(commands.contains("/cancel"));
        assertTrue(commands.contains("/index [路径]"));
        assertTrue(commands.contains("/search <查询>"));
        assertTrue(commands.contains("/graph <类名>"));
    }

    @Test
    void slashCommandChoicesAreRenderedDirectlyWithoutJLineConfirmationText() {
        String choices = CliDisplay.formatSlashCommandChoices(120);

        assertTrue(choices.contains("/model anthropic"), choices);
        assertTrue(choices.contains("/model openai"), choices);
        assertTrue(choices.contains("/model glm-5.1"), choices);
        assertTrue(choices.contains("/model glm-5v-turbo"), choices);
        assertTrue(choices.contains("/model step"), choices);
        assertTrue(choices.contains("/model kimi"), choices);
        assertTrue(choices.contains("/browser status"), choices);
        assertFalse(choices.contains("do you wish"), choices);
        assertTrue(choices.lines().count() < CliDisplay.slashCommandHints().size(),
                "choices should be compact multi-column output");
    }

    @Test
    void classifiesStandaloneEscapeAsCancelIntent() {
        assertEquals(TerminalInputSupport.EscapeSequenceType.STANDALONE_ESC, TerminalInputSupport.classifyEscapeSequence(""));
    }

    @Test
    void classifiesArrowKeysAsControlSequences() {
        assertEquals(TerminalInputSupport.EscapeSequenceType.CONTROL_SEQUENCE, TerminalInputSupport.classifyEscapeSequence("[A"));
        assertEquals(TerminalInputSupport.EscapeSequenceType.CONTROL_SEQUENCE, TerminalInputSupport.classifyEscapeSequence("[B"));
        assertEquals(TerminalInputSupport.EscapeSequenceType.CONTROL_SEQUENCE, TerminalInputSupport.classifyEscapeSequence("OA"));
    }

    @Test
    void classifiesBracketedPasteSequenceSeparately() {
        assertEquals(TerminalInputSupport.EscapeSequenceType.BRACKETED_PASTE, TerminalInputSupport.classifyEscapeSequence("[200~hello"));
    }

    @Test
    void upArrowPrefillsLatestHistoryEntry() throws Exception {
        LineReader lineReader = newLineReader();
        History history = lineReader.getHistory();
        history.add("第一条");
        history.add("最近一条");

        assertEquals("最近一条", TerminalInputSupport.seedBufferForHistoryNavigation(lineReader, "[A"));
    }

    @Test
    void downArrowKeepsPromptEmpty() throws Exception {
        LineReader lineReader = newLineReader();
        lineReader.getHistory().add("最近一条");

        assertEquals("", TerminalInputSupport.seedBufferForHistoryNavigation(lineReader, "[B"));
    }

    @Test
    void decideEscCancelTriggersOnStandaloneEsc() {
        // 单 ESC（escTail 为空）→ 取消
        assertTrue(CliTurnRunner.decideEscCancel(27, ""));
        assertTrue(CliTurnRunner.decideEscCancel(27, null));
    }

    @Test
    void decideEscCancelIgnoresArrowKeyEscapeSequence() {
        // 上方向键 ESC[A → CONTROL_SEQUENCE，不取消
        assertFalse(CliTurnRunner.decideEscCancel(27, "[A"));
        // 下方向键
        assertFalse(CliTurnRunner.decideEscCancel(27, "[B"));
        // 应用模式方向键
        assertFalse(CliTurnRunner.decideEscCancel(27, "OA"));
    }

    @Test
    void decideEscCancelIgnoresBracketedPaste() {
        assertFalse(CliTurnRunner.decideEscCancel(27, "[200~hello"));
    }

    @Test
    void decideEscCancelIgnoresNonEscFirstByte() {
        // 普通字符不应触发
        assertFalse(CliTurnRunner.decideEscCancel((int) 'a', null));
        assertFalse(CliTurnRunner.decideEscCancel((int) '/', "cancel"));
        assertFalse(CliTurnRunner.decideEscCancel(0, null));
        assertFalse(CliTurnRunner.decideEscCancel(-1, null));
    }

    @Test
    void readEscCancelHandlesNullTerminalSafely() {
        assertFalse(CliTurnRunner.readEscCancel(null));
    }

    @Test
    void buildTerminalUsesDetectedConsoleEncoding() throws Exception {
        String previous = System.getProperty("devcli.terminal.encoding");
        System.setProperty("devcli.terminal.encoding", "UTF-8");
        try (Terminal terminal = TerminalInputSupport.buildTerminal()) {
            assertEquals(StandardCharsets.UTF_8, terminal.encoding());
            assertEquals(StandardCharsets.UTF_8, terminal.inputEncoding());
            assertEquals(StandardCharsets.UTF_8, terminal.outputEncoding());
        } finally {
            restoreProperty("devcli.terminal.encoding", previous);
        }
    }

    @Test
    void forceAnsiUsesXtermTypeForMisdetectedTerminal() throws Exception {
        String previous = System.getProperty("devcli.terminal.force.ansi");
        System.setProperty("devcli.terminal.force.ansi", "true");
        try (Terminal terminal = TerminalInputSupport.buildTerminal()) {
            assertEquals("xterm-256color", terminal.getType());
        } finally {
            restoreProperty("devcli.terminal.force.ansi", previous);
        }
    }

    @Test
    void redirectedInputDefaultsToUtf8() {
        String previous = System.getProperty("devcli.terminal.encoding");
        try {
            System.clearProperty("devcli.terminal.encoding");

            assertEquals(StandardCharsets.UTF_8, TerminalInputSupport.consoleCharset());
        } finally {
            restoreProperty("devcli.terminal.encoding", previous);
        }
    }

    @Test
    void consoleCharsetCanBeOverriddenForLegacyWindowsConsole() {
        String previous = System.getProperty("devcli.terminal.encoding");
        try {
            System.setProperty("devcli.terminal.encoding", "GBK");

            assertEquals(java.nio.charset.Charset.forName("GBK"), TerminalInputSupport.consoleCharset());
        } finally {
            restoreProperty("devcli.terminal.encoding", previous);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static LineReader newLineReader() throws Exception {
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .build();

        DefaultHistory history = new DefaultHistory();
        return LineReaderBuilder.builder()
                .terminal(terminal)
                .history(history)
                .build();
    }
}
