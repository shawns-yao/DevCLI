package com.devcli.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

final class PublicBenchmarkCatalog {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PublicBenchmarkCatalog() {
    }

    static Catalog load(Path projectRoot) throws IOException {
        Path root = normalizeRoot(projectRoot);
        Path config = root.resolve("Config/public-benchmarks.json");
        CatalogFile file = JSON.readValue(config.toFile(), CatalogFile.class);
        if (file.schemaVersion() != 1) {
            throw new IOException("unsupported public benchmark catalog schema: " + file.schemaVersion());
        }
        return new Catalog(root, file.datasets());
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path normalizeRoot(Path projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot is required");
        }
        return projectRoot.toAbsolutePath().normalize();
    }

    record Catalog(Path projectRoot, List<DatasetDescriptor> datasets) {
        Catalog {
            projectRoot = normalizeRoot(projectRoot);
            datasets = datasets == null ? List.of() : List.copyOf(datasets);
        }

        DatasetDescriptor require(String id) {
            return datasets.stream()
                    .filter(dataset -> dataset.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown public benchmark dataset: " + id));
        }

        Path resolveArtifact(DatasetDescriptor descriptor) {
            return resolveInsideRoot(descriptor.artifact());
        }

        Path resolveExtractedRoot(DatasetDescriptor descriptor) {
            if (descriptor.extractedRoot() == null || descriptor.extractedRoot().isBlank()) {
                return null;
            }
            return resolveInsideRoot(descriptor.extractedRoot());
        }

        Path resolveHarness(DatasetDescriptor descriptor) {
            return resolveInsideRoot(descriptor.officialHarness());
        }

        DatasetValidation validate(DatasetDescriptor descriptor) throws Exception {
            Path artifact = resolveArtifact(descriptor);
            if (!Files.isRegularFile(artifact)) {
                return new DatasetValidation(descriptor.id(), artifact, false, false, 0, "artifact missing");
            }
            String actualHash = sha256(artifact);
            boolean hashMatches = actualHash.equalsIgnoreCase(descriptor.artifactSha256());
            Path harness = resolveHarness(descriptor);
            boolean harnessPresent = Files.isDirectory(harness) || Files.isRegularFile(harness);
            String message = hashMatches && harnessPresent
                    ? "ready"
                    : (!hashMatches ? "sha256 mismatch" : "official harness missing");
            return new DatasetValidation(descriptor.id(), artifact, hashMatches, harnessPresent,
                    Files.size(artifact), message);
        }

        private Path resolveInsideRoot(String relativePath) {
            if (relativePath == null || relativePath.isBlank()) {
                throw new IllegalArgumentException("catalog path is required");
            }
            Path relative = Path.of(relativePath);
            if (relative.isAbsolute()) {
                throw new IllegalArgumentException("catalog path must be relative: " + relativePath);
            }
            Path resolved = projectRoot.resolve(relative).normalize();
            if (resolved.equals(projectRoot) || !resolved.startsWith(projectRoot)) {
                throw new IllegalArgumentException("catalog path escapes project root: " + relativePath);
            }
            return resolved;
        }
    }

    record CatalogFile(int schemaVersion, String updatedAt, List<DatasetDescriptor> datasets) {
    }

    record DatasetDescriptor(String id, String displayName, String category,
                             String datasetRepository, String datasetRevision,
                             String license, String artifact, String artifactUrl,
                             String artifactSha256, String sampleUrl, String extractedRoot,
                             String officialHarness, String evaluationMode) {
    }

    record DatasetValidation(String id, Path artifact, boolean hashMatches,
                             boolean harnessPresent, long bytes, String message) {
        boolean ready() {
            return hashMatches && harnessPresent;
        }
    }
}
