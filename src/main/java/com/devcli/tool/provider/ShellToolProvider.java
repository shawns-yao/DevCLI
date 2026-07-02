package com.devcli.tool.provider;

import com.devcli.policy.CommandGuard;
import com.devcli.policy.PolicyException;
import com.devcli.tool.ToolRegistry;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ShellToolProvider implements ToolProvider {
    private static final int MAX_COMMAND_OUTPUT_CHARS = 8_000;

    @Override
    public void register(ToolContext context) {
        context.registerTool(new ToolRegistry.Tool(
                "execute_command",
                "在当前项目目录中执行短时 Shell 命令（默认 60 秒超时，不允许全盘扫描）",
                context.createToolParameters(new ToolParameter("command", "string", "要执行的命令", true)),
                args -> executeCommand(args, context)
        ));
    }

    private String executeCommand(Map<String, String> args, ToolContext context) {
        String normalized = args.get("command") == null ? "" : args.get("command").trim();
        if (normalized.isEmpty()) {
            return "执行命令失败: 命令不能为空";
        }
        String denyReason = CommandGuard.check(normalized);
        if (denyReason != null) {
            throw new PolicyException(denyReason);
        }

        ExecutorService outputReaderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "devcli-command-output");
            thread.setDaemon(true);
            return thread;
        });

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(shellCommand(normalized));
            pb.directory(new File(context.projectPath()));
            pb.redirectErrorStream(true);
            process = pb.start();

            Process runningProcess = process;
            Future<String> outputFuture = outputReaderExecutor.submit(() -> readProcessOutput(runningProcess));

            boolean finished = process.waitFor(context.commandTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                terminateProcessTree(process);
                outputFuture.cancel(true);
                return "命令执行超时（" + context.commandTimeoutSeconds() + "秒），已强制终止";
            }

            String output = getCommandOutput(outputFuture);
            int exitCode = process.exitValue();
            return String.format("命令执行完成 (exit code: %d)\n%s", exitCode, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                terminateProcessTree(process);
            }
            return "用户取消了此次工具调用";
        } catch (Exception e) {
            if (process != null) {
                terminateProcessTree(process);
            }
            return "执行命令失败: " + e.getMessage();
        } finally {
            outputReaderExecutor.shutdownNow();
        }
    }

    private void terminateProcessTree(Process process) {
        if (process == null) {
            return;
        }
        List<ProcessHandle> descendants = process.toHandle().descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--) {
            descendants.get(i).destroyForcibly();
        }
        process.destroyForcibly();
        for (ProcessHandle descendant : descendants) {
            waitForProcessExit(descendant);
        }
        waitForProcessExit(process.toHandle());
        closeProcessStreams(process);
    }

    private void waitForProcessExit(ProcessHandle handle) {
        try {
            handle.onExit().get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Best-effort cleanup: timeout paths must return even if the OS delays process reaping.
        }
    }

    private void closeProcessStreams(Process process) {
        try {
            process.getInputStream().close();
        } catch (Exception ignored) {
        }
        try {
            process.getOutputStream().close();
        } catch (Exception ignored) {
        }
        try {
            process.getErrorStream().close();
        } catch (Exception ignored) {
        }
    }

    private List<String> shellCommand(String command) {
        if (isWindows()) {
            String utf8Command = "[Console]::InputEncoding = [Text.UTF8Encoding]::new($false); "
                    + "[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false); "
                    + command;
            return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-Command", utf8Command);
        }
        return List.of("bash", "-c", command);
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    private String readProcessOutput(Process process) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < MAX_COMMAND_OUTPUT_CHARS) {
                    int remaining = MAX_COMMAND_OUTPUT_CHARS - output.length();
                    if (line.length() > remaining) {
                        output.append(line, 0, remaining);
                    } else {
                        output.append(line);
                    }
                    output.append('\n');
                }
            }
        }
        if (output.length() >= MAX_COMMAND_OUTPUT_CHARS) {
            return output.substring(0, MAX_COMMAND_OUTPUT_CHARS) + "\n...(输出已截断)";
        }
        return output.toString();
    }

    private String getCommandOutput(Future<String> outputFuture) throws Exception {
        try {
            return outputFuture.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            outputFuture.cancel(true);
            return "(命令已结束，但输出读取超时)";
        }
    }
}
