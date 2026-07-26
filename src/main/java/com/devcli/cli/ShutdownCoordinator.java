package com.devcli.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一 JVM 关停出口。
 *
 * <p>动机:JVM 的多个 shutdown hook 之间是并发执行、没有任何顺序保证的。
 * 之前 Main 里 MCP、Agent、向量库、任务管理器各自注册独立 hook,
 * 可能出现"任务管理器还在收尾无头任务时,它依赖的 MCP/记忆资源已被并发关闭"的竞态。
 *
 * <p>本类改为:全进程只注册一个 hook,资源按显式 order 升序依次关闭
 * (order 小的先关;依赖方应比被依赖方先关)。单个资源关闭失败只记录日志,
 * 不阻断后续资源的关闭。
 */
final class ShutdownCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ShutdownCoordinator.class);

    private record Entry(int order, String name, AutoCloseable resource) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final AtomicBoolean hookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 注册一个随 JVM 退出关闭的资源。order 小的先关闭;
     * 建议:先关"发起方/依赖方"(如后台任务管理器),后关"被依赖的基础资源"(如存储)。
     */
    synchronized void register(int order, String name, AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        entries.add(new Entry(order, name, resource));
        if (hookInstalled.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::closeAll, "devcli-shutdown"));
        }
    }

    /** 按 order 升序关闭全部资源;可重入安全,只执行一次。 */
    void closeAll() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<Entry> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(entries);
        }
        snapshot.sort(Comparator.comparingInt(Entry::order));
        for (Entry entry : snapshot) {
            try {
                entry.resource().close();
            } catch (Exception e) {
                log.warn("shutdown close failed: {}", entry.name(), e);
            } catch (Error e) {
                // 关停路径上的 Error 也不应阻断其余资源关闭;记录后继续。
                log.error("shutdown close error: {}", entry.name(), e);
            }
        }
    }
}
