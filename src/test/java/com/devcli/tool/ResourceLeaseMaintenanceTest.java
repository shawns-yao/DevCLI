package com.devcli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceLeaseMaintenanceTest {

    @Test
    void periodicallyPrunesExpiredLeases(@TempDir Path tempDir) throws Exception {
        ResourceLeaseManager manager = new ResourceLeaseManager(20);
        ResourceLeaseMaintenance maintenance =
                new ResourceLeaseMaintenance(Duration.ofMillis(10));
        try (ResourceLeaseMaintenance.Registration ignored = maintenance.attach(manager)) {
            manager.acquireWrite("step", tempDir.resolve("file.txt"));

            awaitCondition(() -> manager.leaseCount() == 0, 3_000);

            assertEquals(0, manager.leaseCount());
        }
        assertTrue(maintenance.isClosed());
        assertTrue(maintenance.isTerminated());
    }

    @Test
    void projectForkSharesCleanerAndLastCloseStopsIt(@TempDir Path project) {
        ToolRegistry root = new ToolRegistry();
        ToolRegistry fork = root.forkForProject(project);
        ResourceLeaseMaintenance maintenance = root.resourceLeaseMaintenance();

        assertSame(maintenance, fork.resourceLeaseMaintenance());
        assertEquals(2, maintenance.registrationCount());
        assertEquals(2, maintenance.managerCount());

        root.close();
        assertFalse(maintenance.isClosed());
        assertEquals(1, maintenance.registrationCount());

        fork.close();
        assertTrue(maintenance.isClosed());
        assertTrue(maintenance.isTerminated());
    }

    @Test
    void concurrentRegistrationsDoNotCreateAdditionalSchedulers() throws Exception {
        ResourceLeaseMaintenance maintenance =
                new ResourceLeaseMaintenance(Duration.ofSeconds(1));
        ResourceLeaseManager anchor = new ResourceLeaseManager();
        ResourceLeaseMaintenance.Registration anchorRegistration = maintenance.attach(anchor);
        int workers = 12;
        var pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        try {
            for (int index = 0; index < workers; index++) {
                pool.submit(() -> {
                    await(start);
                    ResourceLeaseManager manager = new ResourceLeaseManager();
                    try (ResourceLeaseMaintenance.Registration ignored = maintenance.attach(manager)) {
                        assertTrue(maintenance.managerCount() >= 1);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(1, maintenance.registrationCount());
            assertEquals(1, maintenance.managerCount());
        } finally {
            anchorRegistration.close();
            pool.shutdownNow();
        }
        assertTrue(maintenance.isTerminated());
    }

    @Test
    void explicitCloseKeepsLateRegistrationCloseIdempotent() {
        ResourceLeaseMaintenance maintenance =
                new ResourceLeaseMaintenance(Duration.ofSeconds(1));
        ResourceLeaseMaintenance.Registration registration =
                maintenance.attach(new ResourceLeaseManager());

        maintenance.close();
        registration.close();
        registration.close();

        assertEquals(0, maintenance.registrationCount());
        assertEquals(0, maintenance.managerCount());
        assertTrue(maintenance.isTerminated());
    }

    @Test
    void cleanupIntervalUsesPropertyBeforeEnvironment() {
        Properties properties = new Properties();
        properties.setProperty(ResourceLeaseMaintenance.INTERVAL_PROPERTY, "7");

        assertEquals(Duration.ofSeconds(7), ResourceLeaseMaintenance.resolveInterval(
                properties, Map.of(ResourceLeaseMaintenance.INTERVAL_ENV, "11")));
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition,
                                       long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
