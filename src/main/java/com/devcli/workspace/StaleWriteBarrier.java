package com.devcli.workspace;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic write-path consistency gate.
 *
 * <p>Resource leases prevent concurrent writes. This barrier prevents a Worker from writing
 * from a view that became stale after it read a file. Java files additionally retain class and
 * method fingerprints. The first implementation deliberately uses conservative scope-level
 * invalidation instead of guessing a call graph: a changed observed symbol requires refresh
 * before the Worker writes again.
 */
public final class StaleWriteBarrier {
    private final Map<String, Map<String, Observation>> observedByScope = new ConcurrentHashMap<>();
    private final Map<String, Map<String, CodeObservation>> codeEvidenceByScope = new ConcurrentHashMap<>();
    private final Map<String, Snapshot> currentByPath = new ConcurrentHashMap<>();
    private final Map<String, String> lastWriterByPath = new ConcurrentHashMap<>();
    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    public void recordRead(String scope, Path path, String content) {
        if (isInactive(scope) || path == null) {
            return;
        }
        String normalizedPath = key(path);
        Snapshot snapshot = snapshot(path, content);
        observedByScope.computeIfAbsent(scope, ignored -> new ConcurrentHashMap<>())
                .put(normalizedPath, new Observation(snapshot));
        Map<String, CodeObservation> evidence = codeEvidenceByScope.get(scope);
        if (evidence != null) {
            evidence.entrySet().removeIf(entry -> entry.getValue().pathKey().equals(normalizedPath));
        }
        currentByPath.put(normalizedPath, snapshot);
    }

    /** 记录 search_code 返回的符号依赖；版本号用于诊断，内容指纹用于确定性校验。 */
    public void recordCodeEvidence(String scope, Path path, String chunkType,
                                   String symbolName, String symbolVersion, String sourceContent) {
        if (isInactive(scope) || path == null || chunkType == null || chunkType.isBlank()
                || symbolName == null || symbolName.isBlank()) {
            return;
        }
        String pathKey = key(path);
        String symbolKey = symbolKey(chunkType, symbolName);
        codeEvidenceByScope.computeIfAbsent(scope, ignored -> new ConcurrentHashMap<>())
                .put(pathKey + "\0" + symbolKey,
                        new CodeObservation(pathKey, symbolKey,
                                symbolVersion == null ? "" : symbolVersion,
                                sourceContent == null || sourceContent.isBlank()
                                        ? "" : fingerprint(sourceContent)));
        currentByPath.putIfAbsent(pathKey, snapshot(path, readCurrentContent(path)));
    }

    public void recordWrite(String scope, Path path, String content) {
        recordWrite(scope, path, null, content);
    }

    public void recordWrite(String scope, Path path, String before, String content) {
        if (isInactive(scope) || path == null) {
            return;
        }
        String normalizedPath = key(path);
        Snapshot snapshot = snapshot(path, content);
        lastWriterByPath.put(normalizedPath, scope);
        currentByPath.put(normalizedPath, snapshot);
        observedByScope.computeIfAbsent(scope, ignored -> new ConcurrentHashMap<>())
                .put(normalizedPath, new Observation(snapshot));
        Map<String, CodeObservation> evidence = codeEvidenceByScope.get(scope);
        if (evidence != null) {
            evidence.entrySet().removeIf(entry -> entry.getValue().pathKey().equals(normalizedPath));
        }
    }

    public String staleReason(String scope, Path path, String currentContent) {
        WriteGateResult result = validateWrite(scope, path, currentContent);
        return result.isAllowed() ? null : result.reason();
    }

    /** The write gate is deterministic: only content fingerprints and Java ASTs decide. */
    public WriteGateResult validateWrite(String scope, Path path, String currentContent) {
        if (isInactive(scope) || path == null) {
            return WriteGateResult.allowed();
        }
        Map<String, Observation> observed = observedByScope.get(scope);
        if (observed != null) {
            String targetKey = key(path);
            Observation observedTarget = observed.get(targetKey);
            if (observedTarget != null
                    && !observedTarget.snapshot().fingerprint().equals(fingerprint(currentContent))) {
                String writer = lastWriterByPath.get(targetKey);
                return WriteGateResult.stale(
                        staleFileReason(path, changedBy(scope, writer)),
                        List.of(path.getFileName().toString()),
                        writer == null ? "" : writer);
            }

            List<String> affectedSymbols = new ArrayList<>();
            String changedBy = "";
            for (Map.Entry<String, Observation> entry : observed.entrySet()) {
                String observedPath = entry.getKey();
                Snapshot current = refreshCurrentSnapshot(Path.of(observedPath));
                Map<String, String> baselineSymbols = entry.getValue().snapshot().symbols();
                if (baselineSymbols.isEmpty() || current.symbols().equals(baselineSymbols)) {
                    continue;
                }
                affectedSymbols.addAll(changedSymbols(baselineSymbols, current.symbols()));
                String writer = lastWriterByPath.get(observedPath);
                if (changedBy.isBlank() && writer != null && !writer.equals(scope)) {
                    changedBy = writer;
                }
            }
            if (!affectedSymbols.isEmpty()) {
                String owner = changedBy.isBlank() ? "其他流程" : changedBy;
                String reason = "上下文已过期: 依赖的 Java 符号 " + String.join(", ", affectedSymbols)
                        + " 已被 " + owner + " 修改，写入 " + path.getFileName()
                        + " 前请先用 read_file 重读相关文件并刷新上下文。";
                return WriteGateResult.stale(reason, affectedSymbols, changedBy);
            }
        }
        return validateCodeEvidence(scope, path);
    }

