package com.devcli.budget;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingCatalogTest {

    @Test
    void unknownModelReturnsUnknownInsteadOfProviderFallback() {
        PricingCatalog catalog = new PricingCatalog(List.of(
                new PricingCatalog.Price("deepseek", "deepseek-v4-flash",
                        Instant.parse("2026-01-01T00:00:00Z"), "CNY",
                        new BigDecimal("2"), new BigDecimal("0.5"), new BigDecimal("8"))));

        PricingCatalog.Cost cost = catalog.estimate(
                "deepseek", "unlisted-model", 1_000_000, 0, 0,
                Instant.parse("2026-08-12T00:00:00Z"));

        assertFalse(cost.known());
        assertEquals("cost=unknown", cost.display());
    }

    @Test
    void choosesLatestEffectiveModelPriceAndSeparatesCachedInput() {
        PricingCatalog catalog = new PricingCatalog(List.of(
                new PricingCatalog.Price("provider", "model",
                        Instant.parse("2026-01-01T00:00:00Z"), "CNY",
                        new BigDecimal("2"), new BigDecimal("0.5"), new BigDecimal("8")),
                new PricingCatalog.Price("provider", "model",
                        Instant.parse("2026-08-01T00:00:00Z"), "CNY",
                        new BigDecimal("4"), new BigDecimal("1"), new BigDecimal("10"))));

        PricingCatalog.Cost cost = catalog.estimate(
                "provider", "model", 1_000_000, 250_000, 500_000,
                Instant.parse("2026-08-12T00:00:00Z"));

        assertTrue(cost.known());
        assertEquals(new BigDecimal("8.250000"), cost.amount());
        assertEquals("CNY", cost.currency());
    }
}
