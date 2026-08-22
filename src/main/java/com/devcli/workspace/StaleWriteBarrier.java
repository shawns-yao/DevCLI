package com.devcli.workspace;

import java.nio.file.Path;

/**
 * 旧测试和扩展点的兼容适配器。生产路径统一使用 {@link ContextVersionLedger}。
 */
@Deprecated
public final class StaleWriteBarrier {
    private final ContextVersionLedger ledger = new ContextVersionLedger();
    private Path commonRoot;

    public void recordRead(String scope, Path path, String content) {
        ledger.recordRead(scope, key(path), path, content);
    }

    public void recordCodeEvidence(String scope, Path path, String chunkType,
                                   String symbolName, String symbolVersion, String sourceContent) {
        ledger.recordCodeEvidence(scope, key(path), path, chunkType,
                symbolName, symbolVersion, sourceContent);
    }

    public void recordWrite(String scope, Path path, String content) {
        ledger.publishWrite(scope, key(path), path, content);
    }

    public void recordWrite(String scope, Path path, String before, String content) {
        ledger.publishWrite(scope, key(path), path, content);
    }

    public String staleReason(String scope, Path path, String currentContent) {
        WriteGateResult result = validateWrite(scope, path, currentContent);
        return result.isAllowed() ? null : result.reason();
    }

    public WriteGateResult validateWrite(String scope, Path path, String currentContent) {
        Path root = root(path);
        return ledger.validateWrite(scope, key(path), path, currentContent, root, true);
    }

    public void forgetScope(String scope) {
        ledger.forgetScope(scope);
    }

    public void clear() {
        ledger.clear();
        commonRoot = null;
    }

    private String key(Path path) {
        Path root = root(path);
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private synchronized Path root(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (commonRoot == null) {
            commonRoot = parent == null ? absolute : parent;
        } else {
            while (!absolute.startsWith(commonRoot) && commonRoot.getParent() != null) {
                commonRoot = commonRoot.getParent();
            }
        }
        return commonRoot;
    }
}
