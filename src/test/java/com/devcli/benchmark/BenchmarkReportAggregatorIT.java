package com.devcli.benchmark;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class BenchmarkReportAggregatorIT {

    @Test
    void writesPersistentResumeMetricsReport() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("devcli.benchmark.aggregate"),
                "set -Ddevcli.benchmark.aggregate=true after benchmark reports exist");
        String version = System.getProperty("devcli.benchmark.version", "20260713_v1");

        BenchmarkReportAggregator.Result result = BenchmarkReportAggregator.aggregate(
                Path.of("").toAbsolutePath().normalize(), version);

        System.out.println("Benchmark summary JSON: " + result.json());
        System.out.println("Benchmark summary CSV: " + result.csv());
        System.out.println("Benchmark manifest: " + result.manifest());
    }
}
