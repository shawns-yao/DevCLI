package com.devcli.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunContextTest {

    @Test
    void bindsProjectAndCancellationToCurrentRun(@TempDir Path projectRoot) {
        try (RunContext context = CancellationContext.startRunContext(projectRoot)) {
            assertSame(context, CancellationContext.currentRun());
            assertEquals(projectRoot.toAbsolutePath().normalize(), context.projectPath());

            context.cancel();

            assertTrue(CancellationContext.isCancelled());
        }
    }

    @Test
    void closesOwnedResourcesInReverseOrderOnce(@TempDir Path projectRoot) {
        List<Integer> closed = new ArrayList<>();
        AtomicInteger firstCloseCount = new AtomicInteger();
        RunContext context = CancellationContext.startRunContext(projectRoot);
        context.own(() -> {
            closed.add(1);
            firstCloseCount.incrementAndGet();
        });
        context.own(() -> closed.add(2));

        context.close();
        context.close();

        assertEquals(List.of(2, 1), closed);
        assertEquals(1, firstCloseCount.get());
    }
}
