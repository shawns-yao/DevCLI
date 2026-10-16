package com.devcli.concurrent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 后台任务共用的有界线程池，线程不继承调用方的线程本地上下文。 */
public final class BackgroundWorkers {
    private BackgroundWorkers() { }

    public static ExecutorService create(String name, int workers, int queueCapacity) {
        return new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
            Thread thread = new Thread(null, runnable, name, 0, false);
            thread.setDaemon(true);
            return thread;
        });
    }
}
