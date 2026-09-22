package com.devcli.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 校验并应用摘要增量操作。整批操作失败时保留上一版摘要。 */
public class SummaryLifecycleReducer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Result apply(String previousSummary, String operationJson) {
        String previous = previousSummary == null ? "" : previousSummary;
        try {
            List<SummaryOperation> operations = parseOperations(operationJson);
            RollingSummary summary = RollingSummary.parse(previous);
            for (SummaryOperation operation : operations) {
                apply(summary, operation);
            }
            return new Result(true, summary.render(), "applied");
        } catch (RuntimeException e) {
            return new Result(false, previous, e.getMessage());
        }
    }

    private List<SummaryOperation> parseOperations(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("摘要操作为空");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("摘要操作不是 JSON 对象");
        }
        try {
            JsonNode root = MAPPER.readTree(raw.substring(start, end + 1));
            JsonNode nodes = root.get("operations");
            if (nodes == null || !nodes.isArray() || nodes.isEmpty()) {
                throw new IllegalArgumentException("缺少 operations");
            }
            List<SummaryOperation> operations = new ArrayList<>();
            for (JsonNode node : nodes) {
                SummaryOperation.Action action = SummaryOperation.Action.valueOf(required(node, "action")
                        .toUpperCase(Locale.ROOT));
                String section = required(node, "section");
                String subject = required(node, "subject");
                if (!RollingSummary.SECTIONS.contains(section)) {
                    throw new IllegalArgumentException("未知摘要分段: " + section);
                }
                String target = text(node, "target_section");
                if (!target.isBlank() && !RollingSummary.SECTIONS.contains(target)) {
                    throw new IllegalArgumentException("未知目标分段: " + target);
                }
                String content = text(node, "content");
                String targetId = text(node, "target_id");
                if (requiresContent(action) && content.isBlank()) {
                    throw new IllegalArgumentException(action + " 缺少 content");
                }
                String lifecycleText = text(node, "lifecycle");
                SummaryItem.Lifecycle lifecycle = lifecycleText.isBlank()
                        ? defaultLifecycle(action, target.isBlank() ? section : target)
                        : SummaryItem.Lifecycle.valueOf(lifecycleText.toUpperCase(Locale.ROOT));
                int importance = node.path("importance").isInt()
                        ? node.path("importance").asInt() : 70;
                List<String> refs = new ArrayList<>();
                JsonNode refNodes = node.get("evidence_refs");
                if (refNodes != null && refNodes.isArray()) {
                    refNodes.forEach(ref -> {
                        if (ref.isTextual() && !ref.asText().isBlank()) {
                            refs.add(ref.asText());
                        }
                    });
                }
                operations.add(new SummaryOperation(action, section, target, targetId, subject,
                        content, lifecycle, importance, List.copyOf(refs)));
            }
            return operations;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("摘要操作解析失败", e);
        }
    }

    private void apply(RollingSummary summary, SummaryOperation operation) {
        boolean resolvesIntoHistory = operation.action() == SummaryOperation.Action.RESOLVE
                && !operation.targetSection().isBlank()
                && !RollingSummary.isProjectionOnlySection(operation.targetSection());
        if (RollingSummary.isProjectionOnlySection(operation.section()) && !resolvesIntoHistory) {
            return;
        }
        Optional<SummaryItem> current = operation.targetId().isBlank()
                ? summary.findItem(operation.section(), operation.subject())
                : summary.allItems().stream()
                        .filter(item -> item.id().equals(operation.targetId()))
                        .findFirst();
        if (operation.action() != SummaryOperation.Action.ADD
                && !operation.targetId().isBlank() && current.isEmpty()) {
            throw new IllegalArgumentException("摘要 target_id 不存在: " + operation.targetId());
        }
        switch (operation.action()) {
            case ADD -> {
                SummaryItem added = newItem(operation, operation.section(),
                        current.map(item -> item.revision() + 1).orElse(1));
                current.filter(SummaryItem::isVisible)
                        .ifPresent(item -> summary.replaceItem(item, item.supersede(added.id())));
                summary.addItem(added);
            }
            case UPDATE -> {
                int revision = current.map(item -> item.revision() + 1).orElse(1);
                SummaryItem replacement = newItem(operation, operation.section(), revision);
                current.filter(SummaryItem::isVisible)
                        .ifPresent(item -> summary.replaceItem(item, item.supersede(replacement.id())));
                summary.addItem(replacement);
            }
            case RESOLVE -> {
                String target = operation.targetSection().isBlank()
                        ? operation.section() : operation.targetSection();
                int revision = current.map(item -> item.revision() + 1).orElse(1);
                SummaryItem resolved = new SummaryItem(
                        SummaryItem.create(target, operation.subject(), operation.content(),
                                SummaryItem.Lifecycle.RESOLVED, operation.importance(),
                                operation.evidenceRefs()).id(),
                        target, operation.subject(), operation.content(), SummaryItem.Lifecycle.RESOLVED,
                        operation.importance(), revision, 0, "", operation.evidenceRefs());
                current.ifPresent(item -> summary.replaceItem(item, item.supersede(resolved.id())));
                summary.addItem(resolved);
            }
            case SUPERSEDE -> current.ifPresent(item -> summary.replaceItem(item,
                    item.supersede(operation.content().isBlank() ? "external" : operation.content())));
            case EXPIRE -> current.ifPresent(item -> summary.replaceItem(item,
                    item.withLifecycle(SummaryItem.Lifecycle.EXPIRED)));
            case DELETE -> summary.removeItems(item -> item.section().equals(operation.section())
                    && item.subject().equals(operation.subject()));
        }
    }

    private SummaryItem newItem(SummaryOperation operation, String section, int revision) {
        SummaryItem item = SummaryItem.create(section, operation.subject(), operation.content(),
                operation.lifecycle(), operation.importance(), operation.evidenceRefs());
        return item.withRevision(revision);
    }

    private static boolean requiresContent(SummaryOperation.Action action) {
        return action == SummaryOperation.Action.ADD || action == SummaryOperation.Action.UPDATE
                || action == SummaryOperation.Action.RESOLVE;
    }

    private static SummaryItem.Lifecycle defaultLifecycle(SummaryOperation.Action action, String section) {
        return action == SummaryOperation.Action.RESOLVE
                ? SummaryItem.Lifecycle.RESOLVED : RollingSummary.defaultLifecycle(section);
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少 " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText().strip() : "";
    }

    public record Result(boolean applied, String summary, String reason) {
    }
}
