package com.devcli.cli.turn;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnExecutionGuardTest {

    @Test
    void distinguishesFutureCancellationFromActualWorkerExit() throws Exception {
        TurnExecutionGuard guard = new TurnExecutionGuard();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = executor.submit(guard.wrap(() -> {
                started.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        // 模拟没有及时响应线程中断的模型调用。
                    }
                }
                return "done";
            }, null));
            assertTrue(started.await(1, TimeUnit.SECONDS));

            future.cancel(true);

            assertFalse(guard.awaitStopped(Duration.ofMillis(20)));
            release.countDown();
            assertTrue(guard.awaitStopped(Duration.ofSeconds(1)));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
