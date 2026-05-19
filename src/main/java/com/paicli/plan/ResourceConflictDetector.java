package com.paicli.plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

public final class ResourceConflictDetector {
    private ResourceConflictDetector() {
    }

    public static <T> List<List<T>> splitConflictFree(List<T> items,
                                                      Function<T, String> idFn,
                                                      Function<T, String> descriptionFn,
                                                      Function<T, String> typeFn) {
        List<List<T>> waves = new ArrayList<>();
        List<ResourceAccess<T>> current = new ArrayList<>();
        for (T item : items) {
            ResourceAccess<T> access = ResourceAccess.from(
                    item,
                    safeApply(idFn, item),
                    safeApply(descriptionFn, item),
                    safeApply(typeFn, item));
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

    record ResourceAccess<T>(T item, String id, Set<String> resources, boolean writes, boolean exclusive) {
        static <T> ResourceAccess<T> from(T item, String id, String description, String type) {
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
            boolean exclusive = command || (write && resources.isEmpty());
            return new ResourceAccess<>(item, id, resources, write, exclusive);
        }

        private static Set<String> extractResources(String text) {
            Set<String> resources = new LinkedHashSet<>();
            if (text == null || text.isBlank()) {
                return resources;
            }
            String[] tokens = text.split("[\\s,，;；:：()（）\\[\\]{}<>\"']+");
            for (String token : tokens) {
                String cleaned = token.trim().replace("\\", "/");
                if (cleaned.contains("/") || cleaned.matches(".*\\.[A-Za-z0-9]{1,8}$")) {
                    resources.add(cleaned);
                }
            }
            return resources;
        }
    }
}
