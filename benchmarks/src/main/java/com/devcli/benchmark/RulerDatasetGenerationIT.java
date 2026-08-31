package com.devcli.benchmark;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulerDatasetGenerationIT {
    private static final String TEMPLATE = """
            Some special magic {type_needle_v} are hidden within the following text. Make sure to memorize it. I will quiz you about the {type_needle_v} afterwards.
            {context}
            What are all the special magic {type_needle_v} for {query} mentioned in the provided text? The special magic {type_needle_v} for {query} mentioned in the provided text are""";

    @Test
    void generatesPinnedRulerNiahSamplesThroughOfficialGenerator() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("devcli.benchmark.ruler.generate"),
                "set -Ddevcli.benchmark.ruler.generate=true after preparing the RULER Python environment");
        Path root = Path.of("").toAbsolutePath().normalize();
        Path python = configuredPython(root);
        Path generator = root.resolve(
                "Data/raw/public-benchmarks/ruler/extracted/NVIDIA-RULER-38da79d/scripts/data/synthetic/niah.py");
        Path saveDir = root.resolve("Data/raw/public-benchmarks/ruler/generated");
        Assumptions.assumeTrue(Files.isRegularFile(python), "RULER Python environment missing: " + python);
        Assumptions.assumeTrue(Files.isRegularFile(generator), "pinned RULER generator missing: " + generator);
        Files.createDirectories(saveDir);

        int samples = Math.max(1, Integer.getInteger("devcli.benchmark.ruler.samples", 3));
        int length = Math.max(4096, Integer.getInteger("devcli.benchmark.ruler.length", 4096));
        List<String> command = new ArrayList<>(List.of(
                python.toString(), generator.toString(),
                "--save_dir", saveDir.toString(),
                "--save_name", "niah_single_1",
                "--subset", "validation",
                "--tokenizer_path", "cl100k_base",
                "--tokenizer_type", "openai",
                "--max_seq_length", Integer.toString(length),
                "--tokens_to_generate", "128",
                "--num_samples", Integer.toString(samples),
                "--random_seed", "42",
                "--type_haystack", "noise",
                "--type_needle_k", "words",
                "--type_needle_v", "numbers",
                "--num_needle_k", "1",
                "--num_needle_v", "1",
                "--num_needle_q", "1",
                "--template", TEMPLATE
        ));
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(Duration.ofMinutes(5).toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("RULER generator timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);

        Path generated = saveDir.resolve("niah_single_1/validation.jsonl");
        List<PublicBenchmarkDatasets.RulerCase> cases =
                PublicBenchmarkDatasets.loadRuler(generated, samples);
        assertEquals(samples, cases.size());
        assertTrue(cases.stream().allMatch(item -> item.length() >= length * 0.80),
                "generated RULER samples did not reach the requested context length");
    }

    private static Path configuredPython(Path root) {
        String configured = System.getProperty("devcli.benchmark.ruler.python", "").trim();
        if (!configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "Scripts/python.exe"
                : "bin/python";
        return root.resolve("Temp/ruler-venv").resolve(executable).normalize();
    }
}