    private WriteGateResult validateCodeEvidence(String scope, Path writePath) {
        Map<String, CodeObservation> evidence = codeEvidenceByScope.get(scope);
        if (evidence == null || evidence.isEmpty()) {
            return WriteGateResult.allowed();
        }

        List<String> affectedSymbols = new ArrayList<>();
        String changedBy = "";
        for (CodeObservation observation : evidence.values()) {
            Path observedPath = Path.of(observation.pathKey());
            Snapshot current = refreshCurrentSnapshot(observedPath);
            String currentContent = current.symbolContents().get(observation.symbolKey());
            boolean missing = currentContent == null;
            boolean changed = !missing
                    && !observation.sourceFingerprint().isBlank()
                    && !observation.sourceFingerprint().equals(fingerprint(currentContent));
            if (!missing && !changed) {
                continue;
            }
            affectedSymbols.add(observation.symbolKey()
                    + (observation.symbolVersion().isBlank()
                    ? "" : " (symbolVersion=" + observation.symbolVersion() + ")")
                    + (missing ? " (deleted)" : ""));
            String writer = lastWriterByPath.get(observation.pathKey());
            if (changedBy.isBlank() && writer != null && !writer.equals(scope)) {
                changedBy = writer;
            }
        }
        if (affectedSymbols.isEmpty()) {
            return WriteGateResult.allowed();
        }

        String owner = changedBy.isBlank() ? "其他流程" : changedBy;
        String reason = "上下文已过期: 依赖的 Java 符号 " + String.join(", ", affectedSymbols)
                + " 已被 " + owner + " 修改，写入 " + writePath.getFileName()
                + " 前请先用 read_file 重读相关文件并刷新上下文。";
        return WriteGateResult.stale(reason, affectedSymbols, changedBy);
    }

    private Snapshot refreshCurrentSnapshot(Path path) {
        String normalizedPath = key(path);
        try {
            if (Files.exists(path) && Files.isRegularFile(path)) {
                Snapshot snapshot = snapshot(path, Files.readString(path));
                currentByPath.put(normalizedPath, snapshot);
                return snapshot;
            }
        } catch (IOException ignored) {
            // The direct target fingerprint remains the authoritative fallback.
        }
        return currentByPath.getOrDefault(normalizedPath, snapshot(path, null));
    }

    private static String readCurrentContent(Path path) {
        try {
            return Files.exists(path) && Files.isRegularFile(path)
                    ? Files.readString(path) : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private ParsedSymbols parseJavaSymbols(Path path, String content) {
        if (content == null || !path.toString().toLowerCase().endsWith(".java")) {
            return ParsedSymbols.empty();
        }
        try {
            var parsed = parser.parse(content);
            if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
                return ParsedSymbols.empty();
            }
            CompilationUnit unit = parsed.getResult().get();
            Map<String, String> fingerprints = new LinkedHashMap<>();
            Map<String, String> contents = new LinkedHashMap<>();
            for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                String className = type.getNameAsString();
                int classStart = type.getBegin().map(position -> position.line).orElse(0);
                int classEnd = type.getEnd().map(position -> position.line).orElse(0);
                String classContent = extractLines(content, classStart,
                        Math.min(classStart + 5, classEnd));
                String classKey = symbolKey("class", className);
                fingerprints.put(classKey, symbolFingerprint(path, classKey, classContent));
                contents.put(classKey, classContent);
                for (MethodDeclaration method : type.getMethods()) {
                    String name = className + "."
                            + method.getDeclarationAsString(false, false, false);
                    int methodStart = method.getBegin().map(position -> position.line).orElse(0);
                    int methodEnd = method.getEnd().map(position -> position.line).orElse(0);
                    String methodContent = extractLines(content, methodStart, methodEnd);
                    putSymbol(fingerprints, contents, path, symbolKey("method", name), methodContent);
                    String parameters = method.getParameters().stream()
                            .map(parameter -> parameter.getType().asString())
                            .reduce((left, right) -> left + ", " + right)
                            .orElse("");
                    String shortName = className + "." + method.getNameAsString()
                            + "(" + parameters + ")";
                    putSymbol(fingerprints, contents, path,
                            symbolKey("method", shortName), methodContent);
                }
            }
            return new ParsedSymbols(fingerprints, contents);
        } catch (RuntimeException ignored) {
            return ParsedSymbols.empty();
        }
    }

