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
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 编排任务共享的确定性上下文版本账本。
 *
 * <p>账本以项目相对路径作为逻辑资源键，因此父 Registry 和隔离 worktree fork 可以共享
 * 同一份版本事实。隔离工作区中的本地写入只更新当前 scope 的观察；只有 PatchSet 成功应用
 * 到项目根后才发布新的全局 generation。版本号用于快速失效，内容指纹和 Java AST 用于最终判定。
 */
public final class ContextVersionLedger {
    private final Map<String, Map<String, Observation>> observedByScope = new ConcurrentHashMap<>();
    private final Map<String, Map<String, CodeObservation>> codeEvidenceByScope = new ConcurrentHashMap<>();
    private final Map<String, ResourceVersion> currentByResource = new ConcurrentHashMap<>();
    private final Map<String, String> lastWriterByResource = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> pendingRefreshByScope = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    public void recordRead(String scope, String resourceKey, Path authoritativePath, String content) {
        if (inactive(scope) || invalid(resourceKey)) return;
        Snapshot snapshot = snapshot(authoritativePath, content);
        ResourceVersion current = currentByResource.computeIfAbsent(resourceKey,
                ignored -> readVersion(authoritativePath, generation.get(), false));
        observedByScope.computeIfAbsent(scope, ignored -> new ConcurrentHashMap<>())
                .compute(resourceKey, (key, previous) -> previous != null && previous.localModified()
                        ? previous : new Observation(snapshot, current.generation(), false));
        clearCodeEvidence(scope, resourceKey);
        Set<String> pending = pendingRefreshByScope.get(scope);
        if (pending != null) pending.remove(resourceKey);
    }

    /** 分页读取只登记整文件流式哈希，避免把局部页面误作完整文件基线。 */
    public void recordReadFile(String scope, String resourceKey, Path authoritativePath) {
        if (inactive(scope) || invalid(resourceKey)) return;
        Snapshot snapshot = snapshotFile(authoritativePath);
        ResourceVersion current = currentByResource.computeIfAbsent(resourceKey,
                ignored -> version(snapshot, authoritativePath, generation.get(), false));
        observedByScope.computeIfAbsent(scope, ignored -> new ConcurrentHashMap<>())
                .compute(resourceKey, (key, previous) -> previous != null && previous.localModified()
                        ? previous : new Observation(snapshot, current.generation(), false));
        clearCodeEvidence(scope, resourceKey);
        Set<String> pending = pendingRefreshByScope.get(scope);
        if (pending != null) pending.remove(resourceKey);
    }

    public void recordCodeEvidence(String scope, String resourceKey, Path authoritativePath,
                                   String chunkType, String symbolName,
                                   String symbolVersion, String sourceContent) {
        if (inactive(scope) || invalid(resourceKey) || chunkType == null || symbolName == null) return;
        String type = chunkType.trim().toLowerCase();
        if (!(type.equals("class") || type.equals("method") || type.equals("file"))) return;
        if (type.equals("file") && isSegmentedFileChunk(symbolName)) return;
        String symbolKey = symbolKey(type, symbolName);
        codeEvidenceByScope.computeIfAbsent(scope, ignored -> new ConcurrentHashMap<>())
                .put(resourceKey + "\0" + symbolKey,
                        new CodeObservation(resourceKey, symbolKey, type,
                                symbolVersion == null ? "" : symbolVersion,
                                sourceContent == null ? "" : fingerprint(sourceContent)));
        currentByResource.computeIfAbsent(resourceKey,
                ignored -> readVersion(authoritativePath, generation.get(), false));
    }

