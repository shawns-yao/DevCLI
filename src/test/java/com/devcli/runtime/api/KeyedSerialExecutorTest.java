package com.devcli.runtime.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    @Test
    void keepsSameKeySerialAcrossRepeatedLaneRetirement() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(4);
        ExecutorService submitters = Executors.newFixedThreadPool(8);
        try {
            KeyedSerialExecutor executor = new KeyedSerialExecutor(workers, 128);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> producers = new CopyOnWriteArrayList<>();

            for (int producer = 0; producer < 8; producer++) {
                producers.add(submitters.submit(() -> {
                    await(start);
                    for (int iteration = 0; iteration < 500; iteration++) {
                        CountDownLatch completed = new CountDownLatch(1);
                        executor.execute("shared-thread", () -> {
                            int current = active.incrementAndGet();
                            maxActive.accumulateAndGet(current, Math::max);
                            Thread.yield();
                            active.decrementAndGet();
                            completed.countDown();
                        });
                        await(completed);
                    }
                }));
            }

            start.countDown();
            for (Future<?> producer : producers) {
                producer.get(10, TimeUnit.SECONDS);
            }

            assertEquals(1, maxActive.get());
            assertEquals(0, executor.pendingCount());
        } finally {
            submitters.shutdownNow();
            workers.shutdownNow();
        }
    }

    @Test
    void continuesSameKeyLaneAfterTaskFailure() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "keyed-serial-failure-test");
            thread.setUncaughtExceptionHandler((ignored, failure) -> { });
            return thread;
        });
        try {
            KeyedSerialExecutor executor = new KeyedSerialExecutor(pool, 8);
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondDone = new CountDownLatch(1);

            executor.execute("thread-1", () -> {
                firstStarted.countDown();
                await(releaseFirst);
                throw new IllegalStateException("task failed");
            });
            assertTrue(firstStarted.await(3, TimeUnit.SECONDS));

            executor.execute("thread-1", secondDone::countDown);
            releaseFirst.countDown();

            assertTrue(secondDone.await(3, TimeUnit.SECONDS),
                    "单个任务异常不能阻塞同通道后续任务");
            awaitPendingCount(executor, 0);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectsAllTasksWaitingForInitialDelegateScheduling() throws Exception {
        CountDownLatch schedulingEntered = new CountDownLatch(1);
        CountDownLatch releaseScheduling = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            KeyedSerialExecutor executor = new KeyedSerialExecutor(command -> {
                schedulingEntered.countDown();
                await(releaseScheduling);
                throw new RejectedExecutionException("delegate rejected");
            }, 8);

            Future<Throwable> first = callers.submit(() -> captureFailure(
                    () -> executor.execute("thread-1", () -> {
                    })));
            assertTrue(schedulingEntered.await(3, TimeUnit.SECONDS));

            CountDownLatch secondEntered = new CountDownLatch(1);
            Future<Throwable> second = callers.submit(() -> {
                secondEntered.countDown();
                return captureFailure(() -> executor.execute("thread-1", () -> {
                }));
            });
            assertTrue(secondEntered.await(3, TimeUnit.SECONDS));
            awaitPendingCount(executor, 2);

            try {
                assertFalse(second.isDone(), "同通道任务不能在底层调度结果未知时提前报告已接受");
            } finally {
                releaseScheduling.countDown();
            }

            assertInstanceOf(RejectedExecutionException.class, first.get(3, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, second.get(3, TimeUnit.SECONDS));
            assertEquals(0, executor.pendingCount());
        } finally {
            releaseScheduling.countDown();
            callers.shutdownNow();
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

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void awaitPendingCount(KeyedSerialExecutor executor, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (executor.pendingCount() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, executor.pendingCount());
    }

    private static Throwable captureFailure(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }
}
