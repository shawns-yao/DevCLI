package com.devcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.devcli.agent.AgentCheckpoint.*;

/** Checkpoint JSON 的原子保存、加载和目录查询。 */
final class CheckpointFiles {
    private static final Logger log = LoggerFactory.getLogger(CheckpointFiles.class);
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private CheckpointFiles() {}
    static void save(AgentCheckpoint checkpoint) throws IOException {
        Path checkpointDir = AgentCheckpoint.getCheckpointDir();
        Files.createDirectories(checkpointDir);

        checkpoint.validateAgentState();
        Path checkpointFile = checkpointDir.resolve(checkpoint.getOrchestrationId() + ".json");
        Path tempFile = checkpointDir.resolve(checkpoint.getOrchestrationId() + ".json.tmp");
        mapper.writeValue(tempFile.toFile(), checkpoint);
        try {
            Files.move(tempFile, checkpointFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnsupported) {
            Files.move(tempFile, checkpointFile, StandardCopyOption.REPLACE_EXISTING);
        }

        log.info("Checkpoint 已保存: {} (已完成: {}/{} 步)",
                checkpoint.getOrchestrationId(), checkpoint.getCompletedSteps().size(), checkpoint.getPlanSteps().isEmpty()
                        ? checkpoint.getCompletedSteps().size() + checkpoint.getFailedSteps() : checkpoint.getPlanSteps().size());
    }

    static LoadResult loadResult(String orchestrationId) {
        try {
            Path checkpointFile = AgentCheckpoint.getCheckpointDir().resolve(orchestrationId + ".json");
            if (!Files.exists(checkpointFile)) {
                log.warn("Checkpoint 不存在: {}", orchestrationId);
                return new LoadResult(LoadStatus.NOT_FOUND, null, "checkpoint 不存在");
            }

            AgentCheckpoint checkpoint = mapper.readValue(checkpointFile.toFile(), AgentCheckpoint.class);
            int loadedVersion = checkpoint.getProtocolVersion() <= 0 ? 1 : checkpoint.getProtocolVersion();
            if (loadedVersion > CURRENT_PROTOCOL_VERSION) {
                String message = "checkpoint 协议版本不兼容：文件版本 " + loadedVersion
                        + "，当前版本 " + CURRENT_PROTOCOL_VERSION;
                log.error("拒绝加载未来版本 Checkpoint: {} (文件版本: {}, 当前版本: {})",
                        orchestrationId, loadedVersion, CURRENT_PROTOCOL_VERSION);
                return new LoadResult(LoadStatus.INCOMPATIBLE, null, message);
            }
            checkpoint.normalizeState();
            log.info("Checkpoint 已加载: {} (协议版本: {}, 已完成: {} 步)",
                orchestrationId, loadedVersion, checkpoint.getCompletedSteps().size());
            return new LoadResult(LoadStatus.LOADED, checkpoint, "");
        } catch (IOException | IllegalArgumentException e) {
            log.error("加载 Checkpoint 失败: {}", e.getMessage(), e);
            return new LoadResult(LoadStatus.INVALID, null,
                    e.getMessage() == null ? "checkpoint 无法解析" : e.getMessage());
        }
    }

    /**
     * 列出所有可恢复的 Checkpoint
     */
    static List<CheckpointInfo> listAvailable() {
        List<CheckpointInfo> checkpoints = new ArrayList<>();
        try {
            Path checkpointDir = AgentCheckpoint.getCheckpointDir();
            if (!Files.exists(checkpointDir)) {
                return checkpoints;
            }

            try (var paths = Files.list(checkpointDir)) {
                paths.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            AgentCheckpoint cp = mapper.readValue(p.toFile(), AgentCheckpoint.class);
                            cp.normalizeState();
                            checkpoints.add(new CheckpointInfo(
                                cp.getOrchestrationId(),
                                cp.getGoal(),
                                cp.getCompletedSteps().size(),
                                cp.getFailedSteps(),
                                Instant.ofEpochMilli(cp.getTimestamp())
                            ));
                        } catch (IOException | IllegalArgumentException e) {
                            log.warn("读取 Checkpoint 失败: {}", p.getFileName());
                        }
                    });
            }
        } catch (IOException e) {
            log.error("列出 Checkpoint 失败: {}", e.getMessage());
        }
        return checkpoints;
    }

}
