package com.devcli.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Trusted launcher bridge. No command is executed if AppContainer setup fails. */
public final class WindowsSandboxProcess extends Process {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Process broker;
    private final Path control;
    private volatile boolean timedOut;

    private WindowsSandboxProcess(Process broker, Path control) {
        this.broker = broker;
        this.control = control;
    }

    public static WindowsSandboxProcess start(List<String> command, Path workspace, boolean writable,
                                               long timeoutSeconds, BooleanSupplier cancelled) throws IOException {
        return start(command, workspace, writable, timeoutSeconds, cancelled, false);
    }

    public static WindowsSandboxProcess start(List<String> command, Path workspace, boolean writable,
                                               long timeoutSeconds, BooleanSupplier cancelled,
                                               boolean mergeError) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
            throw new IOException("WINDOWS_NATIVE requires Windows");
        String configured = setting("devcli.windows.sandbox.launcher", "DEVCLI_WINDOWS_SANDBOX_LAUNCHER");
        if (configured.isBlank()) throw new IOException("Configure DEVCLI_WINDOWS_SANDBOX_LAUNCHER; no host fallback");
        Path launcher = Path.of(configured);
        Path root = workspace.toRealPath();
        if (!launcher.isAbsolute() || !Files.isRegularFile(launcher)
                || !launcher.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe"))
            throw new IOException("Windows sandbox launcher must be an existing absolute .exe");
        launcher = launcher.toRealPath();
        if (writable && launcher.startsWith(root)) throw new IOException("Sandbox launcher must be outside the writable workspace");
        if (command.isEmpty() || !Path.of(command.get(0)).isAbsolute())
            throw new IOException("Sandbox executable must be absolute");
        String roots = setting("devcli.windows.sandbox.read.roots", "DEVCLI_WINDOWS_SANDBOX_READ_ROOTS");
        List<String> readRoots = roots.isBlank() ? List.of() : Arrays.stream(roots.split(";", -1))
                .map(String::trim).toList();
        for (String entry : readRoots)
            if (entry.isBlank() || !Path.of(entry).isAbsolute() || !Files.isDirectory(Path.of(entry)))
                throw new IOException("Sandbox read roots must be existing absolute directories");
        Path directory = Files.createTempDirectory("devcli-sandbox-control-");
        Path control = directory.resolve("request.json");
        WindowsSandboxProcess managed = null;
        try {
            JSON.writeValue(control.toFile(), Map.of("protocol", 1, "workspace", root.toString(),
                    "writable", writable, "command", command, "readRoots", readRoots,
                    "parentPid", ProcessHandle.current().pid(), "timeoutSeconds", Math.min(timeoutSeconds, Integer.MAX_VALUE)));
            ProcessBuilder builder = new ProcessBuilder(launcher.toString(), control.toString());
            builder.redirectErrorStream(mergeError);
            // The broker also receives no model credentials or runtime injection options.
            builder.environment().clear();
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot != null) builder.environment().put("SystemRoot", systemRoot);
            managed = new WindowsSandboxProcess(builder.start(), control);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.min(30, timeoutSeconds));
            while (!Files.exists(Path.of(control + ".ready"))) {
                if (Files.exists(Path.of(control + ".error")))
                    throw new IOException(Files.readString(Path.of(control + ".error")));
                if (!managed.isAlive()) throw new IOException("Windows sandbox launcher exited before readiness");
                if (cancelled.getAsBoolean() || System.nanoTime() >= deadline)
                    throw new IOException("Windows sandbox startup cancelled or timed out");
                Thread.sleep(20);
            }
            return managed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (managed != null) managed.destroyForcibly();
            cleanup(control);
            throw new IOException("Windows sandbox startup interrupted", e);
        } catch (IOException | RuntimeException e) {
            if (managed != null) managed.destroyForcibly();
            cleanup(control);
            throw e;
        }
    }

    public static String setting(String property, String environment) {
        String value = System.getProperty(property);
        return value == null ? System.getenv().getOrDefault(environment, "").trim() : value.trim();
    }

    @Override public OutputStream getOutputStream() { return broker.getOutputStream(); }
    @Override public InputStream getInputStream() { return broker.getInputStream(); }
    @Override public InputStream getErrorStream() { return broker.getErrorStream(); }
    @Override public boolean isAlive() { return broker.isAlive(); }
    @Override public long pid() { return broker.pid(); }
    @Override public ProcessHandle toHandle() { return broker.toHandle(); }
    @Override public int exitValue() { return broker.exitValue(); }
    public boolean timedOut() { return timedOut; }
    @Override public int waitFor() throws InterruptedException {
        int result = broker.waitFor();
        cleanupControl();
        return result;
    }
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        boolean exited = broker.waitFor(timeout, unit);
        if (exited) {
            cleanupControl();
        }
        return exited;
    }
    @Override public void destroy() { destroyForcibly(); }
    @Override public synchronized Process destroyForcibly() {
        boolean interrupted = Thread.interrupted();
        try {
            if (broker.isAlive()) {
                Files.writeString(Path.of(control + ".stop"), "stop");
                if (!broker.waitFor(8, TimeUnit.SECONDS)) broker.destroyForcibly();
                if (!broker.waitFor(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("Windows sandbox broker did not stop; reject artifacts");
            }
        } catch (IOException e) {
            broker.destroyForcibly();
        } catch (InterruptedException e) {
            interrupted = true;
            broker.destroyForcibly();
        } finally {
            if (!broker.isAlive()) cleanupControl();
            if (interrupted) Thread.currentThread().interrupt();
        }
        return this;
    }

    private synchronized void cleanupControl() {
        timedOut |= Files.exists(Path.of(control + ".timeout"));
        cleanup(control);
    }

    private static void cleanup(Path control) {
        try {
            for (String suffix : List.of(".ready", ".error", ".stop", ".timeout", ""))
                Files.deleteIfExists(Path.of(control + suffix));
            Files.deleteIfExists(control.getParent());
        } catch (IOException e) {
            System.getLogger(WindowsSandboxProcess.class.getName()).log(System.Logger.Level.WARNING,
                    "Sandbox control files could not be cleaned up");
        }
    }
}
