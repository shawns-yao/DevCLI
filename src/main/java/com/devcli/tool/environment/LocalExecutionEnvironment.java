package com.devcli.tool.environment;

import com.devcli.tool.command.CommandExecutionService;
import com.devcli.tool.command.DefaultCommandExecutionService;
import com.devcli.tool.provider.ProjectFileLister;
import com.devcli.workspace.FileModeSnapshot;
import com.devcli.workspace.PatchSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** 本地文件视图；命令由现有主机、Docker 或 Windows 隔离后端执行。 */
public class LocalExecutionEnvironment implements ExecutionEnvironment {
    private final CommandExecutionService commands;

    public LocalExecutionEnvironment() { this(new DefaultCommandExecutionService()); }
    public LocalExecutionEnvironment(CommandExecutionService commands) { this.commands = commands; }
    @Override public CommandExecutionService commands() { return commands; }
    @Override public boolean exists(Path path) { return Files.exists(path); }
    @Override public boolean isRegularFile(Path path) { return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS); }
    @Override public boolean isSymbolicLink(Path path) { return Files.isSymbolicLink(path); }
    @Override public long size(Path path) throws IOException { return Files.size(path); }
    @Override public InputStream openInput(Path path) throws IOException { return Files.newInputStream(path); }
    @Override public void createDirectories(Path path) throws IOException { Files.createDirectories(path); }
    @Override public void writeString(Path path, String content) throws IOException { Files.writeString(path, content); }
    @Override public List<DirectoryEntry> list(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> new DirectoryEntry(path.getFileName().toString(), Files.isDirectory(path))).toList();
        }
    }
    @Override public List<Path> findFiles(Path root, Path scope) { return ProjectFileLister.list(root, scope); }
    @Override public String hash(Path path) throws IOException { return PatchSet.hash(path); }
    @Override public FileModeSnapshot permissions(Path path) throws IOException { return FileModeSnapshot.capture(path); }
    @Override public PatchSet.ApplyResult applyPatch(Path root, PatchSet patch) throws IOException { return patch.apply(root); }
}