    /** 隔离 worktree 写入尚未提交，只刷新当前 Worker 的本地观察。 */
    public void recordLocalWrite(String scope, String resourceKey, Path path,
                                 String beforeContent, String content) {
        if (inactive(scope) || invalid(resourceKey)) return;
        Snapshot snapshot = snapshot(path, content);
        Snapshot baseline = snapshot(path, beforeContent);
        Observation previous = observedByScope.computeIfAbsent(scope,
                ignored -> new ConcurrentHashMap<>()).get(resourceKey);
        long observedGeneration = previous == null
                ? currentByResource.getOrDefault(resourceKey,
                version(baseline, path, generation.get(), false)).generation()
                : previous.generation();
        observedByScope.computeIfAbsent(scope, ignored -> new ConcurrentHashMap<>())
                .put(resourceKey, new Observation(
                        previous == null ? baseline : previous.snapshot(), observedGeneration, true));
        clearCodeEvidence(scope, resourceKey);
    }

    /** 父项目直接写入或 PatchSet 成功应用后发布新的全局资源版本。 */
    public void publishWrite(String scope, String resourceKey, Path authoritativePath, String content) {
        if (invalid(resourceKey)) return;
        long next = generation.incrementAndGet();
        Snapshot snapshot = snapshot(authoritativePath, content);
        currentByResource.put(resourceKey, version(snapshot, authoritativePath, next, false));
        if (!inactive(scope)) {
            lastWriterByResource.put(resourceKey, scope);
            observedByScope.computeIfAbsent(scope, ignored -> new ConcurrentHashMap<>())
                    .put(resourceKey, new Observation(snapshot, next, false));
            clearCodeEvidence(scope, resourceKey);
        }
    }

    public void markScopeDirty(String scope) {
        if (inactive(scope)) return;
        Map<String, Observation> observed = observedByScope.get(scope);
        if (observed != null) observed.keySet().forEach(this::markDirty);
        Map<String, CodeObservation> evidence = codeEvidenceByScope.get(scope);
        if (evidence != null) evidence.values().forEach(value -> markDirty(value.resourceKey()));
    }

    public void markDirty(String resourceKey) {
        if (invalid(resourceKey)) return;
        currentByResource.computeIfPresent(resourceKey, (key, value) -> {
            if (!value.dirty()) {
                generation.incrementAndGet();
            }
            return value.withDirty(true);
        });
    }

    /** 当前项目上下文的单调逻辑版本；只用于新鲜度判定，不使用墙钟时间。 */
    public long currentGeneration() {
        return generation.get();
    }

    /** 写闸门只做哈希、generation 和 AST 比较，不引入模型判断。 */
    public WriteGateResult validateWrite(String scope, String writeResourceKey,
                                         Path writePath, String currentContent,
                                         Path authoritativeRoot) {
        return validateWrite(scope, writeResourceKey, writePath, currentContent,
                authoritativeRoot, false);
    }

    public WriteGateResult validateWrite(String scope, String writeResourceKey,
                                         Path writePath, String currentContent,
                                         Path authoritativeRoot,
                                         boolean currentContentAuthoritative) {
        return validateWrite(scope, writeResourceKey, writePath, currentContent,
                authoritativeRoot, currentContentAuthoritative, Set.of());
    }

