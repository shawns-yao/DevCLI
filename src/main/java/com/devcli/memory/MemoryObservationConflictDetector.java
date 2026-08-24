package com.devcli.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将高置信工具观察转换为可确定处理的长期记忆冲突。 */
final class MemoryObservationConflictDetector {
    private static final Pattern PATH_ARG = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern GRADLE_ROOT_FILE = Pattern.compile(
            "(?im)^\\[f]\\s+(?:gradlew(?:\\.bat)?|build\\.gradle(?:\\.kts)?|settings\\.gradle(?:\\.kts)?)\\s*$");
    private static final Pattern MAVEN_ROOT_FILE = Pattern.compile(
            "(?im)^\\[f]\\s+(?:mvnw(?:\\.cmd)?|pom\\.xml)\\s*$");

    private MemoryObservationConflictDetector() {
    }

    static Optional<Observation> observe(String toolName, String argsJson, String result) {
        if (toolName == null || result == null || result.isBlank()) {
            return Optional.empty();
        }
        String path = extractPath(argsJson);
        if ("list_dir".equals(toolName) && isProjectRoot(path)) {
            boolean gradle = GRADLE_ROOT_FILE.matcher(result).find();
            boolean maven = MAVEN_ROOT_FILE.matcher(result).find();
            if (gradle == maven) {
                return Optional.empty();
            }
            return Optional.of(buildObservation(gradle ? "gradle" : "maven",
                    gradle ? "项目根目录检测到 Gradle 构建文件" : "项目根目录检测到 Maven 构建文件"));
        }
        if ("read_file".equals(toolName) && result.startsWith("文件内容:")) {
            if (!isProjectRootFile(path)) {
                return Optional.empty();
            }
            String fileName = fileName(path);
            if (isGradleBuildFile(fileName)) {
                return Optional.of(buildObservation("gradle", "成功读取 Gradle 构建文件 " + fileName));
            }
            if (isMavenBuildFile(fileName)) {
                return Optional.of(buildObservation("maven", "成功读取 Maven 构建文件 " + fileName));
            }
        }
        return Optional.empty();
    }

    static List<MemoryEntry> conflictingEntries(Observation observation, List<MemoryEntry> entries) {
        if (observation == null || entries == null) {
            return List.of();
        }
        List<MemoryEntry> conflicts = new ArrayList<>();
        for (MemoryEntry entry : entries) {
            if (entry == null || !entry.isRecallable()) {
                continue;
            }
            String recordedObservation = entry.getMetadata().getOrDefault("observed_value", "");
            if ("true".equals(entry.getMetadata().get("negative_fact"))) {
                if (observation.subject().equals(entry.getSubject())
                        && !recordedObservation.isBlank()
                        && !observation.value().equals(recordedObservation)) {
                    conflicts.add(entry);
                }
                continue;
            }
            String entrySubject = entry.getSubject().isBlank()
                    ? MemorySubjectExtractor.extract(entry.getContent(), entry.getMetadata())
                    : entry.getSubject();
            if (observation.subject().equals(entrySubject)) {
                String recordedValue = recordedObservation.isBlank()
                        ? StructuredClaim.parse(entry.getContent())
                        .map(StructuredClaim.Claim::value).orElse("")
                        : recordedObservation;
                if (!recordedValue.isBlank()) {
                    if (!normalizeValue(observation.value()).equals(normalizeValue(recordedValue))) {
                        conflicts.add(entry);
                    }
                    continue;
                }
            }
            String lower = entry.getContent().toLowerCase(Locale.ROOT);
            boolean buildClaim = "project.build_system".equals(entry.getSubject())
                    || "project.default_test_command".equals(entry.getSubject())
                    || lower.contains("构建工具") || lower.contains("构建系统")
                    || lower.contains("build tool") || lower.contains("build system")
                    || lower.contains("mvn test") || lower.contains("gradle test")
                    || lower.contains("gradlew test");
            if (!buildClaim) {
                continue;
            }
            boolean contradicts = "gradle".equals(observation.value())
                    ? containsAny(lower, "maven", "mvn test", "mvnw", "pom.xml")
                    : containsAny(lower, "gradle", "gradlew", "build.gradle", "settings.gradle");
            if (contradicts) {
                conflicts.add(entry);
            }
        }
        return List.copyOf(conflicts);
    }

    static Observation fromSideChannel(CurrentStateObservationSideChannel sideChannel) {
        return new Observation(sideChannel.subject(), sideChannel.value(), sideChannel.evidence(),
                sideChannel.confidence());
    }

    static String subjectFor(MemoryEntry entry, Observation observation) {
        if (entry != null && !entry.getSubject().isBlank()) {
            return entry.getSubject();
        }
        String inferred = entry == null ? "" : MemorySubjectExtractor.extract(entry.getContent(), entry.getMetadata());
        return inferred.isBlank() ? observation.subject() : inferred;
    }

    private static Observation buildObservation(String value, String evidence) {
        return new Observation("project.build_system", value, evidence,
                CurrentStateObservationSideChannel.ObservationStrength.HIGH);
    }

    private static String extractPath(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return "";
        }
        Matcher matcher = PATH_ARG.matcher(argsJson);
        return matcher.find() ? matcher.group(1).replace('\\', '/').trim() : "";
    }

    private static boolean isProjectRoot(String path) {
        return path.isBlank() || ".".equals(path) || "./".equals(path);
    }

    private static boolean isProjectRootFile(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return !normalized.contains("/");
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return (slash < 0 ? path : path.substring(slash + 1)).toLowerCase(Locale.ROOT);
    }

    private static boolean isGradleBuildFile(String fileName) {
        return fileName.equals("gradlew") || fileName.equals("gradlew.bat")
                || fileName.equals("build.gradle") || fileName.equals("build.gradle.kts")
                || fileName.equals("settings.gradle") || fileName.equals("settings.gradle.kts");
    }

    private static boolean isMavenBuildFile(String fileName) {
        return fileName.equals("pom.xml") || fileName.equals("mvnw") || fileName.equals("mvnw.cmd");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeValue(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}._:/!-]+", "");
    }

    record Observation(String subject, String value, String evidence,
                       CurrentStateObservationSideChannel.ObservationStrength strength) {
    }
}
