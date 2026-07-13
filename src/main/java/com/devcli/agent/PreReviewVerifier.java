package com.devcli.agent;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 在 Reviewer 运行前执行项目级硬验证。
 */
final class PreReviewVerifier {
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_FAILURE_OUTPUT_LENGTH = 3000;

    private final int timeoutSeconds;

    PreReviewVerifier() {
        this(DEFAULT_TIMEOUT_SECONDS);
    }

    PreReviewVerifier(int timeoutSeconds) {
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    Result verify(Path projectRoot, String stepId) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Path javaRoot = normalizedRoot.resolve("src/main/java");
        if (!Files.isDirectory(javaRoot)) {
            return Result.ok();
        }

        if (Files.isRegularFile(normalizedRoot.resolve("pom.xml"))) {
            return runCommand(normalizedRoot, mavenTestCompileCommand(),
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
            return runCommand(normalizedRoot,
                    List.of("javac", "@" + argumentFile.toAbsolutePath().normalize()),
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

    private List<String> mavenTestCompileCommand() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return List.of("cmd.exe", "/c", "mvn", "-q", "-DskipTests", "test-compile");
        }
        return List.of("mvn", "-q", "-DskipTests", "test-compile");
    }

    private Result runCommand(Path projectRoot, List<String> command, String displayCommand) {
        Path outputFile = null;
        try {
            outputFile = Files.createTempFile("devcli-pre-review-", ".log");
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(projectRoot.toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(outputFile.toFile());

            Process process = processBuilder.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return Result.failed("Pre-review hard check failed: " + displayCommand
                        + " 超过 " + timeoutSeconds + "s");
            }
            String output = decodeProcessOutput(Files.readAllBytes(outputFile));
            if (process.exitValue() == 0) {
                return Result.ok();
            }
            return Result.failed("Pre-review hard check failed: " + displayCommand
                    + "\n" + summarizeFailure(output));
        } catch (IOException e) {
            return Result.failed("Pre-review hard check failed: 无法执行 " + displayCommand
                    + "：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("Pre-review hard check failed: " + displayCommand + " 被中断");
        } finally {
            if (outputFile != null) {
                try {
                    Files.deleteIfExists(outputFile);
                } catch (IOException ignored) {
                    // 临时验证日志清理失败不改变验证结论。
                }
            }
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

    private String decodeProcessOutput(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!looksMojibake(utf8)) {
            return utf8;
        }
        String platform = new String(bytes, Charset.defaultCharset());
        if (!looksMojibake(platform)) {
            return platform;
        }
        try {
            String gbk = new String(bytes, Charset.forName("GBK"));
            if (!looksMojibake(gbk)) {
                return gbk;
            }
        } catch (Exception ignored) {
            // 不支持 GBK 时保留 UTF-8 解码结果。
        }
        return utf8;
    }

    private boolean looksMojibake(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.indexOf('\uFFFD') >= 0 || text.contains("????");
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
