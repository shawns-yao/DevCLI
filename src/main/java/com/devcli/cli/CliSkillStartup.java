package com.devcli.cli;

import com.devcli.event.RunEventSink;
import com.devcli.hitl.HitlToolRegistry;
import com.devcli.mcp.McpServerManager;
import com.devcli.skill.SkillRegistry;
import java.nio.file.Path;

/** Skill 目录、内置资源与依赖发现的启动装配。 */
final class CliSkillStartup {
    private CliSkillStartup() {}
    record Result(SkillRegistry registry, com.devcli.skill.SkillStateStore stateStore, String note) {}

    static Result load(HitlToolRegistry hitlToolRegistry, McpServerManager mcpServerManager, RunEventSink events) {
        String startupNote = "";
    // === Skill 系统初始化 ===
    Path home = Path.of(System.getProperty("user.home"));
    Path skillsCacheDir = home.resolve(".devcli/skills-cache");
    Path userSkillsDir = home.resolve(".devcli/skills");
    Path projectSkillsDir = Path.of(".devcli/skills").toAbsolutePath();
    try {
        new com.devcli.skill.SkillBuiltinExtractor(skillsCacheDir,
                diagnostic -> events.emit(new com.devcli.event.RunEvent.CustomMessage(
                        "skill.diagnostic", diagnostic.message(), java.util.Map.of(
                        "code", diagnostic.code(), "path", diagnostic.path())))).extractAll();
    } catch (Exception e) {
        startupNote = "内置 skill 解压失败: " + e.getMessage();
    }
    com.devcli.skill.SkillStateStore skillStateStore = new com.devcli.skill.SkillStateStore(home.resolve(".devcli/skills.json"));
    skillStateStore.setDiagnosticSink(diagnostic -> events.emit(
            new com.devcli.event.RunEvent.CustomMessage(
                    "skill.diagnostic", diagnostic.message(), java.util.Map.of(
                    "code", diagnostic.code(), "path", diagnostic.path()))));
    com.devcli.skill.SkillRegistry skillRegistry = new com.devcli.skill.SkillRegistry(
            skillsCacheDir, userSkillsDir, projectSkillsDir, skillStateStore,
            com.devcli.skill.SkillRegistry.sharedUserDirectories(home));
    skillRegistry.setAvailableDependencies(
            () -> hitlToolRegistry.searchableTools().stream()
                    .map(com.devcli.tool.ToolContract.Tool::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()),
            () -> mcpServerManager.servers().stream()
                    .filter(server -> server.status() == com.devcli.mcp.McpServerStatus.READY)
                    .map(com.devcli.mcp.McpServer::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    skillRegistry.setDiagnosticSink(diagnostic -> events.emit(
            new com.devcli.event.RunEvent.CustomMessage(
                    "skill.diagnostic", diagnostic.message(), java.util.Map.of(
                    "code", diagnostic.code(), "path", diagnostic.path()))));
    skillRegistry.reload();
        return new Result(skillRegistry, skillStateStore, startupNote);
    }
}
