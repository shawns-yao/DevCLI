package com.devcli.tool.command;

import com.devcli.runtime.CancellationToken;

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
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

public final class DefaultCommandExecutionService implements CommandExecutionService {
    public static final String SANDBOX_MODE_PROPERTY = "devcli.command.sandbox.mode";
    public static final String SANDBOX_MODE_ENV = "DEVCLI_COMMAND_SANDBOX_MODE";
    public static final String SANDBOX_IMAGE_PROPERTY = "devcli.command.sandbox.image";
    public static final String SANDBOX_IMAGE_ENV = "DEVCLI_COMMAND_SANDBOX_IMAGE";
    public static final String DOCKER_BINARY_PROPERTY = "devcli.command.sandbox.docker.binary";
    public static final String DOCKER_BINARY_ENV = "DEVCLI_COMMAND_SANDBOX_DOCKER_BINARY";
    public static final String SANDBOX_USER_PROPERTY = "devcli.command.sandbox.user";
    public static final String SANDBOX_USER_ENV = "DEVCLI_COMMAND_SANDBOX_USER";
    private static final String DEFAULT_SANDBOX_IMAGE = "maven:3.9.9-eclipse-temurin-17";
    private static final int MAX_COMMAND_OUTPUT_CHARS = 8_000;

    private final Backend hostBackend;
    private final Backend sandboxBackend;
    private final SandboxMode sandboxMode;

    public DefaultCommandExecutionService() {
        this(Config.resolve(System.getProperties(), System.getenv()));
    }

    private DefaultCommandExecutionService(Config config) {
        this(new HostBackend(), new DockerBackend(config), config.mode());
    }

    DefaultCommandExecutionService(Backend hostBackend, Backend sandboxBackend) {
        this(hostBackend, sandboxBackend, SandboxMode.DOCKER);
    }

    DefaultCommandExecutionService(Backend hostBackend, Backend sandboxBackend,
                                   SandboxMode sandboxMode) {
        this.hostBackend = hostBackend;
        this.sandboxBackend = sandboxBackend;
        this.sandboxMode = sandboxMode == null ? SandboxMode.DOCKER : sandboxMode;
    }

    @Override
    public Result execute(Request request) {
        if (!request.sandboxRequired() || sandboxMode == SandboxMode.DOCKER) {
            return (request.sandboxRequired() ? sandboxBackend : hostBackend).execute(request);
        }
        String hostCommand = HostWarnCommandPolicy.validateAndNormalize(request.command());
        Request hostRequest = new Request(hostCommand, request.projectRoot(), request.timeoutSeconds(),
                false, request.executionContext());
        Result result = hostBackend.execute(hostRequest);
        return new Result(result.exitCode(),
                "⚠️ 沙箱模式 HOST_WARN：隔离命令在主机上执行，风险由用户承担。\n"
                        + result.output(),
                result.timedOut(), result.cancelled());
    }

    static List<String> dockerCommand(Request request, Config config) {
        return dockerCommand(request, config, newContainerName());
    }

    static List<String> dockerCommand(Request request, Config config, String containerName) {
        String mount = "type=bind,src=" + request.projectRoot()
                + ",dst=/workspace";
        List<String> command = new ArrayList<>();
        command.add(config.dockerBinary());
        command.addAll(List.of(
                "run", "--rm",
                "--name", containerName,
                "--pull", "never",
                "--network", "none",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--pids-limit", "256",
                "--memory", "1g",
                "--cpus", "2",
                "--read-only",
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=256m",
                "--tmpfs", "/root:rw,noexec,nosuid,nodev,size=256m",
                "--mount", mount,
                "--workdir", "/workspace",
                config.image(), "sh", "-lc", request.command()));
        if (config.user() != null && !config.user().isBlank()) {
            command.add(2, config.user());
            command.add(2, "--user");
        }
        return command;
    }

    static List<String> dockerCleanupCommand(Config config, String containerName) {
        return List.of(config.dockerBinary(), "rm", "-f", containerName);
    }

    private static String newContainerName() {
        return "devcli-run-" + UUID.randomUUID().toString().replace("-", "");
    }

    interface Backend {
        Result execute(Request request);
    }

    private static final class HostBackend implements Backend {
        @Override
        public Result execute(Request request) {
            return runProcess(hostShellCommand(request.command()), request, false);
        }
    }

    private static final class DockerBackend implements Backend {
        private final Config config;

        private DockerBackend(Config config) {
            this.config = config;
        }

        @Override
        public Result execute(Request request) {
            String containerName = newContainerName();
            return runProcess(dockerCommand(request, config, containerName), request, true,
                    () -> removeDockerContainer(config, containerName));
        }
    }

    private static Result runProcess(List<String> command, Request request, boolean sandbox) {
        return runProcess(command, request, sandbox, () -> { });
    }

