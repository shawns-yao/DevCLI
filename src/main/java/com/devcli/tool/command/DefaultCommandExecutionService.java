package com.devcli.tool.command;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class DefaultCommandExecutionService implements CommandExecutionService {
    public static final String SANDBOX_IMAGE_PROPERTY = "devcli.command.sandbox.image";
    public static final String SANDBOX_IMAGE_ENV = "DEVCLI_COMMAND_SANDBOX_IMAGE";
    public static final String DOCKER_BINARY_PROPERTY = "devcli.command.sandbox.docker.binary";
    public static final String DOCKER_BINARY_ENV = "DEVCLI_COMMAND_SANDBOX_DOCKER_BINARY";
    private static final String DEFAULT_SANDBOX_IMAGE = "maven:3.9.9-eclipse-temurin-17";
    private static final int MAX_COMMAND_OUTPUT_CHARS = 8_000;

    private final Backend hostBackend;
    private final Backend sandboxBackend;

    public DefaultCommandExecutionService() {
        this(new HostBackend(), new DockerBackend(Config.resolve(
                System.getProperties(), System.getenv())));
    }

    DefaultCommandExecutionService(Backend hostBackend, Backend sandboxBackend) {
        this.hostBackend = hostBackend;
        this.sandboxBackend = sandboxBackend;
    }

    @Override
    public Result execute(Request request) {
        return (request.sandboxRequired() ? sandboxBackend : hostBackend).execute(request);
    }

    static List<String> dockerCommand(Request request, Config config) {
        String mount = "type=bind,src=" + request.projectRoot()
                + ",dst=/workspace";
        List<String> command = new ArrayList<>();
        command.add(config.dockerBinary());
        command.addAll(List.of(
                "run", "--rm",
                "--pull", "never",
                "--network", request.profile().networkAllowed() ? "bridge" : "none",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--pids-limit", Integer.toString(request.profile().pidsLimit()),
                "--memory", request.profile().memoryMb() + "m",
                "--cpus", Integer.toString(request.profile().cpus()),
                "--read-only",
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=256m",
                "--tmpfs", "/root:rw,noexec,nosuid,nodev,size=256m",
                "--mount", mount,
                "--workdir", "/workspace",
                config.image(),
                "sh", "-lc", request.command()
        ));
        return command;
    }

    interface Backend {
        Result execute(Request request);
    }

    private static final class HostBackend implements Backend {
        @Override
        public Result execute(Request request) {
            return runProcess(hostShellCommand(request.command()), request.projectRoot(),
                    request.timeoutSeconds(), false);
        }
    }

    private static final class DockerBackend implements Backend {
        private final Config config;

        private DockerBackend(Config config) {
            this.config = config;
        }

        @Override
        public Result execute(Request request) {
            return runProcess(dockerCommand(request, config), request.projectRoot(),
                    request.timeoutSeconds(), true);
        }
    }

    private static Result runProcess(List<String> command, Path workingDirectory,
                                     long timeoutSeconds, boolean sandbox) {
        ExecutorService outputReader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "devcli-command-output");
            thread.setDaemon(true);
            return thread;
        });
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDirectory.toFile());
            builder.redirectErrorStream(true);
            process = builder.start();
            Process running = process;
            Future<String> output = outputReader.submit(() -> readOutput(running));
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                terminateProcessTree(process);
                process.waitFor(5, TimeUnit.SECONDS);
                output.cancel(true);
                return Result.timedOut("命令执行超时（" + timeoutSeconds + "秒），已强制终止");
            }
            String text = output.get(3, TimeUnit.SECONDS);
            int exitCode = process.exitValue();
            if (sandbox && exitCode == 125) {
                throw new IllegalStateException("Docker 命令沙箱启动失败: " + text);
            }
            return Result.completed(exitCode, text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                terminateProcessTree(process);
            }
            return Result.cancelled("用户取消了此次工具调用");
        } catch (IOException e) {
            if (process != null) {
                terminateProcessTree(process);
            }
            if (sandbox) {
                throw new IllegalStateException(
                        "隔离命令必须通过 Docker 执行，禁止回退到主机: " + e.getMessage(), e);
            }
            throw new IllegalStateException("命令进程启动失败: " + e.getMessage(), e);
        } catch (Exception e) {
            if (process != null) {
                terminateProcessTree(process);
            }
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("命令执行失败: " + e.getMessage(), e);
        } finally {
            outputReader.shutdownNow();
        }
    }

    private static List<String> hostShellCommand(String command) {
        if (isWindows()) {
            String utf8Command = "[Console]::InputEncoding = [Text.UTF8Encoding]::new($false); "
                    + "[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false); "
                    + command;
            return List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", utf8Command);
        }
        return List.of("bash", "-c", command);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String readOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int remaining = MAX_COMMAND_OUTPUT_CHARS - output.length();
                if (remaining <= 0) {
                    break;
                }
                if (line.length() > remaining) {
                    output.append(line, 0, remaining);
                    break;
                }
                output.append(line).append('\n');
            }
        }
        if (output.length() >= MAX_COMMAND_OUTPUT_CHARS) {
            output.append("\n... (输出已截断)");
        }
        return output.toString().trim();
    }

    private static void terminateProcessTree(Process process) {
        List<ProcessHandle> descendants = process.toHandle().descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--) {
            descendants.get(i).destroyForcibly();
        }
        process.destroyForcibly();
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // 进程终止后的输出流关闭失败不改变终止结果。
        }
        for (ProcessHandle descendant : descendants) {
            try {
                descendant.onExit().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 已发送强制终止信号，等待超时不阻塞调用方。
            }
        }
    }

    record Config(String dockerBinary, String image) {
        Config {
            if (dockerBinary == null || dockerBinary.isBlank()) {
                throw new IllegalArgumentException("docker binary is required");
            }
            if (image == null || image.isBlank()) {
                throw new IllegalArgumentException("sandbox image is required");
            }
        }

        static Config resolve(Properties properties, Map<String, String> environment) {
            return new Config(
                    firstNonBlank(properties.getProperty(DOCKER_BINARY_PROPERTY),
                            environment.get(DOCKER_BINARY_ENV), "docker"),
                    firstNonBlank(properties.getProperty(SANDBOX_IMAGE_PROPERTY),
                            environment.get(SANDBOX_IMAGE_ENV), DEFAULT_SANDBOX_IMAGE));
        }

        private static String firstNonBlank(String first, String second, String fallback) {
            if (first != null && !first.isBlank()) {
                return first.trim();
            }
            if (second != null && !second.isBlank()) {
                return second.trim();
            }
            return fallback;
        }
    }
}
