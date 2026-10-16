package com.devcli.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MainTeamResumeParseTest {

    @Test
    void bareResumeMeansLatestCheckpoint() {
        assertEquals("", CliPlanReview.parsePlanResumeId("resume"));
        assertEquals("", CliPlanReview.parsePlanResumeId("  RESUME  "));
    }

    @Test
    void resumeWithIdReturnsId() {
        assertEquals("orch-1a2b3c4d", CliPlanReview.parsePlanResumeId("resume orch-1a2b3c4d"));
        assertEquals("orch-1a2b3c4d", CliPlanReview.parsePlanResumeId("Resume   orch-1a2b3c4d "));
    }

    @Test
    void normalTaskTextIsNotResume() {
        assertNull(CliPlanReview.parsePlanResumeId("修复登录模块的空指针问题"));
        assertNull(CliPlanReview.parsePlanResumeId("resumeX 不是子命令"));
        assertNull(CliPlanReview.parsePlanResumeId(null));
        assertNull(CliPlanReview.parsePlanResumeId(""));
    }
}
