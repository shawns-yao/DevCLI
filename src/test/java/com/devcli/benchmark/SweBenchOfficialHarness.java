package com.devcli.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class SweBenchOfficialHarness {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SweBenchOfficialHarness() {
    }

    static void writePredictions(Path output, List<Prediction> predictions) throws IOException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            for (Prediction prediction : predictions) {
                writer.write(JSON.writeValueAsString(prediction));
                writer.newLine();
            }
        }
    }

    static List<String> evaluationCommand(Path python, Path predictions,
                                          Path reportDir, String runId,
                                          List<String> instanceIds, int maxWorkers) {
        if (python == null || predictions == null || reportDir == null) {
            throw new IllegalArgumentException("python, predictions and reportDir are required");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        List<String> command = new ArrayList<>(List.of(
                python.toString(),
                "-m", "swebench.harness.run_evaluation",
                "--dataset_name", "SWE-bench/SWE-bench_Lite",
                "--split", "test",
                "--predictions_path", predictions.toAbsolutePath().normalize().toString(),
                "--max_workers", Integer.toString(Math.max(1, maxWorkers)),
                "--run_id", runId.trim(),
                "--namespace", "none",
                "--report_dir", reportDir.toAbsolutePath().normalize().toString()
        ));
        if (instanceIds != null && !instanceIds.isEmpty()) {
            command.add("--instance_ids");
            command.addAll(instanceIds);
        }
        return List.copyOf(command);
    }

    static List<String> dockerEvaluationCommand(String image, Path predictions,
                                                Path reportDir, Path runDir,
                                                String runId, List<String> instanceIds,
                                                int maxWorkers) {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("image is required");
        }
        Path inputDir = predictions.toAbsolutePath().normalize().getParent();
        Path normalizedReport = reportDir.toAbsolutePath().normalize();
        Path normalizedRun = runDir.toAbsolutePath().normalize();
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--mount", "type=bind,source=" + inputDir + ",target=/work/input,readonly",
                "--mount", "type=bind,source=" + normalizedReport + ",target=/work/report",
                "--mount", "type=bind,source=" + normalizedRun + ",target=/work/run",
                "--mount", "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock",
                "--workdir", "/work/run",
                image.trim(),
                "--dataset_name", "SWE-bench/SWE-bench_Lite",
                "--split", "test",
                "--predictions_path", "/work/input/" + predictions.getFileName(),
                "--max_workers", Integer.toString(Math.max(1, maxWorkers)),
                "--run_id", runId.trim(),
                "--namespace", "none",
                "--report_dir", "/work/report"
        ));
        if (instanceIds != null && !instanceIds.isEmpty()) {
            command.add("--instance_ids");
            command.addAll(instanceIds);
        }
        return List.copyOf(command);
    }

    record Prediction(String instance_id, String model_name_or_path, String model_patch) {
        Prediction {
            instance_id = required(instance_id, "instance_id");
            model_name_or_path = required(model_name_or_path, "model_name_or_path");
            model_patch = model_patch == null ? "" : model_patch;
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value.trim();
        }
    }
}
