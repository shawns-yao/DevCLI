package com.devcli.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationContextTest {

    @Test
    void preExistingWorkerDoesNotInheritAnotherRunsCancellation() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> null).get();

            CancellationToken token = CancellationContext.startRun();
            token.cancel();

            assertNull(executor.submit(CancellationContext::current).get());
            assertFalse(executor.submit(CancellationContext::isCancelled).get());
            CancellationContext.clear(token);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void interruptedThreadIsCancelledWithoutBoundRun() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertTrue(executor.submit(() -> {
                Thread.currentThread().interrupt();
                try {
                    return CancellationContext.isCancelled();
                } finally {
                    Thread.interrupted();
                }
            }).get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void childThreadKeepsRunTokenAfterParentClearsGlobalContext() throws Exception {
        CancellationToken token = CancellationContext.startRun();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch parentCleared = new CountDownLatch(1);
        try {
            Future<Boolean> result = executor.submit(() -> {
                parentCleared.await();
                return CancellationContext.current() == token;
            });

            CancellationContext.clear(token);
            parentCleared.countDown();

            assertTrue(result.get());
        } finally {
            token.cancel();
            CancellationContext.clear(token);
            executor.shutdownNow();
        }
    }
}
