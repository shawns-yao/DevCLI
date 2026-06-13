package com.devcli.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MainTeamResumeParseTest {

    @Test
    void bareResumeMeansLatestCheckpoint() {
        assertEquals("", Main.parseTeamResumeId("resume"));
        assertEquals("", Main.parseTeamResumeId("  RESUME  "));
    }

    @Test
    void resumeWithIdReturnsId() {
        assertEquals("orch-1a2b3c4d", Main.parseTeamResumeId("resume orch-1a2b3c4d"));
        assertEquals("orch-1a2b3c4d", Main.parseTeamResumeId("Resume   orch-1a2b3c4d "));
    }

    @Test
    void normalTaskTextIsNotResume() {
        assertNull(Main.parseTeamResumeId("修复登录模块的空指针问题"));
        assertNull(Main.parseTeamResumeId("resumeX 不是子命令"));
        assertNull(Main.parseTeamResumeId(null));
        assertNull(Main.parseTeamResumeId(""));
    }
}
