package com.devcli.agent;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Plan 与 Team 共用的冲突分波内执行内核：有界并发、异常归属和稳定顺序输出。
 */
final class OrchestrationWaveExecutor {

    @FunctionalInterface
    interface TaskRunner<T, R> {
        R run(T item, PrintStream itemOut) throws Exception;
    }

    @FunctionalInterface
    interface FailureMapper<T, R> {
        R map(T item, Exception error, PrintStream itemOut);
    }

    private OrchestrationWaveExecutor() {
    }

    static <T, R> List<R> execute(OrchestrationProfile profile,
                                  PrintStream out,
                                  List<T> items,
                                  int requestedParallelism,
                                  Consumer<T> beforeSubmit,
                                  TaskRunner<T, R> taskRunner,
                                  FailureMapper<T, R> failureMapper) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(beforeSubmit, "beforeSubmit");
        Objects.requireNonNull(taskRunner, "taskRunner");
        Objects.requireNonNull(failureMapper, "failureMapper");
        if (items.isEmpty()) {
            return List.of();
        }

        int parallelism = profile.parallelismFor(items.size(), requestedParallelism);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, task -> {
            Thread thread = new Thread(task, profile.threadNamePrefix());
            thread.setDaemon(true);
            return thread;
        });
        List<ByteArrayOutputStream> buffers = new ArrayList<>(items.size());
        List<PrintStream> streams = new ArrayList<>(items.size());
        List<Future<R>> futures = new ArrayList<>(items.size());
        boolean interrupted = false;

        try {
            for (T item : items) {
                beforeSubmit.accept(item);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                PrintStream itemOut = new PrintStream(buffer, true, StandardCharsets.UTF_8);
                buffers.add(buffer);
                streams.add(itemOut);
                futures.add(executor.submit(() -> {
                    try {
                        return taskRunner.run(item, itemOut);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return failureMapper.map(item, e, itemOut);
                    } catch (Exception e) {
                        return failureMapper.map(item, e, itemOut);
                    } finally {
                        itemOut.flush();
                    }
                }));
            }

            List<R> results = new ArrayList<>(items.size());
            for (int index = 0; index < futures.size(); index++) {
                Future<R> future = futures.get(index);
                T item = items.get(index);
                PrintStream itemOut = streams.get(index);
                if (interrupted) {
                    future.cancel(true);
                    results.add(failureMapper.map(
                            item, new InterruptedException("编排批次等待被中断"), itemOut));
                    continue;
                }
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    interrupted = true;
                    future.cancel(true);
                    results.add(failureMapper.map(item, e, itemOut));
                } catch (ExecutionException e) {
                    results.add(failureMapper.map(item, asException(e.getCause()), itemOut));
                }
            }
            return Collections.unmodifiableList(results);
        } finally {
            executor.shutdownNow();
            for (ByteArrayOutputStream buffer : buffers) {
                if (buffer.size() > 0) {
                    out.print(buffer.toString(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
            streams.forEach(PrintStream::close);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Exception asException(Throwable cause) {
        if (cause instanceof Exception exception) {
            return exception;
        }
        return new RuntimeException(cause);
    }
}
