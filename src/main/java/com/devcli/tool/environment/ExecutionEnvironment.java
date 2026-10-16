package com.devcli.tool.environment;

import com.devcli.tool.command.CommandExecutionService;
import com.devcli.workspace.FileModeSnapshot;
import com.devcli.workspace.PatchSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * 命令和项目文件共用的执行边界。路径已经过工具权限与项目围栏校验。
 * Path 表示本地工作区视图；后端必须在操作返回前保持它与执行侧一致，
 * 使版本账本、LSP 和 PatchSet 归并读取同一基线。会话及凭据存储不属于此接口。
 */
public interface ExecutionEnvironment {
    CommandExecutionService commands();
    boolean exists(Path path);
    boolean isRegularFile(Path path);
    boolean isSymbolicLink(Path path);
    long size(Path path) throws IOException;
    InputStream openInput(Path path) throws IOException;
    void createDirectories(Path path) throws IOException;
    void writeString(Path path, String content) throws IOException;
    List<DirectoryEntry> list(Path directory) throws IOException;
    List<Path> findFiles(Path root, Path scope);
    String hash(Path path) throws IOException;
    FileModeSnapshot permissions(Path path) throws IOException;
    PatchSet.ApplyResult applyPatch(Path root, PatchSet patch) throws IOException;

    default String readString(Path path) throws IOException {
        try (var input = openInput(path)) {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(input.readAllBytes())).toString();
        }
    }

    record DirectoryEntry(String name, boolean directory) { }
}
