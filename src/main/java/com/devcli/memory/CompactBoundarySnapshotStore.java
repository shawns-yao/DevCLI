package com.devcli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Atomic local persistence for the latest compaction boundary snapshot. */
public final class CompactBoundarySnapshotStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;

    public CompactBoundarySnapshotStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public Path file() {
        return file;
    }

    public synchronized void save(CompactBoundarySnapshot snapshot) throws IOException {
        if (snapshot == null) return;
        if (!snapshot.checksumValid()) throw new IOException("Invalid compaction boundary checksum");
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(file.getParent(), "compact-boundary-", ".tmp");
        try {
            JSON.writeValue(tmp.toFile(), snapshot);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public synchronized Optional<CompactBoundarySnapshot> load() throws IOException {
        if (!Files.isRegularFile(file)) return Optional.empty();
        CompactBoundarySnapshot snapshot = JSON.readValue(file.toFile(), CompactBoundarySnapshot.class);
        if (!snapshot.checksumValid()) throw new IOException("Invalid compaction boundary checksum");
        return Optional.of(snapshot);
    }

    public synchronized Optional<CompactBoundarySnapshot> load(CompactionContext context)
            throws IOException {
        return load().filter(snapshot -> snapshot.matches(context));
    }
}
