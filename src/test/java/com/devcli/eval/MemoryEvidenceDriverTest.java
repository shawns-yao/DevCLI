package com.devcli.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MemoryEvidenceDriverTest {
    @TempDir Path dir;

    @Test void retrievesThroughProductionManagerAndTracksActualInjection() throws Exception {
        var json = new ObjectMapper();
        var job = json.createObjectNode().put("id", "case-1").put("question", "compiler version")
                .put("clock_anchor", Instant.now().toString());
        var sessions = job.putArray("sessions");
        sessions.addObject().put("id", "evidence").put("content", "The compiler version is Java 17.").put("age_seconds", 100);
        sessions.addObject().put("id", "noise").put("content", "An unrelated session about holidays.").put("age_seconds", 0);
        var result = MemoryEvidenceDriver.retrieve(job, dir);
        assertEquals("evidence", result.path("ranked_ids").get(0).asText());
        assertEquals("noise", result.path("recency_ranked_ids").get(0).asText());
        assertTrue(result.path("memory_context").asText().contains("Java 17"));
        assertEquals(1, result.path("injected_ids").size());
        assertEquals(2, result.path("candidate_sessions").asInt());
    }

    @Test void budgetExclusionDoesNotPretendEvidenceWasInjected() throws Exception {
        var job = new ObjectMapper().createObjectNode().put("id", "large").put("question", "compiler version")
                .put("clock_anchor", Instant.now().toString());
        job.putArray("sessions").addObject().put("id", "large-evidence")
                .put("content", "compiler version ".repeat(10000)).put("age_seconds", 0);
        var result = MemoryEvidenceDriver.retrieve(job, dir);
        assertEquals(1, result.path("ranked_ids").size());
        assertEquals(0, result.path("injected_ids").size());
        assertEquals("", result.path("memory_context").asText());
    }
}
