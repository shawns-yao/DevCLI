package com.devcli.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable version marker for one indexed code symbol.
 */
public record SymbolVersion(String value) {
    public static SymbolVersion none() {
        return new SymbolVersion("none");
    }

    public static SymbolVersion from(String filePath,
                                     String chunkType,
                                     String name,
                                     String content,
                                     String classpathEpoch) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, filePath);
            update(digest, chunkType);
            update(digest, name);
            update(digest, content);
            update(digest, classpathEpoch);
            return new SymbolVersion("sv_" + HexFormat.of().formatHex(digest.digest()).substring(0, 16));
        } catch (NoSuchAlgorithmException e) {
            return none();
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
