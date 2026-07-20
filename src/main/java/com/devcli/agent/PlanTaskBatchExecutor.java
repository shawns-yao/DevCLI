package com.devcli.agent;

import com.devcli.plan.ResourceConflictDetector;
import com.devcli.plan.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Plan 可执行任务的冲突分波、并行调度和输出归并器。
 */
final class PlanTaskBatchExecutor {
    private static final Logger log = LoggerFactory.getLogger(PlanTaskBatchExecutor.class);
    private static final int MAX_PARALLEL_TASKS = 4;

    private final PrintStream out;
    private final Consumer<Task> taskStarter;
    private final BiFunction<Task, PrintStream, PlanTaskExecutionResult> taskRunner;
    private final Function<Task, List<String>> modifiedFilesReader;

    PlanTaskBatchExecutor(PrintStream out,
                          Consumer<Task> taskStarter,
                          BiFunction<Task, PrintStream, PlanTaskExecutionResult> taskRunner,
                          Function<Task, List<String>> modifiedFilesReader) {
        this.out = out;
        this.taskStarter = taskStarter;
        this.taskRunner = taskRunner;
        this.modifiedFilesReader = modifiedFilesReader;
    }

    List<PlanTaskExecutionResult> execute(List<Task> executableTasks) {
        List<List<Task>> waves = ResourceConflictDetector.splitConflictFree(
                executableTasks, Task::getId, Task::getDescription, task -> task.getType().name());
        if (waves.size() <= 1) {
            return executeConflictFreeWave(executableTasks);
        }
        List<PlanTaskExecutionResult> results = new ArrayList<>();
        for (List<Task> wave : waves) {
            results.addAll(executeConflictFreeWave(wave));
        }
        return results;
    }

    private List<PlanTaskExecutionResult> executeConflictFreeWave(List<Task> tasks) {
        if (tasks.size() == 1) {
            Task task = tasks.get(0);
            log.info("Executing single task: {} type={}", task.getId(), task.getType());
            out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
            taskStarter.accept(task);
            return List.of(taskRunner.apply(task, out));
        }

        String taskIds = tasks.stream()
                .map(Task::getId)
                .collect(Collectors.joining(", "));
        log.info("Executing parallel batch: {}", taskIds);
        out.println("⚡ 本轮并行执行 " + tasks.size() + " 个任务: " + taskIds);

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(tasks.size(), MAX_PARALLEL_TASKS), task -> {
                    Thread thread = new Thread(task, "devcli-plan-executor");
                    thread.setDaemon(true);
                    return thread;
                });
        Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
        Map<String, PrintStream> streams = new LinkedHashMap<>();
        try {
            List<Future<PlanTaskExecutionResult>> futures = new ArrayList<>();
            for (Task task : tasks) {
                out.println("▶️ 并行任务 [" + task.getId() + "]: " + task.getDescription());
                taskStarter.accept(task);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                PrintStream taskOut = new PrintStream(buffer, true, StandardCharsets.UTF_8);
                buffers.put(task.getId(), buffer);
                streams.put(task.getId(), taskOut);
                futures.add(executor.submit(() -> taskRunner.apply(task, taskOut)));
            }

            List<PlanTaskExecutionResult> results = new ArrayList<>();
            for (int index = 0; index < futures.size(); index++) {
                Task task = tasks.get(index);
                try {
                    results.add(futures.get(index).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(PlanTaskExecutionResult.failure(
                            task, e, modifiedFilesReader.apply(task)));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    Exception error = cause instanceof Exception exception
                            ? exception
                            : new RuntimeException(cause);
                    results.add(PlanTaskExecutionResult.failure(
                            task, error, modifiedFilesReader.apply(task)));
                }
            }

            for (Task task : tasks) {
                ByteArrayOutputStream buffer = buffers.get(task.getId());
                if (buffer != null && buffer.size() > 0) {
                    out.print(buffer.toString(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
            return results;
        } finally {
            streams.values().forEach(PrintStream::close);
            executor.shutdownNow();
        }
    }
}
