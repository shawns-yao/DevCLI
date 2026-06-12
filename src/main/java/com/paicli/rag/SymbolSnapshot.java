package com.paicli.rag;

/**
 * Indexed symbol identity and version at one index epoch.
 */
public record SymbolSnapshot(String symbolKey,
                             String filePath,
                             String chunkType,
                             String name,
                             String symbolVersion,
                             String indexEpoch,
                             String classpathEpoch) {
    public static SymbolSnapshot from(String filePath,
                                      String chunkType,
                                      String name,
                                      String content,
                                      String indexEpoch,
                                      String classpathEpoch) {
        String key = symbolKey(filePath, chunkType, name);
        String version = SymbolVersion.from(filePath, chunkType, name, content, classpathEpoch).value();
        return new SymbolSnapshot(key, filePath, chunkType, name, version, indexEpoch, classpathEpoch);
    }

    public static String symbolKey(String filePath, String chunkType, String name) {
        return safe(filePath) + "#" + safe(chunkType) + "#" + safe(name);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
