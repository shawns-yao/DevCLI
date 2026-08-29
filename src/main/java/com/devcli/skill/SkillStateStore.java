package com.devcli.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Skill 启用状态和项目目录信任状态的跨进程原子存储。 */
public final class SkillStateStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SCHEMA_VERSION = 2;
    private static final ConcurrentHashMap<Path, ReentrantLock> PROCESS_LOCKS = new ConcurrentHashMap<>();

    private final Path file;
    private final Path lockFile;
    private final ReentrantLock processLock;
    private volatile Consumer<SkillRegistry.Diagnostic> diagnosticSink = ignored -> { };

    public SkillStateStore(Path file) {
        if (file == null) throw new IllegalArgumentException("skills state file 不能为空");
        this.file = file.toAbsolutePath().normalize();
        String fileName = this.file.getFileName() == null ? "skills.json" : this.file.getFileName().toString();
        this.lockFile = this.file.resolveSibling(fileName + ".lock");
        this.processLock = PROCESS_LOCKS.computeIfAbsent(this.lockFile, ignored -> new ReentrantLock(true));
    }

    public Path file() {
        return file;
    }

    public void setDiagnosticSink(Consumer<SkillRegistry.Diagnostic> sink) {
        diagnosticSink = sink == null ? ignored -> { } : sink;
    }

    public Set<String> disabled() {
        return withState(state -> state.disabled);
    }

    public boolean isProjectDirectoryTrusted(Path directory) {
        return trustedProjectDirectoryFingerprints().contains(projectDirectoryFingerprint(directory));
    }

    public void trustProjectDirectory(Path directory) {
        update(state -> new State(state.disabled, append(state.trustedProjectDirectories,
                projectDirectoryFingerprint(directory))));
    }

    public void untrustProjectDirectory(Path directory) {
        update(state -> new State(state.disabled, remove(state.trustedProjectDirectories,
                projectDirectoryFingerprint(directory))));
    }

    public Set<String> trustedProjectDirectoryFingerprints() {
        return withState(state -> state.trustedProjectDirectories);
    }

    public void disable(String name) {
        if (name == null || name.isBlank()) return;
        update(state -> new State(append(state.disabled, name), state.trustedProjectDirectories));
    }

    public void enable(String name) {
        if (name == null || name.isBlank()) return;
        update(state -> new State(remove(state.disabled, name), state.trustedProjectDirectories));
    }

    private <T> T withState(Function<State, T> action) {
        return withFileLock(() -> action.apply(readUnlocked()));
    }

    private void update(UnaryOperator<State> updater) {
        withFileLock(() -> {
            State updated = updater.apply(readUnlocked());
            writeUnlocked(updated);
            return null;
        });
    }

    private <T> T withFileLock(IoSupplier<T> action) {
        processLock.lock();
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return action.get();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("访问 Skill 状态文件失败: " + file, e);
        } finally {
            processLock.unlock();
        }
    }

    private State readUnlocked() {
        if (!Files.exists(file)) return State.empty();
        try {
            String content = Files.readString(file);
            if (content.isBlank()) return State.empty();
            JsonNode root = MAPPER.readTree(content);
            if (!root.isObject()) throw new IOException("根节点必须是对象");
            return new State(readSet(root.path("disabled")),
                    readSet(root.path("trustedProjectDirectories")));
        } catch (Exception e) {
            diagnosticSink.accept(new SkillRegistry.Diagnostic(
                    "skill_state_invalid", "skills.json 解析失败，使用空状态: " + e.getMessage(),
                    file.toString()));
            return State.empty();
        }
    }

    private void writeUnlocked(State state) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.putPOJO("disabled", state.disabled);
        root.putPOJO("trustedProjectDirectories", state.trustedProjectDirectories);
        Path temporary = Files.createTempFile(parent, file.getFileName().toString() + ".", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private static Set<String> readSet(JsonNode node) {
        if (!node.isArray()) return Set.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        node.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) values.add(item.asText());
        });
        return Set.copyOf(values);
    }

    private static Set<String> append(Set<String> source, String value) {
        LinkedHashSet<String> copy = new LinkedHashSet<>(source);
        copy.add(value);
        return Set.copyOf(copy);
    }

    private static Set<String> remove(Set<String> source, String value) {
        LinkedHashSet<String> copy = new LinkedHashSet<>(source);
        copy.remove(value);
        return Set.copyOf(copy);
    }

    private static String projectDirectoryFingerprint(Path directory) {
        if (directory == null) throw new IllegalArgumentException("project skill directory 不能为空");
        Path canonical;
        try {
            canonical = directory.toRealPath();
        } catch (IOException ignored) {
            canonical = directory.toAbsolutePath().normalize();
        }
        String normalized = canonical.toString().replace('\\', '/');
        if (isWindows()) normalized = normalized.toLowerCase(java.util.Locale.ROOT);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private record State(Set<String> disabled, Set<String> trustedProjectDirectories) {
        private State {
            disabled = disabled == null ? Set.of() : Set.copyOf(disabled);
            trustedProjectDirectories = trustedProjectDirectories == null
                    ? Set.of() : Set.copyOf(trustedProjectDirectories);
        }

        private static State empty() {
            return new State(Set.of(), Set.of());
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
