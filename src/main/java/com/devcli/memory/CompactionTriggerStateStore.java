package com.devcli.memory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/**
 * 持久化一次尚未完成的压缩触发，避免进程在确定性淘汰后崩溃时丢失 crossed 状态。
 * 文件只保存状态元数据，不保存对话正文或工具结果。
 */
final class CompactionTriggerStateStore {
    private final Path file;
    private volatile boolean durable = true;

    CompactionTriggerStateStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    synchronized Optional<State> load() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            return Optional.of(new State(
                    Long.parseLong(properties.getProperty("epoch", "0")),
                    properties.getProperty("state", "TRIGGERED"),
                    Integer.parseInt(properties.getProperty("triggerTokens", "0")),
                    Integer.parseInt(properties.getProperty("beforeTokens", "0")),
                    properties.getProperty("sourceHash", ""),
                    Integer.parseInt(properties.getProperty("retryCount", "0"))));
        } catch (IOException | RuntimeException e) {
            // 损坏的 pending 文件按“仍需压缩”处理，宁可多做一次语义压缩，也不能静默丢失 crossed 状态。
            return Optional.of(new State(0, "TRIGGERED", 0, 0, "", 0));
        }
    }

    synchronized State begin(int triggerTokens, int beforeTokens, String sourceHash) {
        State previous = load().orElse(null);
        State state = new State(
                previous == null ? 1 : previous.epoch() + 1,
                "TRIGGERED",
                triggerTokens,
                beforeTokens,
                sourceHash,
                0);
        write(state);
        return state;
    }

    boolean isDurable() {
        return durable;
    }

    synchronized void recordRetry(State state) {
        if (state == null) {
            return;
        }
        write(new State(state.epoch(), state.state(), state.triggerTokens(), state.beforeTokens(),
                state.sourceHash(), state.retryCount() + 1));
    }

    synchronized void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 压缩已经提交成功，残留状态只会在下次读取时被忽略或覆盖。
        }
    }

    private void write(State state) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Properties properties = new Properties();
            properties.setProperty("epoch", Long.toString(state.epoch()));
            properties.setProperty("state", state.state());
            properties.setProperty("triggerTokens", Integer.toString(state.triggerTokens()));
            properties.setProperty("beforeTokens", Integer.toString(state.beforeTokens()));
            properties.setProperty("sourceHash", state.sourceHash());
            properties.setProperty("retryCount", Integer.toString(state.retryCount()));
            Path temporary = Files.createTempFile(parent, ".compaction-state-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "DevCLI compaction trigger state");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            durable = true;
        } catch (IOException | RuntimeException ignored) {
            durable = false;
        }
    }

    record State(long epoch, String state, int triggerTokens, int beforeTokens,
                 String sourceHash, int retryCount) {
    }
}