    private WriteGateResult validateWrite(String scope, String writeResourceKey,
                                          Path writePath, String currentContent,
                                          Path authoritativeRoot,
                                          boolean currentContentAuthoritative,
                                          Set<String> ignoredResources) {
        if (inactive(scope)) return WriteGateResult.allowed();
        LinkedHashSet<String> affected = new LinkedHashSet<>();
        LinkedHashSet<String> resources = new LinkedHashSet<>();
        String changedBy = "";

        Map<String, Observation> observed = observedByScope.get(scope);
        if (observed != null) {
            for (Map.Entry<String, Observation> entry : observed.entrySet()) {
                String resourceKey = entry.getKey();
                if (ignoredResources.contains(resourceKey)) continue;
                ResourceVersion current = currentContentAuthoritative
                        && resourceKey.equals(writeResourceKey)
                        ? version(snapshot(writePath, currentContent), writePath,
                        entry.getValue().generation(), false)
                        : current(resourceKey, authoritativeRoot, false);
                Snapshot baseline = entry.getValue().snapshot();
                if (baseline.fingerprint().equals(current.snapshot().fingerprint())) continue;
                resources.add(resourceKey);
                List<String> changed = changedSymbols(baseline.symbols(), current.snapshot().symbols());
                affected.addAll(changed.isEmpty() ? List.of("file:" + resourceKey) : changed);
                String writer = lastWriterByResource.get(resourceKey);
                if (changedBy.isBlank() && writer != null && !writer.equals(scope)) changedBy = writer;
            }
        }

        Map<String, CodeObservation> evidence = codeEvidenceByScope.get(scope);
        if (evidence != null) {
            for (CodeObservation observation : evidence.values()) {
                if (ignoredResources.contains(observation.resourceKey())) continue;
                ResourceVersion current = current(observation.resourceKey(), authoritativeRoot, false);
                String currentSource = observation.chunkType().equals("file")
                        ? current.snapshot().content()
                        : current.snapshot().symbolContents().get(observation.symbolKey());
                boolean missing = currentSource == null;
                boolean changed = !missing && !observation.sourceFingerprint().isBlank()
                        && !observation.sourceFingerprint().equals(fingerprint(currentSource));
                if (!missing && !changed) continue;
                resources.add(observation.resourceKey());
                affected.add(observation.symbolKey()
                        + (observation.symbolVersion().isBlank() ? ""
                        : " (symbolVersion=" + observation.symbolVersion() + ")")
                        + (missing ? " (deleted)" : ""));
                String writer = lastWriterByResource.get(observation.resourceKey());
                if (changedBy.isBlank() && writer != null && !writer.equals(scope)) changedBy = writer;
            }
        }

        if (affected.isEmpty()) return WriteGateResult.allowed();
        pendingRefreshByScope.computeIfAbsent(scope, ignored -> ConcurrentHashMap.newKeySet())
                .addAll(resources);
        String owner = changedBy.isBlank() ? "其他流程" : changedBy;
        String reason = "过期写入被拦截: 上下文已过期，依赖资源 " + String.join(", ", resources)
                + " 中的 " + String.join(", ", affected) + " 已被 " + owner
                + " 修改，写入 " + writePath.getFileName()
                + " 前必须基于刷新后的内容重新生成修改。";
        return WriteGateResult.stale(reason, List.copyOf(affected), changedBy, List.copyOf(resources));
    }

    public WriteGateResult validatePatchSet(String scope, PatchSet patchSet, Path projectRoot) {
        return preparePatchSet(scope, patchSet, projectRoot).writeGate();
    }

    public PatchPreparation preparePatchSet(String scope, PatchSet patchSet, Path projectRoot) {
        markScopeDirty(scope);
        AstRebase astRebase = rebaseNonOverlappingJavaChanges(scope, patchSet, projectRoot);
        for (PatchSet.FileChange change : astRebase.patchSet().changes()) {
            Path target = projectRoot.resolve(change.relativePath()).normalize();
            WriteGateResult result = validateWrite(scope, normalizeKey(change.relativePath()),
                    target, readContent(target), projectRoot, false, astRebase.mergedResources());
            if (!result.isAllowed()) return new PatchPreparation(result, patchSet);
        }
        PatchSet effective = rebaseRefreshedChanges(scope, astRebase.patchSet(), projectRoot);
        return new PatchPreparation(WriteGateResult.allowed(), effective);
    }

    private AstRebase rebaseNonOverlappingJavaChanges(String scope, PatchSet patchSet, Path projectRoot) {
        Map<String, Observation> observations = observedByScope.get(scope);
        if (observations == null || observations.isEmpty()) {
            return new AstRebase(patchSet, Set.of());
        }
        List<PatchSet.FileChange> changes = new ArrayList<>();
        Set<String> mergedResources = new LinkedHashSet<>();
        for (PatchSet.FileChange change : patchSet.changes()) {
            String resourceKey = normalizeKey(change.relativePath());
            Observation observation = observations.get(resourceKey);
            Path target = projectRoot.resolve(change.relativePath()).normalize();
            String currentContent = readContent(target);
            if (change.type() != PatchSet.ChangeType.MODIFY
                    || observation == null || observation.snapshot().content() == null
                    || currentContent == null
                    || observation.snapshot().fingerprint().equals(fingerprint(currentContent))) {
                changes.add(change);
                continue;
            }
            String proposed = new String(change.content(), StandardCharsets.UTF_8);
            JavaAstPatchMerger.MergeResult merge = JavaAstPatchMerger.merge(
                    target, observation.snapshot().content(), proposed, currentContent);
            if (!merge.merged()) {
                changes.add(change);
                continue;
            }
            byte[] mergedContent = merge.content().getBytes(StandardCharsets.UTF_8);
            String currentHash;
            try {
                currentHash = PatchSet.hash(target);
            } catch (IOException e) {
                changes.add(change);
                continue;
            }
            changes.add(new PatchSet.FileChange(
                    change.relativePath(), change.type(), currentHash,
                    PatchSet.hash(mergedContent), mergedContent, change.executable()));
            mergedResources.add(resourceKey);
        }
        return new AstRebase(new PatchSet(changes), Set.copyOf(mergedResources));
    }

