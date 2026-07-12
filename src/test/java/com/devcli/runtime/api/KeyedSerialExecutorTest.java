package com.devcli.runtime.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyedSerialExecutorTest {

    @Test
    void serializesTasksWithSameKeyInSubmissionOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            KeyedSerialExecutor executor = new KeyedSerialExecutor(pool, 8);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            List<Integer> order = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(3);

            for (int i = 1; i <= 3; i++) {
                int value = i;
                executor.execute("thread-1", () -> {
                    int current = active.incrementAndGet();
                    maxActive.accumulateAndGet(current, Math::max);
                    order.add(value);
                    active.decrementAndGet();
                    done.countDown();
                });
            }

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals(1, maxActive.get());
            assertEquals(List.of(1, 2, 3), order);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void allowsDifferentKeysToRunConcurrently() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            KeyedSerialExecutor executor = new KeyedSerialExecutor(pool, 8);
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);

            executor.execute("thread-1", () -> await(started, release, done));
            executor.execute("thread-2", () -> await(started, release, done));

            assertTrue(started.await(3, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(done.await(3, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch started, CountDownLatch release, CountDownLatch done) {
        started.countDown();
        try {
            release.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            done.countDown();
        }
    }
}
