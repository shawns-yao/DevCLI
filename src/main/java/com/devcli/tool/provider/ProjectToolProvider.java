package com.devcli.tool.provider;

import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ProjectToolProvider implements ToolProvider {
    @Override
    public void register(ToolContext context) {
        context.registerTool(ToolRegistry.Tool.structured(
                "create_project",
                "创建新项目结构",
                context.createToolParameters(
                        new ToolParameter("name", "string", "项目名称", true),
                        new ToolParameter("type", "string", "项目类型", true, "java", "python", "node")
                ),
                args -> {
                    String name = args.get("name");
                    String type = args.get("type");
                    Path projectRoot = context.resolveSafePath(name);
                    try {
                        Files.createDirectories(projectRoot);

                        switch (type.toLowerCase()) {
                            case "java" -> {
                                Files.createDirectories(projectRoot.resolve("src/main/java"));
                                Files.createDirectories(projectRoot.resolve("src/main/resources"));
                                Files.writeString(projectRoot.resolve("pom.xml"),
                                        String.format("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                                "<project>\n" +
                                                "    <modelVersion>4.0.0</modelVersion>\n" +
                                                "    <groupId>com.example</groupId>\n" +
                                                "    <artifactId>%s</artifactId>\n" +
                                                "    <version>1.0</version>\n" +
                                                "</project>", name));
                            }
                            case "python" -> {
                                Files.createDirectories(projectRoot.resolve(name));
                                Files.writeString(projectRoot.resolve("main.py"), "# 主程序入口\n");
                                Files.writeString(projectRoot.resolve("requirements.txt"), "# 依赖列表\n");
                            }
                            case "node" -> Files.writeString(projectRoot.resolve("package.json"),
                                    String.format("{\"name\": \"%s\", \"version\": \"1.0.0\"}", name));
                            default -> {
                                return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS,
                                        "创建项目失败: 不支持的项目类型 " + type, false);
                            }
                        }
                        return ToolOutput.success(
                                "项目已创建: " + name + " (类型: " + type + ")");
                    } catch (Exception e) {
                        return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                                "创建项目失败: " + e.getMessage(), false);
                    }
                }
        ));
    }
}
