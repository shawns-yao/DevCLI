package com.devcli.rag;

/**
 * Evidence that one indexed symbol changed between two index epochs.
 */
public record SymbolInvalidation(String symbolKey,
                                 String filePath,
                                 String chunkType,
                                 String name,
                                 String oldSymbolVersion,
                                 String newSymbolVersion,
                                 String oldIndexEpoch,
                                 String newIndexEpoch,
                                 String classpathEpoch,
                                 String negativeFact) {
    public static SymbolInvalidation from(SymbolSnapshot oldSnapshot, SymbolSnapshot newSnapshot) {
        String negativeFact = "Do not rely on " + newSnapshot.name()
                + " from symbolVersion " + oldSnapshot.symbolVersion()
                + ". Refresh this symbol from current RAG evidence before editing related code.";
        return new SymbolInvalidation(
                newSnapshot.symbolKey(),
                newSnapshot.filePath(),
                newSnapshot.chunkType(),
                newSnapshot.name(),
                oldSnapshot.symbolVersion(),
                newSnapshot.symbolVersion(),
                oldSnapshot.indexEpoch(),
                newSnapshot.indexEpoch(),
                newSnapshot.classpathEpoch(),
                negativeFact);
    }

    public static SymbolInvalidation deleted(SymbolSnapshot oldSnapshot, String newIndexEpoch) {
        String negativeFact = "Do not rely on " + oldSnapshot.name()
                + " from symbolVersion " + oldSnapshot.symbolVersion()
                + ". This symbol was removed from current index; refresh surrounding code before editing related paths.";
        return new SymbolInvalidation(
                oldSnapshot.symbolKey(),
                oldSnapshot.filePath(),
                oldSnapshot.chunkType(),
                oldSnapshot.name(),
                oldSnapshot.symbolVersion(),
                "deleted",
                oldSnapshot.indexEpoch(),
                newIndexEpoch == null || newIndexEpoch.isBlank() ? "none" : newIndexEpoch,
                oldSnapshot.classpathEpoch(),
                negativeFact);
    }
}