    /**
     * Worker 在刷新后重新写同一文件时，以刷新时确认的项目版本重绑定 PatchSet 基线。
     * 未刷新或未由该 scope 重写的文件保持原 beforeHash，继续由 PatchSet 冲突检查保护。
     */
    public PatchSet rebaseRefreshedChanges(String scope, PatchSet patchSet, Path projectRoot) {
        Map<String, Observation> observations = observedByScope.get(scope);
        if (observations == null || observations.isEmpty()) return patchSet;
        List<PatchSet.FileChange> changes = new ArrayList<>();
        for (PatchSet.FileChange change : patchSet.changes()) {
            String resourceKey = normalizeKey(change.relativePath());
            Observation observation = observations.get(resourceKey);
            if (observation == null || !observation.localModified()) {
                changes.add(change);
                continue;
            }
            Path target = projectRoot.resolve(change.relativePath()).normalize();
            String currentHash;
            try {
                currentHash = Files.isRegularFile(target)
                        ? PatchSet.hash(target) : PatchSet.MISSING_HASH;
            } catch (IOException e) {
                changes.add(change);
                continue;
            }
            changes.add(new PatchSet.FileChange(change.relativePath(), change.type(),
                    currentHash, change.afterHash(), change.content(), change.executable()));
        }
        return new PatchSet(changes);
    }

    public void publishPatchSet(String scope, PatchSet patchSet, Path projectRoot) {
        for (PatchSet.FileChange change : patchSet.changes()) {
            Path target = projectRoot.resolve(change.relativePath()).normalize();
            publishWrite(scope, normalizeKey(change.relativePath()), target, readContent(target));
        }
    }

    /** 自动刷新账本观察并返回最新内容；模型仍需基于这些内容重新生成修改。 */
    public Map<String, String> refreshPending(String scope, Path authoritativeRoot) {
        Set<String> pending = pendingRefreshByScope.remove(scope);
        if (pending == null || pending.isEmpty()) return Map.of();
        Map<String, String> refreshed = new LinkedHashMap<>();
        for (String resourceKey : pending) {
            Path path = authoritativeRoot.resolve(resourceKey).normalize();
            String content = readContent(path);
            ResourceVersion current = readVersion(path, generation.get(), false);
            currentByResource.put(resourceKey, current);
            observedByScope.computeIfAbsent(scope, ignored -> new ConcurrentHashMap<>())
                    .put(resourceKey, new Observation(current.snapshot(), current.generation(), false));
            clearCodeEvidence(scope, resourceKey);
            refreshed.put(resourceKey, content == null ? "<deleted>" : content);
        }
        return Map.copyOf(refreshed);
    }

    private ResourceVersion current(String resourceKey, Path authoritativeRoot, boolean force) {
        Path path = authoritativeRoot.resolve(resourceKey).normalize();
        ResourceVersion cached = currentByResource.get(resourceKey);
        if (!force && cached != null && !cached.dirty() && sameStamp(cached, path)) return cached;
        ResourceVersion refreshed = readVersion(path,
                cached == null ? generation.get() : cached.generation(), false);
        currentByResource.put(resourceKey, refreshed);
        return refreshed;
    }

