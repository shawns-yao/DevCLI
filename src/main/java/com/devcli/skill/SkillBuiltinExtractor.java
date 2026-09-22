package com.devcli.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** 将内置 Skill 分阶段解压、校验后替换到本地缓存。 */
public final class SkillBuiltinExtractor {
    public static final String CURRENT_VERSION = "1.0.0";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ConcurrentHashMap<Path, ReentrantLock> PROCESS_LOCKS = new ConcurrentHashMap<>();
    private static final List<BuiltinSkillSpec> BUILTIN_SKILLS = List.of(
            new BuiltinSkillSpec("web-access", List.of(
                    "SKILL.md",
                    "references/cdp-cheatsheet.md",
                    "references/site-patterns/github.com.md",
                    "references/site-patterns/juejin.cn.md",
                    "references/site-patterns/mp.weixin.qq.com.md",
                    "references/site-patterns/x.com.md",
                    "references/site-patterns/xiaohongshu.com.md",
                    "references/site-patterns/zhuanlan.zhihu.com.md")));

    private final Path cacheRoot;
    private final Path lockPath;
    private final ReentrantLock processLock;
    private final Consumer<SkillRegistry.Diagnostic> diagnosticSink;

    public SkillBuiltinExtractor(Path cacheRoot) {
        this(cacheRoot, ignored -> { });
    }

    public SkillBuiltinExtractor(Path cacheRoot, Consumer<SkillRegistry.Diagnostic> diagnosticSink) {
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        this.lockPath = this.cacheRoot.resolve(".builtin-extract.lock");
        this.processLock = PROCESS_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock(true));
        this.diagnosticSink = diagnosticSink == null ? ignored -> { } : diagnosticSink;
    }

    public Path cacheRoot() {
        return cacheRoot;
    }

    public List<String> builtinSkillNames() {
        return BUILTIN_SKILLS.stream().map(BuiltinSkillSpec::name).toList();
    }

    public Path skillCacheDir(String skillName) {
        return cacheRoot.resolve(skillName);
    }

    public void extractAll() throws IOException {
        processLock.lock();
        try {
            Files.createDirectories(cacheRoot);
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                for (BuiltinSkillSpec spec : BUILTIN_SKILLS) extract(spec);
            }
        } finally {
            processLock.unlock();
        }
    }

    private void extract(BuiltinSkillSpec spec) throws IOException {
        Path live = cacheRoot.resolve(spec.name());
        recoverBackup(spec.name(), live);
        Map<String, byte[]> resources = loadResources(spec);
        Map<String, String> expectedHashes = hashes(resources);
        if (isValidCache(live, expectedHashes)) return;

        Path stagingRoot = Files.createTempDirectory(cacheRoot, ".staging-");
        Path staged = stagingRoot.resolve(spec.name());
        try {
            Files.createDirectories(staged);
            for (Map.Entry<String, byte[]> resource : resources.entrySet()) {
                Path target = safeResolve(staged, resource.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, resource.getValue());
            }
            Files.writeString(staged.resolve(".version"), CURRENT_VERSION);
            writeManifest(staged.resolve(".manifest.json"), expectedHashes);
            verifyCache(staged, expectedHashes);
            replaceWithRollback(spec.name(), staged, live);
        } finally {
            deleteRecursive(stagingRoot);
        }
    }

    private Map<String, byte[]> loadResources(BuiltinSkillSpec spec) throws IOException {
        Map<String, byte[]> resources = new LinkedHashMap<>();
        for (String relative : spec.files()) {
            Path normalized = Path.of(relative).normalize();
            if (normalized.isAbsolute() || normalized.startsWith("..")) {
                throw new IOException("内置 Skill 清单路径越界: " + relative);
            }
            String resourcePath = "skills/" + spec.name() + "/" + relative.replace('\\', '/');
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (input == null) throw new IOException("内置 Skill 资源缺失: " + resourcePath);
                resources.put(relative.replace('\\', '/'), input.readAllBytes());
            }
        }
        return Map.copyOf(resources);
    }

    private boolean isValidCache(Path directory, Map<String, String> expectedHashes) {
        try {
            if (!Files.isDirectory(directory)
                    || !CURRENT_VERSION.equals(Files.readString(directory.resolve(".version")).trim())) {
                return false;
            }
            JsonNode manifest = JSON.readTree(Files.readString(directory.resolve(".manifest.json")));
            if (!CURRENT_VERSION.equals(manifest.path("version").asText())) return false;
            JsonNode files = manifest.path("files");
            for (Map.Entry<String, String> expected : expectedHashes.entrySet()) {
                if (!expected.getValue().equals(files.path(expected.getKey()).asText())) return false;
            }
            verifyCache(directory, expectedHashes);
            return true;
        } catch (Exception e) {
            diagnosticSink.accept(new SkillRegistry.Diagnostic(
                    "skill_builtin_cache_invalid", "内置 Skill 缓存需要重建: " + e.getMessage(),
                    directory.toString()));
            return false;
        }
    }

    private void verifyCache(Path directory, Map<String, String> expectedHashes) throws IOException {
        for (Map.Entry<String, String> expected : expectedHashes.entrySet()) {
            Path file = safeResolve(directory, expected.getKey());
            if (!Files.isRegularFile(file) || !expected.getValue().equals(sha256(Files.readAllBytes(file)))) {
                throw new IOException("内置 Skill 内容校验失败: " + expected.getKey());
            }
        }
    }

    private void writeManifest(Path target, Map<String, String> hashes) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", CURRENT_VERSION);
        ObjectNode files = root.putObject("files");
        hashes.forEach(files::put);
        Files.writeString(target, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    private void replaceWithRollback(String name, Path staged, Path live) throws IOException {
        Path backup = cacheRoot.resolve("." + name + ".backup-" + UUID.randomUUID());
        boolean oldMoved = false;
        try {
            if (Files.exists(live)) {
                move(live, backup);
                oldMoved = true;
            }
            move(staged, live);
            if (oldMoved) deleteRecursive(backup);
        } catch (IOException failure) {
            if (!Files.exists(live) && oldMoved && Files.exists(backup)) {
                move(backup, live);
            }
            throw failure;
        }
    }

    private void recoverBackup(String name, Path live) throws IOException {
        if (Files.exists(live)) return;
        try (var paths = Files.list(cacheRoot)) {
            Path backup = paths.filter(path -> path.getFileName().toString().startsWith("." + name + ".backup-"))
                    .sorted(Comparator.comparing(Path::toString).reversed())
                    .findFirst().orElse(null);
            if (backup != null) move(backup, live);
        }
    }

    private static Path safeResolve(Path root, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw new IOException("Skill 路径越界: " + relative);
        return target;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static Map<String, String> hashes(Map<String, byte[]> resources) {
        Map<String, String> result = new LinkedHashMap<>();
        resources.forEach((path, bytes) -> result.put(path, sha256(bytes)));
        return Map.copyOf(result);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static void deleteRecursive(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record BuiltinSkillSpec(String name, List<String> files) {
    }
}