    private static void putSymbol(Map<String, String> fingerprints,
                                  Map<String, String> contents,
                                  Path path, String symbolKey, String content) {
        fingerprints.put(symbolKey, symbolFingerprint(path, symbolKey, content));
        contents.put(symbolKey, content);
    }

    private String extractLines(String content, int startLine, int endLine) {
        String[] lines = content.split("\\r?\\n");
        StringBuilder result = new StringBuilder();
        for (int index = startLine - 1; index < Math.min(endLine, lines.length); index++) {
            if (index >= 0) {
                result.append(lines[index]).append('\n');
            }
        }
        return result.toString().trim();
    }

    private Snapshot snapshot(Path path, String content) {
        ParsedSymbols parsed = parseJavaSymbols(path, content);
        return new Snapshot(fingerprint(content), parsed.fingerprints(), parsed.contents());
    }

    private static List<String> changedSymbols(Map<String, String> before, Map<String, String> after) {
        List<String> changed = new ArrayList<>();
        for (String symbol : before.keySet()) {
            if (!after.containsKey(symbol)) {
                changed.add(symbol + " (deleted)");
            } else if (!before.get(symbol).equals(after.get(symbol))) {
                changed.add(symbol);
            }
        }
        for (String symbol : after.keySet()) {
            if (!before.containsKey(symbol)) {
                changed.add(symbol + " (added)");
            }
        }
        Collections.sort(changed);
        return changed;
    }

    private static String staleFileReason(Path path, String changedBy) {
        return "过期写入被拦截: " + path.getFileName() + " —— " + changedBy
                + "，而当前写入基于读取时的旧版本，直接写回会覆盖对方改动。"
                + "请先用 read_file 重读 " + path + " 再基于最新内容重写。";
    }

    private static String changedBy(String scope, String writer) {
        return writer == null || writer.equals(scope)
                ? "该文件已被本流程外的改动修改"
                : "该文件已被 " + writer + " 修改";
    }

    private static boolean isInactive(String scope) {
        return scope == null || scope.isBlank();
    }

    private static String key(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static String symbolKey(String chunkType, String symbolName) {
        return chunkType + ":" + symbolName;
    }

    private static String symbolFingerprint(Path path, String symbol, String content) {
        return fingerprint(path + "\0" + symbol + "\0" + content);
    }

    private static String fingerprint(String content) {
        if (content == null) {
            return "<absent>";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < Math.min(16, hash.length); i++) {
                result.append(String.format("%02x", hash[i]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(content.hashCode()) + ":" + content.length();
        }
    }

    private record Observation(Snapshot snapshot) {
    }

    private record CodeObservation(String pathKey, String symbolKey,
                                   String symbolVersion, String sourceFingerprint) {
    }

    private record Snapshot(String fingerprint, Map<String, String> symbols,
                            Map<String, String> symbolContents) {
        private Snapshot {
            symbols = symbols == null ? Map.of() : Map.copyOf(symbols);
            symbolContents = symbolContents == null ? Map.of() : Map.copyOf(symbolContents);
        }
    }

    private record ParsedSymbols(Map<String, String> fingerprints,
                                 Map<String, String> contents) {
        private ParsedSymbols {
            fingerprints = fingerprints == null ? Map.of() : Map.copyOf(fingerprints);
            contents = contents == null ? Map.of() : Map.copyOf(contents);
        }

        private static ParsedSymbols empty() {
            return new ParsedSymbols(Map.of(), Map.of());
        }
    }

    public void forgetScope(String scope) {
        if (scope != null && !scope.isBlank()) {
            observedByScope.remove(scope);
            codeEvidenceByScope.remove(scope);
        }
    }

    public void clear() {
        observedByScope.clear();
        codeEvidenceByScope.clear();
        currentByPath.clear();
        lastWriterByPath.clear();
    }
}
