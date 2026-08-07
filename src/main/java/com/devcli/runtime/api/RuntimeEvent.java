package com.devcli.runtime.api;

import java.time.Instant;

public record RuntimeEvent(long id, String threadId, String branchId,
                           String type, String data, Instant createdAt) {
    public RuntimeEvent(long id, String threadId, String type, String data, Instant createdAt) {
        this(id, threadId, "main", type, data, createdAt);
    }
}
