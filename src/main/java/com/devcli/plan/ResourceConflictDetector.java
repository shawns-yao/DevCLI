package com.devcli.plan;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class ResourceConflictDetector {
    private ResourceConflictDetector() {
    }

    public static <T> List<List<T>> splitConflictFree(List<T> items,
                                                      Function<T, String> idFn,
                                                      Function<T, String> descriptionFn,
                                                      Function<T, String> typeFn) {
        return splitConflictFree(items, idFn, descriptionFn, typeFn, null);
    }

    public static <T> List<List<T>> splitConflictFree(List<T> items,
                                                      Function<T, String> idFn,
                                                      Function<T, String> descriptionFn,
                                                      Function<T, String> typeFn,
                                                      Path projectRoot) {
        List<List<T>> waves = new ArrayList<>();
        List<ResourceAccess<T>> current = new ArrayList<>();
        for (T item : items) {
            ResourceAccess<T> access = ResourceAccess.from(
                    item,
                    safeApply(idFn, item),
                    safeApply(descriptionFn, item),
                    safeApply(typeFn, item),
                    projectRoot);
            if (current.stream().anyMatch(existing -> conflicts(existing, access))) {
                waves.add(current.stream().map(ResourceAccess::item).toList());
                current = new ArrayList<>();
            }
            current.add(access);
        }
        if (!current.isEmpty()) {
            waves.add(current.stream().map(ResourceAccess::item).toList());
        }
        return waves;
    }

    static boolean conflicts(ResourceAccess<?> left, ResourceAccess<?> right) {
        if (left.exclusive() || right.exclusive()) {
            return true;
        }
        for (String resource : left.resources()) {
            if (right.resources().contains(resource) && (left.writes() || right.writes())) {
                Set<String> leftMethods = left.javaMethodScopes().get(resource);
                Set<String> rightMethods = right.javaMethodScopes().get(resource);
                if (leftMethods != null && rightMethods != null
                        && !leftMethods.isEmpty() && !rightMethods.isEmpty()
                        && leftMethods.stream().noneMatch(rightMethods::contains)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static String safeApply(Function<?, String> fn, Object item) {
        @SuppressWarnings("unchecked")
        Function<Object, String> typed = (Function<Object, String>) fn;
        String value = typed.apply(item);
        return value == null ? "" : value;
    }

    record ResourceAccess<T>(T item, String id, Set<String> resources,
                             Map<String, Set<String>> javaMethodScopes,
                             boolean writes, boolean exclusive) {
        static <T> ResourceAccess<T> from(T item, String id, String description,
                                          String type, Path projectRoot) {
            String normalizedType = type == null ? "" : type.toUpperCase(Locale.ROOT);
            String text = description == null ? "" : description;
            String lower = text.toLowerCase(Locale.ROOT);
            boolean write = normalizedType.contains("WRITE")
                    || normalizedType.contains("FILEWRITE")
                    || lower.contains("写入")
                    || lower.contains("修改")
                    || lower.contains("删除")
                    || lower.contains("edit ")
                    || lower.contains("write ");
            boolean command = normalizedType.contains("COMMAND")
                    || lower.contains("执行命令")
                    || lower.contains("mvn ")
                    || lower.contains("gradle")
                    || lower.contains("npm ")
                    || lower.contains("pnpm ")
                    || lower.contains("yarn ");
            Set<String> resources = extractResources(text);
            Map<String, Set<String>> javaMethodScopes = extractJavaMethodScopes(
                    text, resources, projectRoot);
            boolean exclusive = command || (write && resources.isEmpty());
            return new ResourceAccess<>(item, id, resources, javaMethodScopes, write, exclusive);
        }

        private static Map<String, Set<String>> extractJavaMethodScopes(
                String text, Set<String> resources, Path projectRoot) {
            if (projectRoot == null || hasStructuralJavaIntent(text)) {
                return Map.of();
            }
            Map<String, Set<String>> scopes = new LinkedHashMap<>();
            for (String resource : resources) {
                if (!resource.toLowerCase(Locale.ROOT).endsWith(".java")) {
                    continue;
                }
                Path source = projectRoot.resolve(resource).normalize();
                if (!source.startsWith(projectRoot.normalize()) || !Files.isRegularFile(source)) {
                    continue;
                }
                Set<String> methods = mentionedMethods(source, text);
                if (methods.isEmpty()) {
                    continue;
                }
                scopes.put(resource, methods);
                int slash = resource.lastIndexOf('/');
                if (slash >= 0 && slash < resource.length() - 1) {
                    scopes.put(resource.substring(slash + 1), methods);
                }
            }
            return Map.copyOf(scopes);
        }

        private static Set<String> mentionedMethods(Path source, String text) {
            try {
                JavaParser parser = new JavaParser(new ParserConfiguration()
                        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
                var parsed = parser.parse(source);
                if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
                    return Set.of();
                }
                List<MethodDeclaration> candidates = parsed.getResult().get()
                        .findAll(MethodDeclaration.class).stream()
                        .filter(method -> method.getParentNode().orElse(null) instanceof TypeDeclaration<?>)
                        .toList();
                Set<String> exactMethods = new LinkedHashSet<>();
                for (MethodDeclaration method : candidates) {
                    TypeDeclaration<?> owner = (TypeDeclaration<?>) method.getParentNode().orElseThrow();
                    if (exactSignatureReference(text, owner.getNameAsString(),
                            method.getSignature().asString())) {
                        exactMethods.add(owner.getNameAsString() + "." + method.getSignature().asString());
                    }
                }
                if (!exactMethods.isEmpty()) {
                    return Set.copyOf(exactMethods);
                }
                Set<String> methods = new LinkedHashSet<>();
                for (MethodDeclaration method : candidates) {
                    TypeDeclaration<?> owner = (TypeDeclaration<?>) method.getParentNode().orElseThrow();
                    String ownerName = owner.getNameAsString();
                    String methodName = method.getNameAsString();
                    if (strongMethodReference(text, ownerName, methodName)) {
                        methods.add(ownerName + "." + method.getSignature().asString());
                    }
                }
                return Set.copyOf(methods);
            } catch (Exception ignored) {
                return Set.of();
            }
        }

        private static boolean exactSignatureReference(String text, String owner, String signature) {
            String compact = text == null ? "" : text.replaceAll("\\s+", "");
            String reference = "(?<![\\p{L}\\p{N}_])(?:" + Pattern.quote(owner)
                    + "[.#])?" + Pattern.quote(signature) + "(?![\\p{L}\\p{N}_])";
            return Pattern.compile(reference).matcher(compact).find();
        }

        private static boolean strongMethodReference(String text, String owner, String method) {
            String ownerMethod = "(?<![\\p{L}\\p{N}_])" + Pattern.quote(owner)
                    + "[.#]" + Pattern.quote(method) + "(?:\\s*\\(|(?![\\p{L}\\p{N}_]))";
            String directCall = "(?<![\\p{L}\\p{N}_.#])" + Pattern.quote(method) + "\\s*\\(";
            String namedMethod = "(?:`" + Pattern.quote(method) + "`|(?<![\\p{L}\\p{N}_])"
                    + Pattern.quote(method) + "(?![\\p{L}\\p{N}_]))\\s*(?:方法|method)";
            return Pattern.compile(ownerMethod).matcher(text).find()
                    || Pattern.compile(directCall).matcher(text).find()
                    || Pattern.compile(namedMethod, Pattern.CASE_INSENSITIVE).matcher(text).find();
        }

        private static boolean hasStructuralJavaIntent(String text) {
            String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
            return normalized.contains("import")
                    || normalized.contains("package ")
                    || normalized.contains("字段")
                    || normalized.contains("成员变量")
                    || normalized.contains("构造器")
                    || normalized.contains("构造方法")
                    || normalized.contains("类声明")
                    || normalized.contains("接口声明")
                    || normalized.contains("方法签名")
                    || normalized.contains("新增方法")
                    || normalized.contains("删除方法")
                    || normalized.contains("add method")
                    || normalized.contains("remove method")
                    || normalized.contains("method signature")
                    || normalized.contains("constructor")
                    || normalized.contains("field ")
                    || normalized.contains("class declaration")
                    || normalized.contains("interface declaration");
        }

        private static Set<String> extractResources(String text) {
            Set<String> resources = new LinkedHashSet<>();
            if (text == null || text.isBlank()) {
                return resources;
            }
            String[] tokens = text.split("[\\s,，;；:：()（）\\[\\]{}<>\"']+");
            for (String token : tokens) {
                String cleaned = normalizeResourceToken(token);
                if (cleaned.isEmpty()) {
                    continue;
                }
                if (looksLikeJavaMemberReference(cleaned)) {
                    continue;
                }
                if (cleaned.contains("/") || cleaned.matches(".*\\.[A-Za-z0-9]{1,8}$")) {
                    resources.add(cleaned);
                    int slash = cleaned.lastIndexOf('/');
                    if (slash >= 0 && slash < cleaned.length() - 1) {
                        resources.add(cleaned.substring(slash + 1));
                    }
                } else if (cleaned.matches("[A-Z][A-Za-z0-9_]*(Cli|CLI|Controller|Service|Repository|Util|Utils|Manager|Parser|Command|Handler|Application|Config)")) {
                    resources.add(cleaned + ".java");
                }
            }
            return resources;
        }

        private static boolean looksLikeJavaMemberReference(String token) {
            return !token.toLowerCase(Locale.ROOT).endsWith(".java")
                    && token.matches("[A-Z][A-Za-z0-9_]*[.#][a-z_$][A-Za-z0-9_$]*");
        }

        private static String normalizeResourceToken(String token) {
            if (token == null) {
                return "";
            }
            String cleaned = token.trim()
                    .replace("\\", "/")
                    .replaceAll("^[`./]+", "")
                    .replaceAll("[`。.!！?？]+$", "");
            while (cleaned.contains("//")) {
                cleaned = cleaned.replace("//", "/");
            }
            return cleaned;
        }
    }
}
