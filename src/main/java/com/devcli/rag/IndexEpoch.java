package com.devcli.rag;

import java.time.Instant;
import java.util.UUID;

/**
 * Version marker for one complete code index rebuild.
 */
public record IndexEpoch(String value) {
    public static IndexEpoch none() {
        return new IndexEpoch("none");
    }

    public static IndexEpoch next() {
        return new IndexEpoch("idx_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8));
    }
}
