package com.devcli.workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 过期写入屏障。
 *
 * <p>拦截 read-modify-write 的丢失更新：某步骤读到文件的旧版本，期间别人改了它，
 * 该步骤仍按旧版本写回，会静默覆盖别人的改动。
 *
 * <p>与既有机制的分工：
 * <ul>
 *   <li>{@code ResourceLeaseManager（资源租约）}：步骤执行期内防止并发写同一文件，
 *       租约随步骤结束释放，管不到跨步骤的版本过期。</li>
 *   <li>本屏障：按"读时观察到的内容版本"与"写时磁盘实际版本"比对，跨步骤有效。</li>
 * </ul>
 *
 * <p>作用边界：只对非空 scope（Multi-Agent 步骤 id）生效。单 Agent 路径没有步骤概念，
 * 且"读文件 → 执行命令改文件 → 写回"是正常流程，启用屏障会产生误拦。
 *
 * <p>粒度是<b>文件级</b>，不是符号级。它拦不住"A 改了方法签名、B 改另一个文件里的调用方"
 * 这类语义冲突——那需要符号级依赖清单，仍是待办。
 *
 * <p>线程安全：Multi-Agent 并行 Worker 各占一线程，全部状态用并发容器。
 */
public final class StaleWriteBarrier {

    /** scope -> (文件 -> 该 scope 读到时的内容指纹)。 */
    private final Map<String, Map<String, String>> observedByScope = new ConcurrentHashMap<>();
    /** 文件 -> 最后一次写入它的 scope，用于把"是谁改的"写进错误信息。 */
    private final Map<String, String> lastWriterByPath = new ConcurrentHashMap<>();

    public void recordRead(String scope, Path path, String content) {
        if (isInactive(scope) || path == null) {
            return;
        }
        observedByScope
                .computeIfAbsent(scope, key -> new ConcurrentHashMap<>())
                .put(key(path), fingerprint(content));
    }

    public void recordWrite(String scope, Path path, String content) {
        if (isInactive(scope) || path == null) {
            return;
        }
        String key = key(path);
        lastWriterByPath.put(key, scope);
        // 自己写完就认得当前版本，避免自我阻塞
        observedByScope
                .computeIfAbsent(scope, k -> new ConcurrentHashMap<>())
                .put(key, fingerprint(content));
    }

    /**
     * @param currentContent 写入前从磁盘读到的当前内容（null 表示文件不存在）
     * @return 过期原因；不过期返回 null
     */
    public String staleReason(String scope, Path path, String currentContent) {
        if (isInactive(scope) || path == null) {
            return null;
        }
        Map<String, String> observed = observedByScope.get(scope);
        if (observed == null) {
            return null;
        }
        String key = key(path);
        String observedFingerprint = observed.get(key);
        if (observedFingerprint == null) {
            // 没读过就写属于整体覆盖，不是 read-modify-write
            return null;
        }
        String currentFingerprint = fingerprint(currentContent);
        if (observedFingerprint.equals(currentFingerprint)) {
            return null;
        }
        String writer = lastWriterByPath.get(key);
        String changedBy = writer == null || writer.equals(scope)
                ? "该文件已被本流程外的改动修改"
                : "该文件已被 " + writer + " 修改";
        return "过期写入被拦截: " + path.getFileName() + " —— " + changedBy
                + "，而当前写入基于读取时的旧版本，直接写回会覆盖对方改动。"
                + "请先用 read_file 重读 " + path + " 再基于最新内容重写。";
    }

    /** 步骤结束后清理其观察记录，避免长会话无界增长。 */
    public void forgetScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return;
        }
        observedByScope.remove(scope);
    }

    public void clear() {
        observedByScope.clear();
        lastWriterByPath.clear();
    }

    private static boolean isInactive(String scope) {
        return scope == null || scope.isBlank();
    }

    private static String key(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static String fingerprint(String content) {
        if (content == null) {
            return "<absent>";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(16, hash.length); i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(content.hashCode()) + ":" + content.length();
        }
    }
}
