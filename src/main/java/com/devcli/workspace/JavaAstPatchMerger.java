package com.devcli.workspace;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 对同一 Java 文件执行保守的 AST 三方归并。
 *
 * <p>只归并既有方法体的互不重叠修改。导入、字段、构造器、类型声明、方法签名以及
 * 方法增删都属于结构变化，必须回到文件级冲突处理。
 */
final class JavaAstPatchMerger {
    private static final ParserConfiguration PARSER_CONFIGURATION = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

    private JavaAstPatchMerger() {
    }

    static MergeResult merge(Path path, String baseline, String proposed, String current) {
        if (path == null || !path.toString().toLowerCase().endsWith(".java")
                || baseline == null || proposed == null || current == null) {
            return MergeResult.notMergeable();
        }
        Optional<JavaSourceModel> baselineModel = parse(baseline);
        Optional<JavaSourceModel> proposedModel = parse(proposed);
        Optional<JavaSourceModel> currentModel = parse(current);
        if (baselineModel.isEmpty() || proposedModel.isEmpty() || currentModel.isEmpty()) {
            return MergeResult.notMergeable();
        }

        JavaSourceModel base = baselineModel.get();
        JavaSourceModel worker = proposedModel.get();
        JavaSourceModel authoritative = currentModel.get();
        if (!base.structuralForm().equals(worker.structuralForm())
                || !base.structuralForm().equals(authoritative.structuralForm())
                || !base.methods().keySet().equals(worker.methods().keySet())
                || !base.methods().keySet().equals(authoritative.methods().keySet())) {
            return MergeResult.notMergeable();
        }

        Set<String> workerChanges = changedMethods(base, worker);
        Set<String> concurrentChanges = changedMethods(base, authoritative);
        Set<String> overlap = new LinkedHashSet<>(workerChanges);
        overlap.retainAll(concurrentChanges);
        if (!overlap.isEmpty()) {
            return MergeResult.notMergeable();
        }
        if (workerChanges.isEmpty()) {
            return MergeResult.merged(current);
        }

        List<Replacement> replacements = new ArrayList<>();
        for (String method : workerChanges) {
            MethodBody workerBody = worker.methods().get(method);
            MethodBody currentBody = authoritative.methods().get(method);
            replacements.add(new Replacement(
                    currentBody.startOffset(), currentBody.endOffset(),
                    adaptLineEndings(workerBody.source(), current)));
        }
        replacements.sort(Comparator.comparingInt(Replacement::startOffset).reversed());
        StringBuilder merged = new StringBuilder(current);
        for (Replacement replacement : replacements) {
            merged.replace(replacement.startOffset(), replacement.endOffset(), replacement.source());
        }

        Optional<JavaSourceModel> verifiedModel = parse(merged.toString());
        if (verifiedModel.isEmpty()
                || !authoritative.structuralForm().equals(verifiedModel.get().structuralForm())) {
            return MergeResult.notMergeable();
        }
        JavaSourceModel verified = verifiedModel.get();
        for (String method : workerChanges) {
            if (!worker.methods().get(method).astForm().equals(verified.methods().get(method).astForm())) {
                return MergeResult.notMergeable();
            }
        }
        for (String method : concurrentChanges) {
            if (!authoritative.methods().get(method).astForm().equals(verified.methods().get(method).astForm())) {
                return MergeResult.notMergeable();
            }
        }
        return MergeResult.merged(merged.toString());
    }

    private static Optional<JavaSourceModel> parse(String source) {
        try {
            JavaParser parser = new JavaParser(PARSER_CONFIGURATION);
            var result = parser.parse(source);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                return Optional.empty();
            }
            CompilationUnit unit = result.getResult().get();
            Map<String, MethodBody> methods = new LinkedHashMap<>();
            for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
                if (!(method.getParentNode().orElse(null) instanceof TypeDeclaration<?> owner)
                        || method.getBody().isEmpty() || method.getBody().get().getRange().isEmpty()) {
                    continue;
                }
                String key = methodKey(owner, method);
                Range range = method.getBody().get().getRange().orElseThrow();
                int start = offset(source, range.begin);
                int end = offset(source, range.end) + 1;
                if (start < 0 || end <= start || end > source.length() || methods.containsKey(key)) {
                    return Optional.empty();
                }
                methods.put(key, new MethodBody(
                        method.getBody().get().toString(), source.substring(start, end), start, end));
            }

            CompilationUnit skeleton = unit.clone();
            for (MethodDeclaration method : skeleton.findAll(MethodDeclaration.class)) {
                if (method.getParentNode().orElse(null) instanceof TypeDeclaration<?> && method.getBody().isPresent()) {
                    method.setBody(new BlockStmt());
                }
            }
            return Optional.of(new JavaSourceModel(skeleton.toString(), Map.copyOf(methods)));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Set<String> changedMethods(JavaSourceModel baseline, JavaSourceModel candidate) {
        Set<String> changed = new LinkedHashSet<>();
        for (Map.Entry<String, MethodBody> entry : baseline.methods().entrySet()) {
            MethodBody other = candidate.methods().get(entry.getKey());
            if (other == null || !entry.getValue().astForm().equals(other.astForm())) {
                changed.add(entry.getKey());
            }
        }
        return changed;
    }

    private static String methodKey(TypeDeclaration<?> owner, MethodDeclaration method) {
        List<String> owners = new ArrayList<>();
        Node current = owner;
        while (current instanceof TypeDeclaration<?> type) {
            owners.add(type.getNameAsString());
            current = type.getParentNode().orElse(null);
        }
        Collections.reverse(owners);
        return "method:" + String.join(".", owners) + "." + method.getSignature().asString();
    }

    private static int offset(String source, Position position) {
        int line = 1;
        int index = 0;
        while (line < position.line && index < source.length()) {
            if (source.charAt(index++) == '\n') {
                line++;
            }
        }
        return Math.min(source.length(), index + Math.max(0, position.column - 1));
    }

    private static String adaptLineEndings(String value, String target) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        return target.contains("\r\n") ? normalized.replace("\n", "\r\n") : normalized;
    }

    record MergeResult(boolean merged, String content) {
        private static MergeResult merged(String content) {
            return new MergeResult(true, content);
        }

        private static MergeResult notMergeable() {
            return new MergeResult(false, "");
        }
    }

    private record JavaSourceModel(String structuralForm, Map<String, MethodBody> methods) {
    }

    private record MethodBody(String astForm, String source, int startOffset, int endOffset) {
    }

    private record Replacement(int startOffset, int endOffset, String source) {
    }
}
