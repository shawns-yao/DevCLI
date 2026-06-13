package com.devcli.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Lightweight classpath version derived from build descriptor content.
 */
public record ClasspathEpoch(String value) {
    private static final List<String> BUILD_FILES = List.of("pom.xml", "build.gradle", "build.gradle.kts");

    public static ClasspathEpoch none() {
        return new ClasspathEpoch("none");
    }

    public static ClasspathEpoch detect(Path anyProjectPath) {
        if (anyProjectPath == null) {
            return none();
        }
        Path start = Files.isDirectory(anyProjectPath) ? anyProjectPath : anyProjectPath.getParent();
        Path root = findRoot(start);
        if (root == null) {
            return none();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            boolean found = false;
            for (String buildFile : BUILD_FILES) {
                Path path = root.resolve(buildFile);
                if (Files.isRegularFile(path)) {
                    found = true;
                    digest.update(buildFile.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(Files.readAllBytes(path));
                    digest.update((byte) 0);
                }
            }
            if (!found) {
                return none();
            }
            return new ClasspathEpoch(HexFormat.of().formatHex(digest.digest()).substring(0, 16));
        } catch (IOException | NoSuchAlgorithmException e) {
            return none();
        }
    }

    private static Path findRoot(Path start) {
        Path current = start == null ? null : start.toAbsolutePath().normalize();
        while (current != null) {
            for (String buildFile : BUILD_FILES) {
                if (Files.isRegularFile(current.resolve(buildFile))) {
                    return current;
                }
            }
            current = current.getParent();
        }
        return null;
    }
}
