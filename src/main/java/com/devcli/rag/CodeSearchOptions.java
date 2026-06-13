package com.devcli.rag;

public record CodeSearchOptions(CodeSearchMode mode, int graphDepth) {
    public static CodeSearchOptions resolve(String mode, String query, Integer graphDepth) {
        CodeSearchMode resolvedMode = CodeSearchMode.resolve(mode, query);
        int depth = graphDepth == null ? defaultDepth(resolvedMode) : graphDepth;
        depth = Math.max(0, Math.min(depth, 3));
        if (resolvedMode != CodeSearchMode.CALL_CHAIN) {
            depth = Math.min(depth, defaultDepth(resolvedMode));
        }
        return new CodeSearchOptions(resolvedMode, depth);
    }

    private static int defaultDepth(CodeSearchMode mode) {
        return switch (mode) {
            case CALL_CHAIN -> 3;
            case GENERAL, ERROR_TRACE -> 1;
            case AUTO, DEFINITION, CONFIG -> 0;
        };
    }
}
