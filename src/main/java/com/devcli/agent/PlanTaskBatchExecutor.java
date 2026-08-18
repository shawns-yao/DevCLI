package com.devcli.agent;

import com.devcli.plan.ResourceConflictDetector;
import com.devcli.plan.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Plan 可执行任务的冲突分波、并行调度和输出归并器。
 */
final class PlanTaskBatchExecutor {
    private static final Logger log = LoggerFactory.getLogger(PlanTaskBatchExecutor.class);

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

        return OrchestrationWaveExecutor.execute(
                OrchestrationProfile.STANDARD,
                out,
                tasks,
                OrchestrationProfile.STANDARD.maxParallelism(),
                task -> {
                    out.println("▶️ 并行任务 [" + task.getId() + "]: " + task.getDescription());
                    taskStarter.accept(task);
                },
                taskRunner::apply,
                (task, error, taskOut) -> PlanTaskExecutionResult.failure(
                        task, error, modifiedFilesReader.apply(task)));
    }
}
