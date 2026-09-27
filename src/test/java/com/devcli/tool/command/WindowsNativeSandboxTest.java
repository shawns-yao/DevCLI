package com.devcli.tool.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 定向测试：通过实际命令执行入口检查原生隔离，不连接模型或数据库。 */
@EnabledOnOs(OS.WINDOWS)
class WindowsNativeSandboxTest {
    @TempDir Path workspace;

    @Test
    void nativeModeMustNotFallBackWhenLauncherIsMissing() throws Exception {
        String mode = System.getProperty("devcli.command.sandbox.mode");
        String launcher = System.getProperty("devcli.windows.sandbox.launcher");
        try {
            System.setProperty("devcli.command.sandbox.mode", "WINDOWS_NATIVE");
            System.setProperty("devcli.windows.sandbox.launcher", workspace.resolve("missing.exe").toString());
            CommandExecutionService service = assertDoesNotThrow(() -> new DefaultCommandExecutionService());
            assertThrows(RuntimeException.class, () -> service.execute(new CommandExecutionService.Request(
                    "echo escaped>escaped.txt", workspace, 10, true)));
            assertFalse(Files.exists(workspace.resolve("escaped.txt")));
        } finally {
            restore("devcli.command.sandbox.mode", mode);
            restore("devcli.windows.sandbox.launcher", launcher);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }

    @Test
    void actualProcessCanWriteOnlyInsideWorkspace() throws Exception {
        requireLauncher();
        Path outside = Files.createTempFile("devcli-outside-", ".txt");
        try {
            Files.writeString(outside, "private-sentinel");
            var result = run("Set-Content -NoNewline -LiteralPath 'inside.txt' -Value 'ok'; "
                    + "try { [IO.File]::ReadAllText('" + outside + "'); exit 41 } catch { }; "
                    + "try { [IO.File]::WriteAllText('" + outside + "','bad'); exit 42 } catch { }; exit 0", 30);
            assertTrue(result.succeeded(), result.output());
            assertEquals("ok", Files.readString(workspace.resolve("inside.txt")));
            assertEquals("private-sentinel", Files.readString(outside));
        } finally { Files.deleteIfExists(outside); }
    }

    @Test
    void networkAndHostEnvironmentAreUnavailable() throws Exception {
        requireLauncher();
        try (var listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            // Prove the endpoint is reachable outside the sandbox; a dead port is not isolation evidence.
            try (var host = new java.net.Socket("127.0.0.1", listener.getLocalPort());
                 var accepted = listener.accept()) {
                assertTrue(host.isConnected());
                assertTrue(accepted.isConnected());
            }
            var result = run("if ($env:DEVCLI_SANDBOX_TEST_SECRET -or $env:HTTP_PROXY -or $env:JAVA_TOOL_OPTIONS) { exit 43 }; "
                    + "$c=[Net.Sockets.TcpClient]::new(); try { if ($c.ConnectAsync('127.0.0.1'," + listener.getLocalPort()
                    + ").Wait(1500)) { exit 44 } } catch { }; $c.Dispose(); Write-Output 'network-denied'; exit 0", 30);
            assertTrue(result.succeeded(), result.output());
            assertTrue(result.output().contains("network-denied"));
            listener.setSoTimeout(300);
            assertThrows(java.net.SocketTimeoutException.class, listener::accept);
        }
    }

    @Test
    void normalExitKillsBackgroundChildren() throws Exception {
        requireLauncher();
        var result = run("$p=Start-Process -FilePath ($PSHOME+'\\powershell.exe') "
                + "-ArgumentList '-NoProfile -NonInteractive -Command Start-Sleep 60' -PassThru; "
                + "if (!$p) { exit 45 }; Write-Output ('child='+$p.Id); exit 0", 30);
        assertTrue(result.succeeded(), result.output());
        var match = java.util.regex.Pattern.compile("child=(\\d+)").matcher(result.output());
        assertTrue(match.find(), result.output());
        long pid = Long.parseLong(match.group(1));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void timeoutStopsTheJob() throws Exception {
        requireLauncher();
        long started = System.nanoTime();
        var result = run("Start-Sleep 60", 3);
        assertTrue(result.timedOut(), result.output());
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 20);
    }

    private static void requireLauncher() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("devcli.windows.sandbox.launcher") != null,
                "Build and explicitly configure the native launcher to run OS isolation checks");
    }

    private CommandExecutionService.Result run(String command, long timeout) {
        return service().execute(new CommandExecutionService.Request(command, workspace, timeout, true));
    }

    private CommandExecutionService service() {
        String mode = System.getProperty("devcli.command.sandbox.mode");
        try {
            System.setProperty("devcli.command.sandbox.mode", "WINDOWS_NATIVE");
            return new DefaultCommandExecutionService();
        } finally { restore("devcli.command.sandbox.mode", mode); }
    }

    @Test
    void concurrentTasksCannotReadEachOthersWorkspace() throws Exception {
        requireLauncher();
        Path a = Files.createDirectory(workspace.resolve("a"));
        Path b = Files.createDirectory(workspace.resolve("b"));
        Files.writeString(a.resolve("private.txt"), "a");
        Files.writeString(b.resolve("private.txt"), "b");
        var service = service();
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> service.execute(
                new CommandExecutionService.Request("Start-Sleep 2; try { [IO.File]::ReadAllText('"
                        + b.resolve("private.txt") + "'); exit 51 } catch { }; exit 0", a, 30, true)));
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> service.execute(
                new CommandExecutionService.Request("Start-Sleep 2; try { [IO.File]::ReadAllText('"
                        + a.resolve("private.txt") + "'); exit 52 } catch { }; exit 0", b, 30, true)));
        var ar = first.get(45, java.util.concurrent.TimeUnit.SECONDS);
        var br = second.get(45, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(ar.succeeded(), ar.output());
        assertTrue(br.succeeded(), br.output());
    }

    @Test
    void externalNetworkAndDnsAreBlocked() {
        requireLauncher();
        var result = run("$c=[Net.Sockets.TcpClient]::new(); try { "
                + "if ($c.ConnectAsync('1.1.1.1',443).Wait(1500)) { exit 53 } } catch { }; "
                + "try { [Net.Dns]::GetHostAddresses('example.com'); exit 54 } catch { }; exit 0", 30);
        assertTrue(result.succeeded(), result.output());
    }

    @Test
    void compilesJavaUsingExplicitReadOnlyRuntime(@TempDir Path runtime) throws Exception {
        requireLauncher();
        String previous = System.getProperty("devcli.windows.sandbox.read.roots");
        try {
            Path installed = Path.of(System.getProperty("java.home"));
            try (var source = Files.walk(installed)) {
                for (Path entry : source.toList()) {
                    Path destination = runtime.resolve(installed.relativize(entry));
                    if (Files.isDirectory(entry)) Files.createDirectories(destination);
                    else Files.copy(entry, destination);
                }
            }
            System.setProperty("devcli.windows.sandbox.read.roots", runtime.toString());
            Files.writeString(workspace.resolve("SandboxCompile.java"), "class SandboxCompile {}\n");
            var result = run("javac -d . SandboxCompile.java", 30);
            assertTrue(result.succeeded(), result.output());
            assertTrue(Files.exists(workspace.resolve("SandboxCompile.class")));
        } finally { restore("devcli.windows.sandbox.read.roots", previous); }
    }

    @Test
    void cancellationStopsRunningTarget() throws Exception {
        requireLauncher();
        var token = new com.devcli.concurrent.CancellationToken();
        var context = com.devcli.tool.ToolExecutionContext.unbounded("native-cancel", token);
        var service = service();
        var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> service.execute(
                new CommandExecutionService.Request("[IO.File]::WriteAllText('running.txt',[string]$PID); Start-Sleep 60",
                        workspace, 30, true, context)));
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(workspace.resolve("running.txt")) && !future.isDone() && System.nanoTime() < deadline)
                Thread.sleep(20);
            assertTrue(Files.exists(workspace.resolve("running.txt")), "target must start before cancellation");
            long pid = Long.parseLong(Files.readString(workspace.resolve("running.txt")));
            token.cancel();
            assertTrue(future.get(15, java.util.concurrent.TimeUnit.SECONDS).cancelled());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally { token.cancel(); }
    }

    @Test
    void rejectsHardLinksBeforeGrantingWorkspaceAccess(@TempDir Path outside) throws Exception {
        requireLauncher();
        Path original = outside.resolve("sentinel.txt");
        Files.writeString(original, "untouched");
        Files.createLink(workspace.resolve("alias.txt"), original);
        assertThrows(IllegalStateException.class, () -> run("Set-Content alias.txt changed", 30));
        assertEquals("untouched", Files.readString(original));
    }

    @Test
    void toolEntryReusesWorkspaceAndPublishesOnlyThroughPatchSet() throws Exception {
        requireLauncher();
        String mode = System.getProperty("devcli.command.sandbox.mode");
        java.util.concurrent.atomic.AtomicInteger approvals = new java.util.concurrent.atomic.AtomicInteger();
        var handler = new com.devcli.hitl.HitlHandler() {
            public com.devcli.hitl.ApprovalResult requestApproval(com.devcli.hitl.ApprovalRequest request) {
                approvals.incrementAndGet();
                return com.devcli.hitl.ApprovalResult.approve();
            }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
        };
        try {
            System.setProperty("devcli.command.sandbox.mode", "WINDOWS_NATIVE");
            try (var parent = new com.devcli.hitl.HitlToolRegistry(handler)) {
                parent.setProjectPath(workspace.toString());
                try (var session = com.devcli.workspace.WorkspaceExecutionSession.open(parent, "native-test",
                        java.util.List.of("artifact.txt"))) {
                    var output = session.toolRegistry().runWithToolAccess(com.devcli.tool.ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                            () -> session.toolRegistry().executeToolOutput("execute_command",
                                    "{\"command\":\"Set-Content -NoNewline -LiteralPath artifact.txt -Value verified\"}"));
                    assertEquals("SUCCESS", output.status().name(), output.text());
                    assertTrue(approvals.get() > 0, "Native isolation must not bypass existing approval");
                    assertFalse(Files.exists(workspace.resolve("artifact.txt")));
                    var applied = session.apply(session.patchSet());
                    assertTrue(applied.applied(), applied.toString());
                    assertEquals("verified", Files.readString(workspace.resolve("artifact.txt")));
                }
            }
        } finally { restore("devcli.command.sandbox.mode", mode); }
    }
}
