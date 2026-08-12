package com.devcli.tool.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultCommandExecutionServiceTest {

    @Test
    void routesIsolatedCommandsOnlyToSandboxBackend(@TempDir Path project) {
        AtomicInteger hostCalls = new AtomicInteger();
        AtomicInteger sandboxCalls = new AtomicInteger();
        DefaultCommandExecutionService service = new DefaultCommandExecutionService(
                request -> {
                    hostCalls.incrementAndGet();
                    return CommandExecutionService.Result.completed(0, "host");
                },
                request -> {
                    sandboxCalls.incrementAndGet();
                    return CommandExecutionService.Result.completed(0, "sandbox");
                });

        CommandExecutionService.Result result = service.execute(new CommandExecutionService.Request(
                "pwd", project, 30, true));

        assertEquals("sandbox", result.output());
        assertEquals(0, hostCalls.get());
        assertEquals(1, sandboxCalls.get());
    }

    @Test
    void dockerCommandUsesLockedDownContainerWithoutNetwork(@TempDir Path project) {
        Properties properties = new Properties();
        properties.setProperty(DefaultCommandExecutionService.SANDBOX_IMAGE_PROPERTY,
                "devcli-test:latest");
        DefaultCommandExecutionService.Config config =
                DefaultCommandExecutionService.Config.resolve(properties, Map.of());

        List<String> command = DefaultCommandExecutionService.dockerCommand(
                new CommandExecutionService.Request("mvn test", project, 30, true), config);

        assertEquals("docker", command.get(0));
        assertTrue(command.containsAll(List.of(
                "--network", "none",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--read-only",
                "--pull", "never",
                "--workdir", "/workspace",
                "devcli-test:latest",
                "sh", "-lc", "mvn test")), command.toString());
        assertTrue(command.stream().anyMatch(value ->
                value.startsWith("type=bind,src=") && value.endsWith(",dst=/workspace")),
                command.toString());
        assertEquals("256", command.get(command.indexOf("--pids-limit") + 1));
        assertEquals("1024m", command.get(command.indexOf("--memory") + 1));
    }

    @Test
    void configurationUsesSystemPropertyBeforeEnvironment() {
        Properties properties = new Properties();
        properties.setProperty(DefaultCommandExecutionService.SANDBOX_IMAGE_PROPERTY,
                "property-image");

        DefaultCommandExecutionService.Config config =
                DefaultCommandExecutionService.Config.resolve(properties,
                        Map.of(DefaultCommandExecutionService.SANDBOX_IMAGE_ENV, "env-image"));

        assertEquals("property-image", config.image());
    }
}