    private static Result runProcess(List<String> command, Request request, boolean sandbox,
                                     Runnable externalCleanup) {
        ExecutorService outputReader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "devcli-command-output");
            thread.setDaemon(true);
            return thread;
        });
        Process process = null;
        Runnable termination = () -> { };
        CancellationToken.Registration cancellationRegistration =
                CancellationToken.Registration.NO_OP;
        try {
            request.executionContext().throwIfCancelled();
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(request.projectRoot().toFile());
            builder.redirectErrorStream(true);
            process = builder.start();
            Process running = process;
            termination = termination(running, externalCleanup);
            cancellationRegistration = request.executionContext().cancellationToken()
                    .onCancel(ignored -> signalProcessTree(running));
            Future<String> output = outputReader.submit(() -> readOutput(running));
            if (!process.waitFor(request.timeoutSeconds(), TimeUnit.SECONDS)) {
                termination.run();
                output.cancel(true);
                return Result.timedOut("命令执行超时（" + request.timeoutSeconds()
                        + "秒），已强制终止");
            }
            if (request.executionContext().cancellation().isPresent()) {
                termination.run();
                output.cancel(true);
                return cancellationResult(request);
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
                termination.run();
            }
            return request.executionContext().cancellation().isPresent()
                    ? cancellationResult(request)
                    : Result.cancelled("用户取消了此次工具调用");
        } catch (CancellationException e) {
            if (process != null) {
                termination.run();
            }
            return cancellationResult(request);
        } catch (IOException e) {
            if (process != null) {
                termination.run();
            }
            if (sandbox) {
                throw new IllegalStateException(
                        "隔离命令必须通过 Docker 执行，禁止回退到主机: " + e.getMessage(), e);
            }
            throw new IllegalStateException("命令进程启动失败: " + e.getMessage(), e);
        } catch (Exception e) {
            if (process != null) {
                termination.run();
            }
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("命令执行失败: " + e.getMessage(), e);
        } finally {
            cancellationRegistration.close();
            outputReader.shutdownNow();
            awaitOutputReader(outputReader);
        }
    }

    private static Result cancellationResult(Request request) {
        CancellationToken.Cancellation cancellation = request.executionContext()
                .cancellation()
                .orElse(new CancellationToken.Cancellation(
                        CancellationToken.Reason.INTERRUPTED, "工具执行被中断"));
        if (cancellation.reason() == CancellationToken.Reason.TIMEOUT) {
            return Result.timedOut(cancellation.message().isBlank()
                    ? "命令执行超过工具期限，已强制终止"
                    : cancellation.message());
        }
        return Result.cancelled(cancellation.message().isBlank()
                ? "用户取消了此次工具调用"
                : cancellation.message());
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
        boolean restoreInterrupt = Thread.interrupted();
        List<ProcessHandle> descendants = process.toHandle().descendants().toList();
        signalProcessTree(process, descendants);
        try {
            try {
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                restoreInterrupt = true;
            }
            for (ProcessHandle descendant : descendants) {
                if (descendant.isAlive()) {
                    descendant.destroyForcibly();
                }
            }
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void signalProcessTree(Process process) {
        signalProcessTree(process, process.toHandle().descendants().toList());
    }

    private static void signalProcessTree(Process process, List<ProcessHandle> descendants) {
        for (int i = descendants.size() - 1; i >= 0; i--) {
            descendants.get(i).destroyForcibly();
        }
        process.destroyForcibly();
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // 进程终止后的输出流关闭失败不改变终止结果。
        }
    }

    private static void awaitOutputReader(ExecutorService outputReader) {
        boolean restoreInterrupt = Thread.interrupted();
        try {
            try {
                outputReader.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                restoreInterrupt = true;
            }
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Runnable termination(Process process, Runnable externalCleanup) {
        AtomicBoolean invoked = new AtomicBoolean();
        return () -> {
            if (!invoked.compareAndSet(false, true)) {
                return;
            }
            try {
                terminateProcessTree(process);
            } finally {
                externalCleanup.run();
            }
        };
    }

    private static void removeDockerContainer(Config config, String containerName) {
        Process cleanup = null;
        try {
            cleanup = new ProcessBuilder(dockerCleanupCommand(config, containerName))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!cleanup.waitFor(5, TimeUnit.SECONDS)) {
                cleanup.destroyForcibly();
            }
        } catch (IOException e) {
            // Docker 客户端仍会在 finally 中终止；容器运行时不可用时无法继续清理。
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (cleanup != null) {
                cleanup.destroyForcibly();
            }
        }
    }

    public enum SandboxMode {
        DOCKER,
        HOST_WARN;

        static SandboxMode parse(String value) {
            if (value == null || value.isBlank()) {
                return DOCKER;
            }
            return switch (value.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
                case "DOCKER" -> DOCKER;
                case "HOST_WARN" -> HOST_WARN;
                default -> throw new IllegalArgumentException(
                        "sandbox mode must be DOCKER|HOST_WARN: " + value);
            };
        }
    }

    record Config(String dockerBinary, String image, SandboxMode mode, String user) {
        Config {
            if (dockerBinary == null || dockerBinary.isBlank()) {
                throw new IllegalArgumentException("docker binary is required");
            }
            if (image == null || image.isBlank()) {
                throw new IllegalArgumentException("sandbox image is required");
            }
            mode = mode == null ? SandboxMode.DOCKER : mode;
            user = user == null ? "" : user.trim();
        }

        static Config resolve(Properties properties, Map<String, String> environment) {
            return new Config(
                    firstNonBlank(properties.getProperty(DOCKER_BINARY_PROPERTY),
                            environment.get(DOCKER_BINARY_ENV), "docker"),
                    firstNonBlank(properties.getProperty(SANDBOX_IMAGE_PROPERTY),
                            environment.get(SANDBOX_IMAGE_ENV), DEFAULT_SANDBOX_IMAGE),
                    SandboxMode.parse(firstNonBlank(
                            properties.getProperty(SANDBOX_MODE_PROPERTY),
                            environment.get(SANDBOX_MODE_ENV), "DOCKER")),
                    firstNonBlank(properties.getProperty(SANDBOX_USER_PROPERTY),
                            environment.get(SANDBOX_USER_ENV), ""));
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
