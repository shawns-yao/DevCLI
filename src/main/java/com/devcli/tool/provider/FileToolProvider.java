package com.devcli.tool.provider;

import com.devcli.policy.PolicyException;
import com.devcli.tool.ToolRegistry;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FileToolProvider implements ToolProvider {
    @Override
    public void register(ToolContext context) {
        context.registerTool(new ToolRegistry.Tool(
                "read_file",
                "读取文件内容（仅限项目根目录之内）",
                context.createToolParameters(new ToolParameter("path", "string", "文件路径", true)),
                args -> {
                    Path safe = context.resolveSafePath(args.get("path"));
                    try {
                        return "文件内容:\n" + Files.readString(safe);
                    } catch (Exception e) {
                        return "读取文件失败: " + e.getMessage();
                    }
                }
        ));

        context.registerTool(new ToolRegistry.Tool(
                "write_file",
                "写入文件内容（仅限项目根目录之内，单文件 5MB 上限）",
                context.createToolParameters(
                        new ToolParameter("path", "string", "文件路径", true),
                        new ToolParameter("content", "string", "文件内容", true)
                ),
                args -> {
                    String path = args.get("path");
                    String content = args.get("content") == null ? "" : args.get("content");
                    int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
                    if (contentBytes > context.maxWriteFileBytes()) {
                        throw new PolicyException("写入内容 " + contentBytes + " 字节超过 "
                                + (context.maxWriteFileBytes() / 1024 / 1024) + "MB 上限");
                    }
                    Path safe = context.resolveSafePath(path);
                    String activeStep = context.currentResourceLeaseStep();
                    if (activeStep != null && !activeStep.isBlank()) {
                        context.acquireWriteLease(activeStep, safe);
                        if (!context.isWriteLeaseValid(activeStep, safe)) {
                            throw new PolicyException("写入冲突: 租约已失效，文件 " + path
                                    + " 可能正在被其他任务写入");
                        }
                    }
                    String before = null;
                    try {
                        if (Files.exists(safe) && Files.isRegularFile(safe)) {
                            before = Files.readString(safe);
                        }
                    } catch (Exception ignored) {
                        // 二进制 / 大文件 / 编码错读不出来时，前文当 null 处理（diff 退化为长度提示）
                    }
                    try {
                        Path parent = safe.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.writeString(safe, content);
                        context.recordFileWrite(path, safe, before, content, activeStep);
                        return "文件已写入: " + path;
                    } catch (Exception e) {
                        return "写入文件失败: " + e.getMessage();
                    }
                }
        ));

        context.registerTool(new ToolRegistry.Tool(
                "list_dir",
                "列出目录内容（仅限项目根目录之内）",
                context.createToolParameters(new ToolParameter("path", "string", "目录路径", true)),
                args -> {
                    Path safe = context.resolveSafePath(args.get("path"));
                    try {
                        File[] files = safe.toFile().listFiles();
                        if (files == null) {
                            return "目录为空或不存在";
                        }
                        StringBuilder sb = new StringBuilder("目录内容:\n");
                        for (File f : files) {
                            sb.append(f.isDirectory() ? "[D] " : "[F] ")
                                    .append(f.getName())
                                    .append("\n");
                        }
                        return sb.toString();
                    } catch (Exception e) {
                        return "列出目录失败: " + e.getMessage();
                    }
                }
        ));
    }
}
