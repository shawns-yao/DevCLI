package com.devcli.tool.command;

import com.devcli.concurrent.CancellationToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
    public static final String SANDBOX_MAVEN_REPOSITORY_PROPERTY =
            "devcli.command.sandbox.maven.repository";
    public static final String SANDBOX_MAVEN_REPOSITORY_ENV =
            "DEVCLI_COMMAND_SANDBOX_MAVEN_REPOSITORY";
    private static final String DEFAULT_SANDBOX_IMAGE = "maven:3.9.9-eclipse-temurin-17";
    private static final int MAX_COMMAND_OUTPUT_CHARS = 8_000;

    private final Backend hostBackend;
    private final Backend sandboxBackend;
    private final SandboxMode sandboxMode;
    private final String mavenRepository;

    public DefaultCommandExecutionService() {
        this(Config.resolve(System.getProperties(), System.getenv()));
    }

    private DefaultCommandExecutionService(Config config) {
        this(new HostBackend(), new DockerBackend(config), config);
    }

    DefaultCommandExecutionService(Backend hostBackend, Backend sandboxBackend) {
        this(hostBackend, sandboxBackend, SandboxMode.DOCKER);
    }

    DefaultCommandExecutionService(Backend hostBackend, Backend sandboxBackend,
                                   SandboxMode sandboxMode) {
        this(hostBackend, sandboxBackend, sandboxMode, "");
    }

    DefaultCommandExecutionService(Backend hostBackend, Backend sandboxBackend,
                                   Config config) {
        this(hostBackend, sandboxBackend, config == null ? SandboxMode.DOCKER : config.mode(),
                config == null ? "" : config.mavenRepository());
    }

    private DefaultCommandExecutionService(Backend hostBackend, Backend sandboxBackend,
                                           SandboxMode sandboxMode, String mavenRepository) {
        this.hostBackend = hostBackend;
        this.sandboxBackend = sandboxBackend;
        this.sandboxMode = sandboxMode == null ? SandboxMode.DOCKER : sandboxMode;
        this.mavenRepository = mavenRepository == null ? "" : mavenRepository;
    }

    @Override
    public boolean executesOnHost(boolean sandboxRequired) {
        return !sandboxRequired || sandboxMode != SandboxMode.DOCKER;
    }

    @Override
    public boolean providesIsolation(boolean sandboxRequired) {
        return sandboxRequired && (sandboxMode == SandboxMode.DOCKER || sandboxMode == SandboxMode.WINDOWS_NATIVE);
    }

    @Override
    public void validateRequest(Request request) {
        if (request.sandboxRequired() && !providesIsolation(true)) {
            try {
                HostWarnCommandPolicy.validateAndNormalize(request.command());
            } catch (IllegalArgumentException denied) {
                throw new com.devcli.policy.PolicyException(denied.getMessage());
            }
        }
    }

    @Override
    public Result execute(Request request) {
        if (request.sandboxRequired() && sandboxMode == SandboxMode.WINDOWS_NATIVE) {
            if (!isWindows()) throw new IllegalStateException("WINDOWS_NATIVE requires Windows");
            String shell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
            String encoded = java.util.Base64.getEncoder().encodeToString(
                    ("$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; "
                            + "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); "
                            + "New-PSDrive -Name Work -PSProvider FileSystem -Root '"
                            + request.projectRoot().toString().replace("'", "''")
                            + "' | Out-Null; Set-Location -LiteralPath 'Work:\\'; "
                            + request.command() + "; if ($null -ne $LASTEXITCODE) { exit $LASTEXITCODE }")
                            .getBytes(StandardCharsets.UTF_16LE));
            return runProcess(List.of(shell, "-NoProfile", "-NonInteractive", "-OutputFormat", "Text", "-EncodedCommand", encoded),
                    request, false, () -> { }, true);
        }
        if (!request.sandboxRequired() || sandboxMode == SandboxMode.DOCKER) {
            return (request.sandboxRequired() ? sandboxBackend : hostBackend).execute(request);
        }
        String hostCommand = HostWarnCommandPolicy.validateAndNormalize(request.command());
        hostCommand = withMavenRepository(hostCommand, mavenRepository);
        Request hostRequest = new Request(hostCommand, request.projectRoot(), request.timeoutSeconds(),
                true, request.executionContext());
        Result result = hostBackend.execute(hostRequest);
        return new Result(result.exitCode(),
                "⚠️ 主机模式 HOST_RESTRICTED（兼容 HOST_WARN）：仅限制命令，不提供操作系统隔离。"
                        + "项目 Maven 插件、构建脚本和 javac 注解处理器仍可执行任意主机代码；"
                        + "仅用于可信项目，不可信项目必须使用 Docker。\n"
                        + result.output(),
                result.timedOut(), result.cancelled(), result.artifact(), result.outputIncomplete());
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
        if (!config.mavenRepository().isBlank() && isMavenCommand(request.command())) {
            int workdirIndex = command.indexOf("--workdir");
            command.addAll(workdirIndex, List.of(
                    "--mount", "type=bind,src=" + config.mavenRepository()
                            + ",dst=/maven-repository,readonly",
                    "--env", "MAVEN_OPTS=-Dmaven.repo.local=/maven-repository"));
        }
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

    private static String withMavenRepository(String command, String repository) {
        if (repository == null || repository.isBlank() || !isMavenCommand(command)) {
            return command;
        }
        String trimmed = command == null ? "" : command.trim();
        int separator = trimmed.indexOf(' ');
        String executable = separator < 0 ? trimmed : trimmed.substring(0, separator);
        String remainder = separator < 0 ? "" : trimmed.substring(separator);
        return executable + " -Dmaven.repo.local=\"" + repository + "\"" + remainder;
    }

    private static boolean isMavenCommand(String command) {
        String trimmed = command == null ? "" : command.trim();
        int separator = trimmed.indexOf(' ');
        String executable = separator < 0 ? trimmed : trimmed.substring(0, separator);
        String normalizedExecutable = executable.replace('\\', '/');
        int slash = normalizedExecutable.lastIndexOf('/');
        String name = (slash < 0 ? normalizedExecutable
                : normalizedExecutable.substring(slash + 1)).toLowerCase(Locale.ROOT);
        return Set.of("mvn", "mvn.cmd", "mvnw", "mvnw.cmd").contains(name);
    }

    interface Backend {
        Result execute(Request request);
    }

    private static final class HostBackend implements Backend {
        @Override
        public Result execute(Request request) {
            return runProcess(hostShellCommand(
                    request.command(), isWindows(), request.sandboxRequired()), request, false);
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
        return runProcess(command, request, sandbox, externalCleanup, false);
    }

    private static Result runProcess(List<String> command, Request request, boolean sandbox,
                                     Runnable externalCleanup, boolean windowsNative) {
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
            process = windowsNative
                    ? com.devcli.sandbox.WindowsSandboxProcess.start(command, request.projectRoot(), true,
                            request.timeoutSeconds(), request.executionContext()::isCancelled, true)
                    : builder.start();
            Process running = process;
            termination = termination(running, externalCleanup);
            cancellationRegistration = request.executionContext().cancellationToken()
                    .onCancel(ignored -> signalProcessTree(running));
            Future<CapturedOutput> output = outputReader.submit(() -> readOutput(running));
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
            CapturedOutput captured = output.get(3, TimeUnit.SECONDS);
            String text = captured.text();
            int exitCode = process.exitValue();
            if (process instanceof com.devcli.sandbox.WindowsSandboxProcess nativeProcess && nativeProcess.timedOut())
                return Result.timedOut("Windows 沙箱执行超时，Job 已终止\n" + text);
            if (sandbox && exitCode == 125) {
                throw new IllegalStateException("Docker 命令沙箱启动失败: " + text);
            }
            return new Result(exitCode, text, false, false, captured.artifact(), captured.incomplete());
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
            if (request.executionContext().isCancelled()) return cancellationResult(request);
            if (sandbox || windowsNative) {
                throw new IllegalStateException(
                        "隔离命令启动失败，禁止回退到主机: " + e.getMessage(), e);
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

    static List<String> hostShellCommand(String command, boolean windows,
                                         boolean validatedHostWarnCommand) {
        if (windows && validatedHostWarnCommand) {
            return List.of("cmd.exe", "/d", "/s", "/c", command);
        }
        if (windows) {
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

    private record CapturedOutput(String text, com.devcli.tool.ToolResultArtifact artifact, boolean incomplete) {}

    private static CapturedOutput readOutput(Process process) throws IOException {
        StringBuilder head = new StringBuilder();
        StringBuilder tail = new StringBuilder();
        boolean incomplete = false;
        try (var stored = com.devcli.tool.ToolResultArtifactStore.openWriter("command");
             var reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int count;
            long chars = 0;
            while ((count = reader.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("命令输出读取已取消");
                String chunk = new String(buffer, 0, count);
                if (!stored.append(chunk)) {
                    incomplete = true;
                    signalProcessTree(process);
                    break;
                }
                chars += count;
                int remaining = MAX_COMMAND_OUTPUT_CHARS - head.length();
                if (remaining > 0) head.append(chunk, 0, Math.min(remaining, chunk.length()));
                tail.append(chunk);
                if (tail.length() > 1000) tail.delete(0, tail.length() - 1000);
            }
            var artifact = stored.finish();
            String text = chars <= MAX_COMMAND_OUTPUT_CHARS ? head.toString()
                    : head.substring(0, 1500) + "\n[中间日志已落盘]\n" + tail;
            text += "\n[result_ref=" + artifact.ref() + ", offset=0, output_complete=" + !incomplete + "]";
            if (incomplete) text += "\n[输出超过存储配额，已终止命令；引用仅包含已接收部分，结果不完整]";
            return new CapturedOutput(text, new com.devcli.tool.ToolResultArtifact(
                    "PERSISTED_PREVIEW", artifact.chars(), artifact.bytes(),
                    (int) Math.min(chars, MAX_COMMAND_OUTPUT_CHARS), artifact.ref(), "0", artifact.sha256()), incomplete);
        } catch (IOException failure) {
            signalProcessTree(process);
            return new CapturedOutput(head + "\n[命令输出存储失败，已终止命令；原文不可恢复，结果不完整]",
                    null, true);
        }
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
        WINDOWS_NATIVE,
        HOST_RESTRICTED,
        HOST_WARN;

        static SandboxMode parse(String value) {
            if (value == null || value.isBlank()) {
                return DOCKER;
            }
            return switch (value.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
                case "DOCKER" -> DOCKER;
                case "WINDOWS_NATIVE" -> WINDOWS_NATIVE;
                case "HOST_WARN" -> HOST_WARN;
                case "HOST_RESTRICTED" -> HOST_RESTRICTED;
                default -> throw new IllegalArgumentException(
                        "sandbox mode must be DOCKER|WINDOWS_NATIVE|HOST_WARN|HOST_RESTRICTED: " + value);
            };
        }
    }

    record Config(String dockerBinary, String image, SandboxMode mode, String user,
                  String mavenRepository) {
        Config {
            if (dockerBinary == null || dockerBinary.isBlank()) {
                throw new IllegalArgumentException("docker binary is required");
            }
            if (image == null || image.isBlank()) {
                throw new IllegalArgumentException("sandbox image is required");
            }
            mode = mode == null ? SandboxMode.DOCKER : mode;
            user = user == null ? "" : user.trim();
            mavenRepository = normalizeMavenRepository(mavenRepository);
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
                            environment.get(SANDBOX_USER_ENV), ""),
                    firstNonBlank(properties.getProperty(SANDBOX_MAVEN_REPOSITORY_PROPERTY),
                            environment.get(SANDBOX_MAVEN_REPOSITORY_ENV), ""));
        }

        private static String normalizeMavenRepository(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            if (value.contains("\"") || value.contains("\r") || value.contains("\n")) {
                throw new IllegalArgumentException("sandbox Maven repository path is invalid");
            }
            Path path = Path.of(value.trim());
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException(
                        "sandbox Maven repository path must be absolute: " + value);
            }
            Path normalized = path.normalize();
            if (!Files.isDirectory(normalized)) {
                throw new IllegalArgumentException(
                        "sandbox Maven repository path must be an existing directory: " + value);
            }
            return normalized.toString();
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