    private ResourceVersion readVersion(Path path, long valueGeneration, boolean dirty) {
        return version(snapshot(path, readContent(path)), path, valueGeneration, dirty);
    }

    private static ResourceVersion version(Snapshot snapshot, Path path, long valueGeneration, boolean dirty) {
        try {
            BasicFileAttributes attrs = path != null && Files.exists(path)
                    ? Files.readAttributes(path, BasicFileAttributes.class) : null;
            return new ResourceVersion(snapshot, valueGeneration,
                    attrs == null ? -1L : attrs.size(),
                    attrs == null ? -1L : attrs.lastModifiedTime().toMillis(), dirty);
        } catch (IOException e) {
            return new ResourceVersion(snapshot, valueGeneration, -1L, -1L, dirty);
        }
    }

    private static boolean sameStamp(ResourceVersion cached, Path path) {
        try {
            if (!Files.exists(path)) return cached.size() < 0;
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return cached.size() == attrs.size()
                    && cached.modifiedMillis() == attrs.lastModifiedTime().toMillis();
        } catch (IOException e) {
            return false;
        }
    }

    private Snapshot snapshot(Path path, String content) {
        ParsedSymbols parsed = parseJavaSymbols(path, content);
        return new Snapshot(fingerprint(content), content, parsed.fingerprints(), parsed.contents());
    }

