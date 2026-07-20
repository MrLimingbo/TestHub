package io.testhub.platform.performance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PerformanceTestExecutionReport(PerformanceTestExecution execution, Summary summary,
                                             List<PerformanceTestExecutionMetric> metrics,
                                             List<PerformanceTestExecutionResourceMetric> resources,
                                             List<PerformanceTestExecutionError> errors,
                                             List<PerformanceTestExecutionLog> logs, QualityVerdict quality,
                                             Comparison comparison, List<PerformanceTestExecutionArtifact> artifacts) {
    public record Summary(long sampleCount, long successCount, long errorCount, BigDecimal errorRatePercent,
                          BigDecimal peakThroughputPerSecond, int averageLatencyMillis, int p95LatencyMillis,
                          int minLatencyMillis, int maxLatencyMillis, int peakActiveThreads,
                          Instant firstSampleAt, Instant lastSampleAt) {
    }

    public record QualityVerdict(String status, int configuredChecks, int passedChecks, int failedChecks,
                                 int inconclusiveChecks, List<ThreadGroupThreshold> threadGroups) {
    }

    public record ThreadGroupThreshold(String threadGroupName, long sampleCount, int p95LatencyMillis,
                                       BigDecimal errorRatePercent, Integer p95ThresholdMillis,
                                       BigDecimal errorThresholdPercent, String status, List<String> breaches) {
    }

    public record Comparison(PerformanceTestExecution baseline, Summary baselineSummary, Delta delta) {
    }

    public record Delta(long sampleCount, BigDecimal errorRatePercent, BigDecimal peakThroughputPerSecond,
                        int p95LatencyMillis) {
    }
}
