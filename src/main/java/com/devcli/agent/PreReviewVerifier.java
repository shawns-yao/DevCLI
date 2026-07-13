package com.devcli.agent;

import com.devcli.tool.command.CommandExecutionService;
import com.devcli.tool.command.DefaultCommandExecutionService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


/**
 * 在 Reviewer 运行前执行项目级硬验证。
 */
final class PreReviewVerifier {
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_FAILURE_OUTPUT_LENGTH = 3000;

    private final int timeoutSeconds;
    private final CommandExecutionService commandExecutionService;

    PreReviewVerifier() {
        this(DEFAULT_TIMEOUT_SECONDS, new DefaultCommandExecutionService());
    }

    PreReviewVerifier(int timeoutSeconds) {
        this(timeoutSeconds, new DefaultCommandExecutionService());
    }

    PreReviewVerifier(int timeoutSeconds, CommandExecutionService commandExecutionService) {
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.commandExecutionService = java.util.Objects.requireNonNull(
                commandExecutionService, "commandExecutionService");
    }

    Result verify(Path projectRoot, String stepId) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Path javaRoot = normalizedRoot.resolve("src/main/java");
        if (!Files.isDirectory(javaRoot)) {
            return Result.ok();
        }

        if (Files.isRegularFile(normalizedRoot.resolve("pom.xml"))) {
            return runCommand(normalizedRoot, "mvn -q -DskipTests test-compile",
                    "mvn -q -DskipTests test-compile");
        }

        List<Path> javaFiles;
        try (var stream = Files.walk(javaRoot)) {
            javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            return Result.failed("Pre-review hard check failed: 无法扫描 Java 文件：" + e.getMessage());
        }
        if (javaFiles.isEmpty()) {
            return Result.ok();
        }

        Path outputBase = normalizedRoot.resolve("target/devcli-pre-review-classes");
        Path outputDir = outputBase.resolve(safeStepId(stepId)).normalize();
        if (!outputDir.startsWith(outputBase)) {
            return Result.failed("Pre-review hard check failed: 非法步骤标识");
        }
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            return Result.failed("Pre-review hard check failed: 无法创建编译目录：" + e.getMessage());
        }

        Path argumentFile = null;
        try {
            argumentFile = Files.createTempFile(outputBase, "javac-", ".args");
            writeJavacArguments(argumentFile, normalizedRoot, outputDir, javaFiles);
            String relativeArgumentFile = normalizedRoot.relativize(argumentFile)
                    .toString().replace('\\', '/').replace("\"", "\\\"");
            return runCommand(normalizedRoot,
                    "javac @\"" + relativeArgumentFile + "\"",
                    "javac -encoding UTF-8");
        } catch (IOException e) {
            return Result.failed("Pre-review hard check failed: 无法创建 javac 参数文件：" + e.getMessage());
        } finally {
            if (argumentFile != null) {
                try {
                    Files.deleteIfExists(argumentFile);
                } catch (IOException ignored) {
                    // 参数文件清理失败不覆盖编译结果。
                }
            }
        }
    }

    private void writeJavacArguments(Path argumentFile, Path projectRoot,
                                     Path outputDir, List<Path> javaFiles) throws IOException {
        List<String> arguments = new ArrayList<>();
        arguments.add("-encoding");
        arguments.add("UTF-8");
        arguments.add("-d");
        arguments.add(quoteJavacArgument(projectRoot.relativize(outputDir)));
        javaFiles.stream()
                .map(projectRoot::relativize)
                .map(this::quoteJavacArgument)
                .forEach(arguments::add);
        Files.write(argumentFile, arguments, StandardCharsets.UTF_8);
    }

    private String quoteJavacArgument(Path path) {
        String normalized = path.toString().replace('\\', '/').replace("\"", "\\\"");
        return "\"" + normalized + "\"";
    }

    private Result runCommand(Path projectRoot, String command, String displayCommand) {
        try {
            CommandExecutionService.Result execution = commandExecutionService.execute(
                    new CommandExecutionService.Request(
                            command, projectRoot, timeoutSeconds, true));
            if (execution.succeeded()) {
                return Result.ok();
            }
            if (execution.timedOut()) {
                return Result.failed("Pre-review hard check failed: " + displayCommand
                        + " 超过 " + timeoutSeconds + "s");
            }
            if (execution.cancelled()) {
                return Result.failed("Pre-review hard check failed: " + displayCommand + " 被中断");
            }
            return Result.failed("Pre-review hard check failed: " + displayCommand
                    + "\n" + summarizeFailure(execution.output()));
        } catch (RuntimeException e) {
            return Result.failed("Pre-review hard check failed: 无法在命令沙箱执行 "
                    + displayCommand + "：" + e.getMessage());
        }
    }

    private String summarizeFailure(String output) {
        if (output == null || output.isBlank()) {
            return "无编译输出；请检查命令是否可执行。";
        }
        String abbreviated = abbreviate(output, MAX_FAILURE_OUTPUT_LENGTH);
        String[] lines = abbreviated.replace("\r", "").split("\n");
        StringBuilder summary = new StringBuilder();
        int kept = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            boolean important = kept < 8
                    || trimmed.contains("error:")
                    || trimmed.contains("错误")
                    || trimmed.contains("failed")
                    || trimmed.contains("missing")
                    || trimmed.contains("expected=")
                    || trimmed.contains("actual=");
            if (important) {
                summary.append("- ").append(trimmed).append("\n");
                kept++;
            }
            if (kept >= 14) {
                break;
            }
        }
        if (summary.isEmpty()) {
            return abbreviate(abbreviated, 1200);
        }
        if (lines.length > kept) {
            summary.append("- ...<truncated>\n");
        }
        return summary.toString();
    }


    private String safeStepId(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return "step";
        }
        return stepId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "\n...<truncated>";
    }

    record Result(boolean passed, String feedback) {
        Result {
            feedback = feedback == null ? "" : feedback;
        }

        static Result ok() {
            return new Result(true, "");
        }

        static Result failed(String feedback) {
            return new Result(false,
                    feedback == null ? "Pre-review hard check failed" : feedback);
        }
    }
}
