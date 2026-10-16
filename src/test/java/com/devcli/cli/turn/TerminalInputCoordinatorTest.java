package com.devcli.cli.turn;

import com.devcli.render.inline.FixedInputLineReader;
import org.jline.reader.LineReader;
import org.jline.reader.Widget;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class TerminalInputCoordinatorTest {
    @Test
    void completionWakesReaderAndPreservesUnsubmittedDraft() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicBoolean completed = new AtomicBoolean();
            f.coordinator.begin(completed::get);
            f.onInit(() -> f.worker.submit(() -> {
                completed.set(true);
                f.coordinator.wake();
            }));
            var input = f.coordinator.read("> ", "", "未发送的草稿");
            assertTrue(input.suspended());
            assertEquals("未发送的草稿", input.text());
        }
    }

    @Test
    void approvalRunsOnInputThreadThenRestoresEditableDraft() throws Exception {
        try (Fixture f = new Fixture()) {
            f.coordinator.begin(() -> false);
            Thread inputOwner = Thread.currentThread();
            AtomicReference<Future<String>> answer = new AtomicReference<>();
            f.onInit(() -> answer.set(f.worker.submit(() -> f.coordinator.interact(() -> {
                assertSame(inputOwner, Thread.currentThread());
                return f.reader.readLine("审批> ");
            }))));
            var suspended = f.coordinator.read("> ", "", "keep this draft");
            assertTrue(suspended.suspended());
            assertEquals("keep this draft", suspended.text());
            f.send("y\n");
            f.coordinator.serviceInteractions();
            assertEquals("y", answer.get().get(2, TimeUnit.SECONDS));
            f.send(" and send\n");
            var submitted = f.coordinator.read("> ", "", suspended.text());
            assertFalse(submitted.suspended());
            assertEquals("keep this draft and send", submitted.text());
        }
    }

    @Test
    void completionBeforeReadDoesNotStartOrLoseInput() throws Exception {
        try (Fixture f = new Fixture()) {
            f.coordinator.begin(() -> true);
            assertEquals(new TerminalInputCoordinator.Input("draft", true),
                    f.coordinator.read("> ", "", "draft"));
            assertFalse(f.reader.isReading());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final PipedOutputStream keyboard = new PipedOutputStream();
        final PipedInputStream input = new PipedInputStream(keyboard);
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final Terminal terminal;
        final FixedInputLineReader reader;
        final TerminalInputCoordinator coordinator;
        Fixture() throws Exception {
            terminal = TerminalBuilder.builder().system(false).type("xterm-256color")
                    .streams(input, new ByteArrayOutputStream()).encoding(StandardCharsets.UTF_8)
                    .size(new Size(80, 24)).build();
            reader = new FixedInputLineReader(terminal);
            reader.view().start();
            coordinator = new TerminalInputCoordinator(terminal, reader);
        }
        void onInit(Runnable once) {
            Widget previous = reader.getWidgets().get(LineReader.CALLBACK_INIT);
            AtomicBoolean invoked = new AtomicBoolean();
            reader.getWidgets().put(LineReader.CALLBACK_INIT, () -> {
                if (previous != null) previous.apply();
                if (invoked.compareAndSet(false, true)) once.run();
                return true;
            });
        }
        void send(String text) throws Exception {
            keyboard.write(text.getBytes(StandardCharsets.UTF_8));
            keyboard.flush();
        }
        public void close() throws Exception {
            coordinator.end();
            worker.shutdownNow();
            keyboard.close();
            terminal.close();
        }
    }
}
