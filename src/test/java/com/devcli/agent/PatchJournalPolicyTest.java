package com.devcli.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchJournalPolicyTest {

    @Test
    void deletesOnlyExpiredOrphanJournals(@TempDir Path checkpointDir) throws Exception {
        Instant now = Instant.parse("2026-07-13T12:00:00Z");
        Path orphan = Files.createDirectory(checkpointDir.resolve("orphan.patch-journal"));
        Path recoverable = Files.createDirectory(checkpointDir.resolve("recoverable.patch-journal"));
        Path recent = Files.createDirectory(checkpointDir.resolve("recent.patch-journal"));
        Files.writeString(checkpointDir.resolve("recoverable.json"), "{}");
        Files.setLastModifiedTime(orphan, FileTime.from(now.minus(Duration.ofHours(25))));
        Files.setLastModifiedTime(recoverable, FileTime.from(now.minus(Duration.ofHours(25))));
        Files.setLastModifiedTime(recent, FileTime.from(now.minus(Duration.ofHours(2))));

        PatchJournalPolicy.pruneOrphans(checkpointDir, Duration.ofHours(24), now);

        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(recoverable));
        assertTrue(Files.exists(recent));
    }

    @Test
    void systemPropertyOverridesEnvironmentTtl() {
        assertEquals(Duration.ofHours(3), PatchJournalPolicy.resolveTtl(
                Map.of(PatchJournalPolicy.TTL_PROPERTY, "3"),
                Map.of(PatchJournalPolicy.TTL_ENV, "9")));
    }

    @Test
    void restrictsJournalPermissionsWhenPosixIsAvailable(@TempDir Path tempDir) throws Exception {
        Path journal = tempDir.resolve("journal");
        PatchJournalPolicy.secureDirectory(journal);
        Path backup = Files.writeString(journal.resolve("backup.txt"), "secret");
        PatchJournalPolicy.secureFile(backup);

        if (Files.getFileStore(journal).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(journal));
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(backup));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(
                    backup, AclFileAttributeView.class);
            assertEquals(1, acl.getAcl().size());
            assertEquals(Files.getOwner(backup), acl.getAcl().get(0).principal());
        }
    }
}
