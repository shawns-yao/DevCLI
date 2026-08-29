package com.devcli.tool.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    }

    @Test
    void dockerCommandAssignsDeterministicNameForForcedCleanup(@TempDir Path project) {
        DefaultCommandExecutionService.Config config =
                DefaultCommandExecutionService.Config.resolve(new Properties(), Map.of());

        List<String> command = DefaultCommandExecutionService.dockerCommand(
                new CommandExecutionService.Request("mvn test", project, 30, true),
                config, "devcli-run-fixed");

        int nameOption = command.indexOf("--name");
        assertTrue(nameOption > 0, command.toString());
        assertEquals("devcli-run-fixed", command.get(nameOption + 1));
        assertEquals(List.of("docker", "rm", "-f", "devcli-run-fixed"),
                DefaultCommandExecutionService.dockerCleanupCommand(config, "devcli-run-fixed"));
    }

    @Test
    void dockerCommandCanRunAsExplicitNonRootUser(@TempDir Path project) {
        Properties properties = new Properties();
        properties.setProperty(DefaultCommandExecutionService.SANDBOX_USER_PROPERTY, "1000:1000");
        DefaultCommandExecutionService.Config config =
                DefaultCommandExecutionService.Config.resolve(properties, Map.of());

        List<String> command = DefaultCommandExecutionService.dockerCommand(
                new CommandExecutionService.Request("id", project, 30, true), config);

        assertEquals(List.of("run", "--user", "1000:1000"), command.subList(1, 4));
    }

    @Test
    void dockerCommandMountsExplicitMavenRepositoryReadOnly(@TempDir Path project,
                                                            @TempDir Path repository) {
        Properties properties = new Properties();
        properties.setProperty(
                DefaultCommandExecutionService.SANDBOX_MAVEN_REPOSITORY_PROPERTY,
                repository.toString());
        DefaultCommandExecutionService.Config config =
                DefaultCommandExecutionService.Config.resolve(properties, Map.of());

        List<String> command = DefaultCommandExecutionService.dockerCommand(
                new CommandExecutionService.Request("mvn test", project, 30, true), config);

        assertTrue(command.contains("type=bind,src=" + repository.toAbsolutePath().normalize()
                + ",dst=/maven-repository,readonly"), command.toString());
        int envIndex = command.indexOf("--env");
        assertTrue(envIndex > 0, command.toString());
        assertEquals("MAVEN_OPTS=-Dmaven.repo.local=/maven-repository",
                command.get(envIndex + 1));
    }

    @Test
    void dockerCommandDoesNotExposeMavenRepositoryToNonMavenCommands(
            @TempDir Path project, @TempDir Path repository) {
        Properties properties = new Properties();
        properties.setProperty(
                DefaultCommandExecutionService.SANDBOX_MAVEN_REPOSITORY_PROPERTY,
                repository.toString());
        DefaultCommandExecutionService.Config config =
                DefaultCommandExecutionService.Config.resolve(properties, Map.of());

        List<String> command = DefaultCommandExecutionService.dockerCommand(
                new CommandExecutionService.Request("javac @sources.args", project, 30, true),
                config);

        assertTrue(command.stream().noneMatch(value -> value.contains("/maven-repository")),
                command.toString());
    }

    @Test
    void hostWarnMavenUsesExplicitLocalRepository(@TempDir Path project,
                                                  @TempDir Path repository) {
        Properties properties = new Properties();
        properties.setProperty(DefaultCommandExecutionService.SANDBOX_MODE_PROPERTY, "HOST_WARN");
        properties.setProperty(
                DefaultCommandExecutionService.SANDBOX_MAVEN_REPOSITORY_PROPERTY,
                repository.toString());
        DefaultCommandExecutionService.Config config =
                DefaultCommandExecutionService.Config.resolve(properties, Map.of());
        java.util.concurrent.atomic.AtomicReference<String> command =
                new java.util.concurrent.atomic.AtomicReference<>();
        DefaultCommandExecutionService service = new DefaultCommandExecutionService(
                request -> {
                    command.set(request.command());
                    return CommandExecutionService.Result.completed(0, "ok");
                },
                request -> CommandExecutionService.Result.completed(0, "sandbox"),
                config);

        service.execute(new CommandExecutionService.Request(
                "mvn -q test", project, 30, true));

        assertTrue(command.get().startsWith("mvn -Dmaven.repo.local=\""
                + repository.toAbsolutePath().normalize() + "\" -o "), command.get());
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

    @Test
    void explicitHostWarnModeRoutesIsolatedCommandToHostWithWarning(@TempDir Path project) {
        AtomicInteger hostCalls = new AtomicInteger();
        AtomicInteger sandboxCalls = new AtomicInteger();
        DefaultCommandExecutionService service = new DefaultCommandExecutionService(
                request -> {
                    hostCalls.incrementAndGet();
                    return CommandExecutionService.Result.completed(0, "host-result");
                },
                request -> {
                    sandboxCalls.incrementAndGet();
                    return CommandExecutionService.Result.completed(0, "sandbox-result");
                },
                DefaultCommandExecutionService.SandboxMode.HOST_WARN);

        CommandExecutionService.Result result = service.execute(new CommandExecutionService.Request(
                "mvn test", project, 30, true));

        assertEquals(1, hostCalls.get());
        assertEquals(0, sandboxCalls.get());
        assertTrue(result.output().contains("HOST_WARN"), result.output());
        assertTrue(result.output().contains("host-result"), result.output());
    }

    @Test
    void hostWarnDoesNotChangeOrdinaryHostCommands(@TempDir Path project) {
        AtomicInteger hostCalls = new AtomicInteger();
        DefaultCommandExecutionService service = new DefaultCommandExecutionService(
                request -> {
                    hostCalls.incrementAndGet();
                    return CommandExecutionService.Result.completed(0, "host-result");
                },
                request -> CommandExecutionService.Result.completed(0, "sandbox-result"),
                DefaultCommandExecutionService.SandboxMode.HOST_WARN);

        CommandExecutionService.Result result = service.execute(new CommandExecutionService.Request(
                "pwd", project, 30, false));

        assertEquals(1, hostCalls.get());
        assertEquals("host-result", result.output());
    }

    @Test
    void hostWarnRejectsExternalOrChainedCommandsBeforeHostExecution(@TempDir Path project) {
        AtomicInteger hostCalls = new AtomicInteger();
        DefaultCommandExecutionService service = new DefaultCommandExecutionService(
                request -> {
                    hostCalls.incrementAndGet();
                    return CommandExecutionService.Result.completed(0, "host-result");
                },
                request -> CommandExecutionService.Result.completed(0, "sandbox-result"),
                DefaultCommandExecutionService.SandboxMode.HOST_WARN);

        IllegalArgumentException network = assertThrows(IllegalArgumentException.class,
                () -> service.execute(new CommandExecutionService.Request(
                        "curl https://example.com", project, 30, true)));
        IllegalArgumentException chained = assertThrows(IllegalArgumentException.class,
                () -> service.execute(new CommandExecutionService.Request(
                        "mvn test; curl https://example.com", project, 30, true)));
        IllegalArgumentException gitWrite = assertThrows(IllegalArgumentException.class,
                () -> service.execute(new CommandExecutionService.Request(
                        "git push origin main", project, 30, true)));
        IllegalArgumentException unsupportedRuntime = assertThrows(IllegalArgumentException.class,
                () -> service.execute(new CommandExecutionService.Request(
                        "npm test", project, 30, true)));
        IllegalArgumentException mavenExec = assertThrows(IllegalArgumentException.class,
                () -> service.execute(new CommandExecutionService.Request(
                        "mvn exec:exec -Dexec.executable=calc", project, 30, true)));
        IllegalArgumentException mavenDeploy = assertThrows(IllegalArgumentException.class,
                () -> service.execute(new CommandExecutionService.Request(
                        "mvn deploy", project, 30, true)));

        assertTrue(network.getMessage().contains("HOST_WARN"), network.getMessage());
        assertTrue(chained.getMessage().contains("HOST_WARN"), chained.getMessage());
        assertTrue(gitWrite.getMessage().contains("HOST_WARN"), gitWrite.getMessage());
        assertTrue(unsupportedRuntime.getMessage().contains("HOST_WARN"),
                unsupportedRuntime.getMessage());
        assertTrue(mavenExec.getMessage().contains("HOST_WARN"), mavenExec.getMessage());
        assertTrue(mavenDeploy.getMessage().contains("HOST_WARN"), mavenDeploy.getMessage());
        assertEquals(0, hostCalls.get());
    }

    @Test
    void hostWarnAllowsProjectBuildAndReadOnlyGitCommands(@TempDir Path project) {
        AtomicInteger hostCalls = new AtomicInteger();
        java.util.List<String> commands = new java.util.ArrayList<>();
        DefaultCommandExecutionService service = new DefaultCommandExecutionService(
                request -> {
                    hostCalls.incrementAndGet();
                    commands.add(request.command());
                    return CommandExecutionService.Result.completed(0, request.command());
                },
                request -> CommandExecutionService.Result.completed(0, "sandbox-result"),
                DefaultCommandExecutionService.SandboxMode.HOST_WARN);

        service.execute(new CommandExecutionService.Request("mvn test", project, 30, true));
        service.execute(new CommandExecutionService.Request(
                "mvn -q -DskipTests test-compile", project, 30, true));
        service.execute(new CommandExecutionService.Request(
                "./mvnw -q -DskipTests test-compile", project, 30, true));
        service.execute(new CommandExecutionService.Request("javac @\"target/sources.args\"", project, 30, true));
        service.execute(new CommandExecutionService.Request("git status --short", project, 30, true));

        assertEquals(5, hostCalls.get());
        assertTrue(commands.get(0).startsWith("mvn -o "), commands.toString());
        assertTrue(commands.get(1).startsWith("mvn -o "), commands.toString());
        assertTrue(commands.get(2).startsWith("./mvnw -o "), commands.toString());
        assertEquals("javac @\"target/sources.args\"", commands.get(3));
        assertEquals("git status --short", commands.get(4));
    }

    @Test
    void sandboxModeRejectsInvalidExplicitValue() {
        Properties properties = new Properties();
        properties.setProperty(DefaultCommandExecutionService.SANDBOX_MODE_PROPERTY, "auto");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DefaultCommandExecutionService.Config.resolve(properties, Map.of()));

        assertTrue(error.getMessage().contains("DOCKER|HOST_WARN"), error.getMessage());
    }

    @Test
    void sandboxMavenRepositoryRequiresExistingAbsoluteDirectory(@TempDir Path project) {
        Properties relative = new Properties();
        relative.setProperty(
                DefaultCommandExecutionService.SANDBOX_MAVEN_REPOSITORY_PROPERTY,
                "relative/repository");
        Properties missing = new Properties();
        missing.setProperty(
                DefaultCommandExecutionService.SANDBOX_MAVEN_REPOSITORY_PROPERTY,
                project.resolve("missing").toString());

        IllegalArgumentException relativeError = assertThrows(IllegalArgumentException.class,
                () -> DefaultCommandExecutionService.Config.resolve(relative, Map.of()));
        IllegalArgumentException missingError = assertThrows(IllegalArgumentException.class,
                () -> DefaultCommandExecutionService.Config.resolve(missing, Map.of()));

        assertTrue(relativeError.getMessage().contains("absolute"), relativeError.getMessage());
        assertTrue(missingError.getMessage().contains("existing directory"),
                missingError.getMessage());
    }

    @Test
    void sandboxModeDefaultsToDockerAndPropertyOverridesEnvironment() {
        assertEquals(DefaultCommandExecutionService.SandboxMode.DOCKER,
                DefaultCommandExecutionService.Config.resolve(new Properties(), Map.of()).mode());

        Properties properties = new Properties();
        properties.setProperty(DefaultCommandExecutionService.SANDBOX_MODE_PROPERTY, "host_warn");
        DefaultCommandExecutionService.Config config = DefaultCommandExecutionService.Config.resolve(
                properties,
                Map.of(DefaultCommandExecutionService.SANDBOX_MODE_ENV, "docker"));

        assertEquals(DefaultCommandExecutionService.SandboxMode.HOST_WARN, config.mode());
    }
}
