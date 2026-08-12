package com.devcli.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** 按 provider、完整 model 名称和生效时间匹配的价格目录。 */
public final class PricingCatalog {
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private final List<Price> prices;

    public PricingCatalog(List<Price> prices) {
        this.prices = prices == null ? List.of() : prices.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Price::effectiveAt))
                .toList();
    }

    public static PricingCatalog empty() {
        return new PricingCatalog(List.of());
    }

    public Cost estimate(String provider, String model, long inputTokens,
                         long cachedInputTokens, long outputTokens, Instant at) {
        Instant effectiveAt = at == null ? Instant.now() : at;
        Optional<Price> matched = prices.stream()
                .filter(price -> price.matches(provider, model, effectiveAt))
                .max(Comparator.comparing(Price::effectiveAt));
        if (matched.isEmpty()) return Cost.unknown();

        Price price = matched.get();
        long input = Math.max(0, inputTokens);
        long cached = Math.max(0, Math.min(input, cachedInputTokens));
        long uncached = input - cached;
        BigDecimal amount = perMillion(uncached, price.uncachedInputPerMillion())
                .add(perMillion(cached, price.cachedInputPerMillion()))
                .add(perMillion(Math.max(0, outputTokens), price.outputPerMillion()))
                .setScale(6, RoundingMode.HALF_UP);
        return new Cost(true, amount, price.currency(), price.effectiveAt());
    }

    private static BigDecimal perMillion(long tokens, BigDecimal rate) {
        return BigDecimal.valueOf(tokens).multiply(rate).divide(MILLION, 12, RoundingMode.HALF_UP);
    }

    public record Price(String provider, String model, Instant effectiveAt, String currency,
                        BigDecimal uncachedInputPerMillion,
                        BigDecimal cachedInputPerMillion,
                        BigDecimal outputPerMillion) {
        public Price {
            provider = normalize(provider);
            model = normalize(model);
            effectiveAt = effectiveAt == null ? Instant.EPOCH : effectiveAt;
            currency = currency == null || currency.isBlank()
                    ? "UNKNOWN" : currency.trim().toUpperCase(Locale.ROOT);
            uncachedInputPerMillion = nonNegative(uncachedInputPerMillion);
            cachedInputPerMillion = nonNegative(cachedInputPerMillion);
            outputPerMillion = nonNegative(outputPerMillion);
            if (provider.isBlank() || model.isBlank()) {
                throw new IllegalArgumentException("provider and model are required");
            }
        }

        boolean matches(String candidateProvider, String candidateModel, Instant at) {
            return provider.equals(normalize(candidateProvider))
                    && model.equals(normalize(candidateModel))
                    && !effectiveAt.isAfter(at);
        }
    }

    public record Cost(boolean known, BigDecimal amount, String currency, Instant effectiveAt) {
        public Cost {
            amount = amount == null ? BigDecimal.ZERO.setScale(6) : amount.setScale(6, RoundingMode.HALF_UP);
            currency = currency == null || currency.isBlank() ? "unknown" : currency;
            effectiveAt = effectiveAt == null ? Instant.EPOCH : effectiveAt;
        }

        public static Cost unknown() {
            return new Cost(false, BigDecimal.ZERO, "unknown", Instant.EPOCH);
        }

        public String display() {
            return known ? currency + " " + amount.toPlainString() : "cost=unknown";
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("price must be non-negative");
        }
        return value;
    }
}