    private static Snapshot snapshotFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return new Snapshot("<absent>", null, Map.of(), Map.of());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8_192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            String fingerprint = HexFormat.of().formatHex(hash, 0, Math.min(16, hash.length));
            return new Snapshot(fingerprint, null, Map.of(), Map.of());
        } catch (IOException | NoSuchAlgorithmException e) {
            return new Snapshot("<unreadable>", null, Map.of(), Map.of());
        }
    }

    private ParsedSymbols parseJavaSymbols(Path path, String content) {
        if (content == null || path == null || !path.toString().toLowerCase().endsWith(".java")) {
            return ParsedSymbols.empty();
        }
        try {
            var parsed = parser.parse(content);
            if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) return ParsedSymbols.empty();
            CompilationUnit unit = parsed.getResult().get();
            Map<String, String> fingerprints = new LinkedHashMap<>();
            Map<String, String> contents = new LinkedHashMap<>();
            for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                String className = type.getNameAsString();
                int start = type.getBegin().map(value -> value.line).orElse(0);
                int end = type.getEnd().map(value -> value.line).orElse(0);
                putSymbol(fingerprints, contents, symbolKey("class", className),
                        extractLines(content, start, Math.min(start + 5, end)));
                for (MethodDeclaration method : type.getMethods()) {
                    String name = className + "." + method.getDeclarationAsString(false, false, false);
                    int methodStart = method.getBegin().map(value -> value.line).orElse(0);
                    int methodEnd = method.getEnd().map(value -> value.line).orElse(0);
                    String methodContent = extractLines(content, methodStart, methodEnd);
                    putSymbol(fingerprints, contents, symbolKey("method", name), methodContent);
                    String parameters = method.getParameters().stream()
                            .map(parameter -> parameter.getType().asString())
                            .reduce((left, right) -> left + ", " + right).orElse("");
                    putSymbol(fingerprints, contents,
                            symbolKey("method", className + "." + method.getNameAsString()
                                    + "(" + parameters + ")"), methodContent);
                }
            }
            return new ParsedSymbols(fingerprints, contents);
        } catch (RuntimeException ignored) {
            return ParsedSymbols.empty();
        }
    }

    private static void putSymbol(Map<String, String> fingerprints, Map<String, String> contents,
                                  String symbolKey, String content) {
        fingerprints.put(symbolKey, fingerprint(content));
        contents.put(symbolKey, content);
    }

    private static String extractLines(String content, int startLine, int endLine) {
        String[] lines = content.split("\\r?\\n");
        StringBuilder result = new StringBuilder();
        for (int index = startLine - 1; index < Math.min(endLine, lines.length); index++) {
            if (index >= 0) result.append(lines[index]).append('\n');
        }
        return result.toString().trim();
    }

    private static List<String> changedSymbols(Map<String, String> before, Map<String, String> after) {
        List<String> changed = new ArrayList<>();
        for (String symbol : before.keySet()) {
            if (!after.containsKey(symbol)) changed.add(symbol + " (deleted)");
            else if (!before.get(symbol).equals(after.get(symbol))) changed.add(symbol);
        }
        for (String symbol : after.keySet()) {
            if (!before.containsKey(symbol)) changed.add(symbol + " (added)");
        }
        Collections.sort(changed);
        return changed;
    }

    private static boolean isSegmentedFileChunk(String name) {
        return name != null && name.matches(".*#\\d+$");
    }

    private static String symbolKey(String chunkType, String symbolName) {
        return chunkType + ":" + symbolName;
    }

    private static String normalizeKey(String resourceKey) {
        return resourceKey.replace('\\', '/');
    }

    private static String readContent(Path path) {
        try {
            return path != null && Files.isRegularFile(path) ? Files.readString(path) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String fingerprint(String content) {
        if (content == null) return "<absent>";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < Math.min(16, hash.length); index++) {
                result.append(String.format("%02x", hash[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(content.hashCode()) + ":" + content.length();
        }
    }

    private void clearCodeEvidence(String scope, String resourceKey) {
        Map<String, CodeObservation> evidence = codeEvidenceByScope.get(scope);
        if (evidence != null) evidence.entrySet().removeIf(entry ->
                entry.getValue().resourceKey().equals(resourceKey));
    }

    private static boolean inactive(String scope) {
        return scope == null || scope.isBlank();
    }

    private static boolean invalid(String resourceKey) {
        return resourceKey == null || resourceKey.isBlank();
    }

    public void forgetScope(String scope) {
        if (inactive(scope)) return;
        observedByScope.remove(scope);
        codeEvidenceByScope.remove(scope);
        pendingRefreshByScope.remove(scope);
        Set<String> referenced = new LinkedHashSet<>();
        observedByScope.values().forEach(values -> referenced.addAll(values.keySet()));
        codeEvidenceByScope.values().forEach(values -> values.values()
                .forEach(value -> referenced.add(value.resourceKey())));
        currentByResource.keySet().removeIf(key -> !referenced.contains(key));
        lastWriterByResource.keySet().removeIf(key -> !referenced.contains(key));
    }

    public void clear() {
        observedByScope.clear();
        codeEvidenceByScope.clear();
        currentByResource.clear();
        lastWriterByResource.clear();
        pendingRefreshByScope.clear();
    }

    int cachedResourceCount() {
        return currentByResource.size();
    }

    public record PatchPreparation(WriteGateResult writeGate, PatchSet patchSet) {
    }

    private record AstRebase(PatchSet patchSet, Set<String> mergedResources) {
    }

    private record Observation(Snapshot snapshot, long generation, boolean localModified) {
    }

    private record CodeObservation(String resourceKey, String symbolKey, String chunkType,
                                   String symbolVersion, String sourceFingerprint) {
    }

    private record Snapshot(String fingerprint, String content, Map<String, String> symbols,
                            Map<String, String> symbolContents) {
        private Snapshot {
            symbols = symbols == null ? Map.of() : Map.copyOf(symbols);
            symbolContents = symbolContents == null ? Map.of() : Map.copyOf(symbolContents);
        }
    }

    private record ResourceVersion(Snapshot snapshot, long generation, long size,
                                   long modifiedMillis, boolean dirty) {
        private ResourceVersion withDirty(boolean value) {
            return new ResourceVersion(snapshot, generation, size, modifiedMillis, value);
        }
    }

    private record ParsedSymbols(Map<String, String> fingerprints, Map<String, String> contents) {
        private ParsedSymbols {
            fingerprints = fingerprints == null ? Map.of() : Map.copyOf(fingerprints);
            contents = contents == null ? Map.of() : Map.copyOf(contents);
        }

        private static ParsedSymbols empty() {
            return new ParsedSymbols(Map.of(), Map.of());
        }
    }
}
