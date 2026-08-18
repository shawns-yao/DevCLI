package com.devcli.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Plan 与 Multi-Agent 共用的依赖图调度和结构校验。
 */
public final class ExecutionGraph {

    public enum NodeState {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED
    }

    public record ValidationResult(boolean valid, List<String> errors) {
        public ValidationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    private ExecutionGraph() {
    }

    public static <T extends ExecutionNode> List<T> ready(
            List<T> nodes, Predicate<T> finalStep) {
        return ready(
                nodes,
                ExecutionNode::id,
                ExecutionNode::dependencies,
                node -> node.artifact().state(),
                finalStep);
    }

    public static ValidationResult validate(List<? extends ExecutionNode> nodes) {
        return validate(nodes, ExecutionNode::id, ExecutionNode::dependencies);
    }

    public static List<String> topologicalOrder(List<? extends ExecutionNode> nodes) {
        return topologicalOrder(nodes, ExecutionNode::id, ExecutionNode::dependencies);
    }

    public static <T> List<T> ready(List<T> nodes,
                                    Function<T, String> id,
                                    Function<T, List<String>> dependencies,
                                    Function<T, NodeState> state,
                                    Predicate<T> finalStep) {
        List<T> safeNodes = nodes == null ? List.of() : nodes;
        Map<String, NodeState> states = new HashMap<>();
        for (T node : safeNodes) {
            states.put(id.apply(node), state.apply(node));
        }

        List<T> normalReady = safeNodes.stream()
                .filter(node -> state.apply(node) == NodeState.PENDING)
                .filter(finalStep.negate())
                .filter(node -> safeDependencies(dependencies.apply(node)).stream()
                        .allMatch(dep -> states.get(dep) == NodeState.COMPLETED))
                .toList();
        if (!normalReady.isEmpty()) {
            return normalReady;
        }

        boolean normalRunning = safeNodes.stream()
                .filter(finalStep.negate())
                .anyMatch(node -> state.apply(node) == NodeState.RUNNING);
        if (normalRunning) {
            return List.of();
        }

        return safeNodes.stream()
                .filter(node -> state.apply(node) == NodeState.PENDING)
                .filter(finalStep)
                .filter(node -> safeDependencies(dependencies.apply(node)).stream()
                        .allMatch(dep -> {
                            NodeState dependencyState = states.get(dep);
                            return dependencyState == NodeState.COMPLETED
                                    || dependencyState == NodeState.FAILED;
                        }))
                .toList();
    }

    public static <T> ValidationResult validate(List<T> nodes,
                                                Function<T, String> id,
                                                Function<T, List<String>> dependencies) {
        List<T> safeNodes = nodes == null ? List.of() : nodes;
        Map<String, T> byId = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (T node : safeNodes) {
            String nodeId = id.apply(node);
            if (nodeId == null || nodeId.isBlank()) {
                errors.add("node id is blank");
                continue;
            }
            if (byId.putIfAbsent(nodeId, node) != null) {
                errors.add("duplicate node: " + nodeId);
            }
        }
        for (T node : safeNodes) {
            String nodeId = id.apply(node);
            for (String dependency : safeDependencies(dependencies.apply(node))) {
                if (!byId.containsKey(dependency)) {
                    errors.add("missing dependency: " + nodeId + " -> " + dependency);
                }
            }
        }
        if (errors.isEmpty() && hasCycle(byId, dependencies)) {
            errors.add("cycle detected");
        }
        return new ValidationResult(errors.isEmpty(), errors);
    }

    public static <T> List<String> topologicalOrder(List<T> nodes,
                                                    Function<T, String> id,
                                                    Function<T, List<String>> dependencies) {
        ValidationResult validation = validate(nodes, id, dependencies);
        if (!validation.valid()) {
            return List.of();
        }
        Map<String, T> byId = new LinkedHashMap<>();
        for (T node : nodes) {
            byId.put(id.apply(node), node);
        }
        List<String> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String nodeId : byId.keySet()) {
            appendTopological(nodeId, byId, dependencies, visited, order);
        }
        return List.copyOf(order);
    }

    private static <T> boolean hasCycle(Map<String, T> byId,
                                        Function<T, List<String>> dependencies) {
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String nodeId : byId.keySet()) {
            if (detectCycle(nodeId, byId, dependencies, visited, visiting)) {
                return true;
            }
        }
        return false;
    }

    private static <T> boolean detectCycle(String nodeId,
                                           Map<String, T> byId,
                                           Function<T, List<String>> dependencies,
                                           Set<String> visited,
                                           Set<String> visiting) {
        if (visiting.contains(nodeId)) {
            return true;
        }
        if (!visited.add(nodeId)) {
            return false;
        }
        visiting.add(nodeId);
        T node = byId.get(nodeId);
        for (String dependency : safeDependencies(dependencies.apply(node))) {
            if (detectCycle(dependency, byId, dependencies, visited, visiting)) {
                return true;
            }
        }
        visiting.remove(nodeId);
        return false;
    }

    private static <T> void appendTopological(String nodeId,
                                              Map<String, T> byId,
                                              Function<T, List<String>> dependencies,
                                              Set<String> visited,
                                              List<String> order) {
        if (!visited.add(nodeId)) {
            return;
        }
        T node = byId.get(nodeId);
        for (String dependency : safeDependencies(dependencies.apply(node))) {
            appendTopological(dependency, byId, dependencies, visited, order);
        }
        order.add(nodeId);
    }

    private static List<String> safeDependencies(List<String> dependencies) {
        return dependencies == null ? List.of() : dependencies;
    }
}
