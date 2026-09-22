package com.devcli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 包依赖方向的机械检查。
 *
 * <p>只断言「必须长期成立、且当前已经成立」的边界，不做完整分层断言。写成断言的门槛是
 * 「当前为绿」——断言一条长期为红的规则只会让检查失去信号价值。
 *
 * <p>检查基于源码里的 {@code import com.devcli.X.} 语句，同一顶层包内的引用不计。
 * 使用完全限定名而不 import 的引用不在覆盖范围内——覆盖它需要解析语法树，收益不足以抵消成本。
 * {@link #scanActuallyCoversTheCodebase()} 负责守住扫描本身的有效性，避免路径或正则失效后
 * 其余断言静默空跑。
 */
class PackageBoundaryTest {

    private static final Pattern PACKAGE =
            Pattern.compile("^package\\s+com\\.devcli\\.([a-zA-Z0-9_]+)", Pattern.MULTILINE);
    private static final Pattern IMPORT =
            Pattern.compile("^import\\s+(?:static\\s+)?com\\.devcli\\.([a-zA-Z0-9_]+)\\.",
                    Pattern.MULTILINE);

    /** 基础包：不得依赖任何其他 {@code com.devcli} 顶层包。 */
    private static final Set<String> LEAF_PACKAGES =
            Set.of("browser", "concurrent", "config", "prompt", "util");

    /** 入口层：任何包都不得依赖它。 */
    private static final String ENTRY_LAYER = "cli";

    /** 指定包不得依赖的顶层包。键是「谁」，值是「不许依赖谁」。 */
    private static final Map<String, Set<String>> FORBIDDEN = new LinkedHashMap<>();

    static {
        // 模型客户端层不得依赖业务层：它只认识配置与运行时原语。
        // 取消原语已下沉到 concurrent 叶子包，因此这里可以禁止 runtime。
        FORBIDDEN.put("llm", Set.of(
                "agent", "tool", "memory", "rag", "mcp", "plan", "render", "hitl", "skill",
                "snapshot", "workspace", "session", "extension", "web", "hook", "trace",
                "image", "lsp", "policy", "runtime"));
        // 路径与命令策略必须留在底层，不能反向依赖使用它的业务模块。
        FORBIDDEN.put("policy", Set.of(
                "agent", "tool", "memory", "rag", "mcp", "plan", "render", "hitl", "skill",
                "snapshot", "workspace", "session", "extension", "web", "hook", "trace",
                "image", "lsp", "llm", "runtime", "context"));
        // 记忆不得依赖 Agent 循环；检索不得依赖记忆与 Agent 循环。
        FORBIDDEN.put("memory", Set.of("agent"));
        FORBIDDEN.put("rag", Set.of("agent", "memory"));
        // Agent 循环是运行时编排的被托管方：runtime 包装它，它不得反向依赖。
        // 事件模型已下沉到 event 叶子方向（见 docs/adr/0004），此前它只为事件类型依赖整个 runtime。
        FORBIDDEN.put("agent", Set.of("runtime"));
        // 审批闸门不得依赖渲染实现：渲染器展示审批请求，方向只能是 render → hitl。
        FORBIDDEN.put("hitl", Set.of("render"));
        // 渲染层只做展示，不得依赖业务模块。
        FORBIDDEN.put("render", Set.of(
                "agent", "tool", "memory", "rag", "mcp", "plan", "snapshot", "workspace",
                "session", "extension", "web", "hook", "lsp"));
    }

    /** 一次跨包引用：来自哪个顶层包、指向哪个顶层包、出现在哪个文件。 */
    private record Edge(String file, String source, String target) {
    }

    private static List<Edge> edges;

    @Test
    void scanActuallyCoversTheCodebase() {
        List<Edge> found = edges();
        Set<String> sources = new TreeSet<>();
        found.forEach(edge -> sources.add(edge.source()));

        assertTrue(found.size() >= 300,
                "跨包 import 只扫到 " + found.size() + " 条，扫描范围或正则很可能已失效");
        assertTrue(sources.size() >= 20,
                "只扫到 " + sources.size() + " 个顶层包（" + sources + "），扫描范围很可能已失效");
    }

    @Test
    void leafPackagesDoNotDependOnOtherPackages() {
        List<String> violations = violations(edge -> LEAF_PACKAGES.contains(edge.source())
                ? "基础包 " + edge.source() + " 不得依赖任何其他包"
                : null);

        assertTrue(violations.isEmpty(),
                "基础包出现了对外依赖（" + LEAF_PACKAGES + "）:\n" + String.join("\n", violations));
    }

    @Test
    void noPackageDependsOnEntryLayer() {
        List<String> violations = violations(edge -> ENTRY_LAYER.equals(edge.target())
                ? "不得依赖入口层 " + ENTRY_LAYER
                : null);

        assertTrue(violations.isEmpty(),
                "有包反向依赖了入口层 " + ENTRY_LAYER + ":\n" + String.join("\n", violations));
    }

    @Test
    void declaredForbiddenDirectionsAreRespected() {
        List<String> violations = violations(edge -> {
            Set<String> forbidden = FORBIDDEN.get(edge.source());
            return forbidden != null && forbidden.contains(edge.target())
                    ? edge.source() + " 不得依赖 " + edge.target()
                    : null;
        });

        assertTrue(violations.isEmpty(), "包依赖方向违规:\n" + String.join("\n", violations));
    }

    private static List<String> violations(Rule rule) {
        List<String> result = new ArrayList<>();
        for (Edge edge : edges()) {
            String reason = rule.check(edge);
            if (reason != null) {
                result.add("  " + edge.file() + "  [" + reason + "]");
            }
        }
        return result;
    }

    /** 遍历主源码收集跨包 import；同一文件内重复指向同一顶层包只记一条。 */
    private static synchronized List<Edge> edges() {
        if (edges != null) {
            return edges;
        }
        Path root = sourceRoot();
        List<Edge> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher pkg = PACKAGE.matcher(text);
                if (!pkg.find()) {
                    continue;
                }
                String source = pkg.group(1);
                String relative = root.relativize(file).toString().replace('\\', '/');
                Set<String> seen = new TreeSet<>();
                Matcher imports = IMPORT.matcher(text);
                while (imports.find()) {
                    String target = imports.group(1);
                    if (!target.equals(source) && seen.add(target)) {
                        found.add(new Edge(relative, source, target));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        edges = List.copyOf(found);
        return edges;
    }

    private static Path sourceRoot() {
        Path root = Path.of("src", "main", "java", "com", "devcli").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(root), "找不到主源码目录: " + root);
        return root;
    }

    @FunctionalInterface
    private interface Rule {
        /** @return 违规原因；{@code null} 表示允许 */
        String check(Edge edge);
    }
}
