package com.devcli.budget;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetLedgerTest {

    @Test
    void concurrentWorkersShareOneAtomicRequestLimit() throws Exception {
        RunBudget budget = RunBudget.create("run_team", RunBudgetPolicy.builder()
                .maxLlmCalls(1)
                .maxTotalTokens(10_000)
                .build(), PricingCatalog.empty());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();

        try {
            for (int worker = 0; worker < 2; worker++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    RunBudget.Admission admission = budget.tryStartLlmRequest(
                            "worker", "worker", "attempt-1");
                    if (admission.allowed()) {
                        admitted.incrementAndGet();
                    }
                    return null;
                });
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }

        assertEquals(1, admitted.get());
        assertEquals(1, budget.snapshot().llmCalls());
        assertEquals(RunBudget.Decision.HARD_STOP, budget.snapshot().decision());
    }

    @Test
    void retryAdmissionUsesTheSameCallCounter() {
        RunBudget budget = RunBudget.create("run_retry", RunBudgetPolicy.builder()
                .maxLlmCalls(2)
                .maxTotalTokens(10_000)
                .build(), PricingCatalog.empty());

        assertTrue(budget.tryStartLlmRequest("react", "agent", "attempt-1").allowed());
        assertTrue(budget.tryStartLlmRequest("react", "agent", "attempt-2").allowed());
        assertFalse(budget.tryStartLlmRequest("react", "agent", "attempt-3").allowed());
        assertEquals(2, budget.snapshot().llmCalls());
    }

    @Test
    void tokenReservationsPreventParallelWorkersFromJointlyExceedingTheLimit() throws Exception {
        RunBudget budget = RunBudget.create("run_tokens", RunBudgetPolicy.builder()
                .maxLlmCalls(4)
                .maxTotalTokens(100)
                .build(), PricingCatalog.empty());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            for (int worker = 0; worker < 2; worker++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    RunBudget.Admission admission = budget.tryStartLlmRequest(
                            "worker", "worker", "attempt-1", 60);
                    if (admission.allowed()) {
                        budget.recordLlmUsage(admission, "unknown", "model", 60, 0, 0);
                    }
                    return null;
                });
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }

        assertEquals(60, budget.snapshot().totalTokens());
        assertEquals(1, budget.snapshot().llmCalls());
        assertEquals(0, budget.snapshot().reservedTokens());
    }

    @Test
    void costAccumulatesPerCallWithoutRepricingPreviousUsage() {
        PricingCatalog catalog = new PricingCatalog(java.util.List.of(
                new PricingCatalog.Price("provider", "model", java.time.Instant.EPOCH, "CNY",
                        new java.math.BigDecimal("1"), java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO)));
        RunBudget budget = RunBudget.create("run_cost", RunBudgetPolicy.builder()
                .maxLlmCalls(3).maxTotalTokens(3_000_000).build(), catalog);

        RunBudget.Admission first = budget.tryStartLlmRequest("react", "agent", "attempt-1");
        budget.recordLlmUsage(first, "provider", "model", 1_000_000, 0, 0);
        RunBudget.Admission second = budget.tryStartLlmRequest("react", "agent", "attempt-2");
        budget.recordLlmUsage(second, "provider", "model", 1_000_000, 0, 0);

        assertEquals(new java.math.BigDecimal("2.000000"), budget.snapshot().estimatedCost());
    }
}
