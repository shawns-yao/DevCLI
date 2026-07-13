package com.devcli.mcp.transport;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ExecutableResolver {
    private static final String DEFAULT_WINDOWS_EXTENSIONS = ".COM;.EXE;.BAT;.CMD";

    private ExecutableResolver() {
    }

    static String resolve(String command, Map<String, String> environment, boolean windows) {
        if (command == null || command.isBlank() || !windows || isExplicitPath(command)) {
            return command;
        }
        String pathValue = environmentValue(environment, "PATH");
        if (pathValue == null || pathValue.isBlank()) {
            return command;
        }
        List<String> names = candidateNames(command,
                environmentValue(environment, "PATHEXT"));
        for (String directory : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String normalized = stripQuotes(directory.trim());
            if (normalized.isEmpty()) {
                continue;
            }
            for (String name : names) {
                Path candidate = Path.of(normalized, name).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return command;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    private static boolean isExplicitPath(String command) {
        return command.indexOf('/') >= 0
                || command.indexOf('\\') >= 0
                || Path.of(command).isAbsolute();
    }

    private static List<String> candidateNames(String command, String pathExt) {
        List<String> extensions = new ArrayList<>();
        String configured = pathExt == null || pathExt.isBlank()
                ? DEFAULT_WINDOWS_EXTENSIONS : pathExt;
        for (String rawExtension : configured.split(";")) {
            String extension = rawExtension.trim();
            if (extension.isEmpty()) {
                continue;
            }
            if (!extension.startsWith(".")) {
                extension = "." + extension;
            }
            extensions.add(extension.toLowerCase(Locale.ROOT));
        }
        String lower = command.toLowerCase(Locale.ROOT);
        if (extensions.stream().anyMatch(lower::endsWith)) {
            return List.of(command);
        }
        return extensions.stream()
                .map(extension -> command + extension)
                .toList();
    }

    private static String environmentValue(Map<String, String> environment, String key) {
        if (environment == null || environment.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
