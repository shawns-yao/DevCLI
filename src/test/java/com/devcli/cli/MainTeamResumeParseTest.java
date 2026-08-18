package com.devcli.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MainTeamResumeParseTest {

    @Test
    void bareResumeMeansLatestCheckpoint() {
        assertEquals("", Main.parsePlanResumeId("resume"));
        assertEquals("", Main.parsePlanResumeId("  RESUME  "));
    }

    @Test
    void resumeWithIdReturnsId() {
        assertEquals("orch-1a2b3c4d", Main.parsePlanResumeId("resume orch-1a2b3c4d"));
        assertEquals("orch-1a2b3c4d", Main.parsePlanResumeId("Resume   orch-1a2b3c4d "));
    }

    @Test
    void normalTaskTextIsNotResume() {
        assertNull(Main.parsePlanResumeId("修复登录模块的空指针问题"));
        assertNull(Main.parsePlanResumeId("resumeX 不是子命令"));
        assertNull(Main.parsePlanResumeId(null));
        assertNull(Main.parsePlanResumeId(""));
    }
}
