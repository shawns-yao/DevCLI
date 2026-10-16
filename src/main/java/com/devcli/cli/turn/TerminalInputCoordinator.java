package com.devcli.cli.turn;

import org.jline.reader.LineReader;
import org.jline.reader.MaskingCallback;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.terminal.Terminal;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Marshals modal interactions to the one thread that owns terminal input. */
public final class TerminalInputCoordinator {
    private static final String WAKE_WIDGET = "devcli-wake-live-input";
    private final Thread owner = Thread.currentThread();
    private final Terminal terminal;
    private final LineReader reader;
    private final ConcurrentLinkedQueue<Request<?>> pending = new ConcurrentLinkedQueue<>();
    private volatile boolean active;
    private volatile boolean liveRead;
    private volatile BooleanSupplier completed = () -> false;

    public TerminalInputCoordinator(Terminal terminal, LineReader reader) {
        this.terminal = terminal;
        this.reader = reader;
        reader.getWidgets().put(WAKE_WIDGET, () -> {
            if (liveRead && shouldWake()) terminal.raise(Terminal.Signal.INT);
            return true;
        });
        Widget previous = reader.getWidgets().get(LineReader.CALLBACK_INIT);
        reader.getWidgets().put(LineReader.CALLBACK_INIT, () -> {
            boolean result = previous == null || previous.apply();
            if (liveRead && shouldWake()) terminal.raise(Terminal.Signal.INT);
            return result;
        });
    }

    public void begin(BooleanSupplier completed) {
        this.completed = completed;
        active = true;
    }

    public <T> T interact(Supplier<T> interaction) {
        if (Thread.currentThread() == owner) return interaction.get();
        if (!active) throw new CancellationException("终端交互已结束");
        Request<T> request = new Request<>(interaction);
        pending.add(request);
        if (!active) request.result.cancel(false);
        wake();
        try {
            return request.result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            request.result.cancel(false);
            throw new CancellationException("终端交互已取消");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e.getCause());
        } finally {
            pending.remove(request);
        }
    }

    public Input read(String prompt, String rightPrompt, String draft) {
        if (shouldWake()) return new Input(draft, true);
        liveRead = true;
        try {
            return new Input(reader.readLine(prompt, rightPrompt, (MaskingCallback) null, draft), false);
        } catch (UserInterruptException e) {
            if (shouldWake()) return new Input(e.getPartialLine() == null ? "" : e.getPartialLine(), true);
            throw e;
        } finally {
            liveRead = false;
        }
    }

    public void serviceInteractions() {
        Request<?> request;
        while ((request = pending.poll()) != null) request.run();
    }

    public void wake() {
        if (!liveRead) return;
        try {
            reader.callWidget(WAKE_WIDGET);
        } catch (IllegalStateException ignored) {
            // CALLBACK_INIT covers a wake that arrives before readLine owns the terminal.
        }
    }

    public void end() {
        active = false;
        Request<?> request;
        while ((request = pending.poll()) != null) request.result.cancel(false);
    }

    private boolean shouldWake() { return completed.getAsBoolean() || !pending.isEmpty(); }

    public record Input(String text, boolean suspended) { }

    private static final class Request<T> {
        final Supplier<T> interaction;
        final CompletableFuture<T> result = new CompletableFuture<>();
        Request(Supplier<T> interaction) { this.interaction = interaction; }
        void run() {
            if (result.isDone()) return;
            try { result.complete(interaction.get()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        }
    }
}
