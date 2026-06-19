package com.devcli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Converts CodeSearchNet Java rows into a small synthetic Java project that DevCLI's
 * existing CodeIndex/CodeChunker pipeline can index without cloning upstream repos.
 */
final class CodeSearchNetJavaDatasetAdapter {
    private CodeSearchNetJavaDatasetAdapter() {
    }

    static List<SourceCase> fromHuggingFaceRows(JsonNode root, int limit) {
        if (root == null || limit <= 0) {
            return List.of();
        }
        JsonNode rows = root.path("rows");
        if (!rows.isArray()) {
            return List.of();
        }
        List<SourceCase> cases = new ArrayList<>();
        int index = 0;
        for (JsonNode wrapper : rows) {
            if (cases.size() >= limit) {
                break;
            }
            JsonNode row = wrapper.path("row");
            Optional<SourceCase> sourceCase = fromDatasetRow(row, index++);
            sourceCase.ifPresent(cases::add);
        }
        return List.copyOf(cases);
    }

    static Optional<SourceCase> fromDatasetRow(JsonNode row, int index) {
        if (row == null || row.isMissingNode() || row.isNull()) {
            return Optional.empty();
        }
        String language = text(row, "language");
        if (!language.isBlank() && !"java".equalsIgnoreCase(language)) {
            return Optional.empty();
        }
        String function = firstNonBlank(text(row, "whole_func_string"), text(row, "func_code_string"));
        String documentation = text(row, "func_documentation_string");
        String functionName = text(row, "func_name");
        if (function.isBlank() || functionName.isBlank()) {
            return Optional.empty();
        }

        String methodName = methodName(functionName);
        String originalPath = text(row, "func_path_in_repository");
        String repository = text(row, "repository_name");
        String className = className(index, originalPath, functionName);
        String packageName = "codesearchnet." + sanitizeIdentifier(repository.isBlank() ? "sample" : repository);
        String sourcePath = "src/main/java/" + packageName.replace('.', '/') + "/" + className + ".java";
        String source = renderSource(packageName, className, documentation, function);
        String query = documentation.isBlank() ? methodName : documentation;
        String id = (repository + ":" + originalPath + ":" + functionName).replaceAll("^:+|:+$", "");
        if (id.isBlank()) {
            id = "codesearchnet-java-" + index;
        }

        return Optional.of(new SourceCase(
                id,
                repository,
                originalPath,
                sourcePath,
                source,
                query,
                className + "." + methodName));
    }

    static void writeSyntheticProject(Path projectRoot, List<SourceCase> cases) throws IOException {
        if (projectRoot == null || cases == null) {
            return;
        }
        for (SourceCase sourceCase : cases) {
            Path file = projectRoot.resolve(sourceCase.sourcePath()).normalize();
            Files.createDirectories(file.getParent());
            Files.writeString(file, sourceCase.source(), StandardCharsets.UTF_8);
        }
    }

    private static String renderSource(String packageName, String className, String documentation, String function) {
        StringBuilder source = new StringBuilder();
        source.append("package ").append(packageName).append(";\n\n");
        source.append("public class ").append(className).append(" {\n");
        if (!documentation.isBlank()) {
            source.append("    /**\n");
            for (String line : documentation.replace("*/", "* /").split("\\R")) {
                source.append("     * ").append(line.strip()).append("\n");
            }
            source.append("     */\n");
        }
        for (String line : function.split("\\R", -1)) {
            source.append("    ").append(line).append("\n");
        }
        source.append("}\n");
        return source.toString();
    }

    private static String className(int index, String path, String functionName) {
        String base = "CodeSearchNetCase" + String.format(Locale.ROOT, "%04d", Math.max(0, index));
        String fileName = path == null ? "" : path.replace('\\', '/');
        int slash = fileName.lastIndexOf('/');
        if (slash >= 0) {
            fileName = fileName.substring(slash + 1);
        }
        if (fileName.endsWith(".java")) {
            fileName = fileName.substring(0, fileName.length() - ".java".length());
        }
        String suffix = sanitizeTypeName(firstNonBlank(fileName, functionName));
        return suffix.isBlank() ? base : base + "_" + suffix;
    }

    private static String methodName(String functionName) {
        String normalized = functionName == null ? "" : functionName.trim();
        int dot = normalized.lastIndexOf('.');
        if (dot >= 0 && dot < normalized.length() - 1) {
            normalized = normalized.substring(dot + 1);
        }
        int paren = normalized.indexOf('(');
        if (paren > 0) {
            normalized = normalized.substring(0, paren);
        }
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private static String sanitizeTypeName(String value) {
        String identifier = sanitizeIdentifier(value);
        if (identifier.isBlank()) {
            return "";
        }
        return Character.toUpperCase(identifier.charAt(0)) + identifier.substring(1);
    }

    private static String sanitizeIdentifier(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (sanitized.isBlank()) {
            return "";
        }
        if (!Character.isJavaIdentifierStart(sanitized.charAt(0))) {
            sanitized = "x_" + sanitized;
        }
        return sanitized;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    record SourceCase(String id,
                      String repositoryName,
                      String originalPath,
                      String sourcePath,
                      String source,
                      String query,
                      String goldName) {
    }
}