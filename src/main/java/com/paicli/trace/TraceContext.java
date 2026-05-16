package com.paicli.trace;

import java.util.UUID;

public record TraceContext(String traceId, String phase) {
    public static TraceContext root(String phase) {
        return new TraceContext(UUID.randomUUID().toString(), phase);
    }
}
