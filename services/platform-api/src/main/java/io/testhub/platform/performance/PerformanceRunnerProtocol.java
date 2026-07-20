package io.testhub.platform.performance;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class PerformanceRunnerProtocol {
    public static final String VERSION = "1";
    public static final String TASK_CONTRACT = "testhub.jmeter-runner.task";
    public static final String CANCEL_CONTRACT = "testhub.jmeter-runner.cancel";
    public static final String EVENT_CONTRACT = "testhub.jmeter-runner.event";
    public static final String METRIC_CONTRACT = "testhub.jmeter-runner.metric";
    public static final String RESOURCE_CONTRACT = "testhub.jmeter-runner.resource";
    public static final String ERROR_CONTRACT = "testhub.jmeter-runner.error";
    public static final String TASK_STREAM = "testhub:runner:jmeter:tasks:v1";
    public static final String CANCEL_STREAM = "testhub:runner:jmeter:cancellations:v1";
    public static final String EVENT_STREAM = "testhub:runner:jmeter:events:v1";
    public static final String METRIC_STREAM = "testhub:runner:jmeter:metrics:v1";
    public static final String RESOURCE_STREAM = "testhub:runner:jmeter:resources:v1";
    public static final String ERROR_STREAM = "testhub:runner:jmeter:errors:v1";
    public static final String EVENT_GROUP = "testhub-platform-performance-events-v1";
    public static final String METRIC_GROUP = "testhub-platform-performance-metrics-v1";
    public static final String RESOURCE_GROUP = "testhub-platform-performance-resources-v1";
    public static final String ERROR_GROUP = "testhub-platform-performance-errors-v1";
    public static final String RUNNER_TYPE = "jmeter";
    private static final Set<String> EVENT_FIELDS = Set.of("contract", "protocolVersion", "taskId", "executionId",
            "runnerId", "type", "code", "message", "occurredAt");
    private static final Set<String> EVENT_TYPES = Set.of("ACCEPTED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "REJECTED", "DEFERRED");
    private static final Set<String> METRIC_FIELDS = Set.of("contract", "protocolVersion", "taskId", "executionId",
            "runnerId", "threadGroupName", "sampleCount", "successCount", "errorCount", "activeThreads",
            "throughputPerSecond", "averageLatencyMillis", "p95LatencyMillis", "minLatencyMillis", "maxLatencyMillis", "occurredAt");
    private static final Set<String> ERROR_FIELDS = Set.of("contract", "protocolVersion", "taskId", "executionId",
            "runnerId", "threadGroupName", "responseCode", "errorCount", "occurredAt");
    private static final Set<String> RESOURCE_FIELDS = Set.of("contract", "protocolVersion", "taskId", "executionId",
            "runnerId", "cpuPercent", "residentMemoryBytes", "occurredAt");

    private PerformanceRunnerProtocol() {
    }

    public static PerformanceRunnerEvent event(Map<String, String> fields) {
        if (fields == null || !new LinkedHashSet<>(fields.keySet()).equals(EVENT_FIELDS)) {
            throw invalid("JMeter Runner 事件字段不兼容");
        }
        if (!EVENT_CONTRACT.equals(required(fields, "contract")) || !VERSION.equals(required(fields, "protocolVersion"))) {
            throw invalid("JMeter Runner 事件协议不兼容");
        }
        String type = required(fields, "type");
        if (!EVENT_TYPES.contains(type)) throw invalid("JMeter Runner 事件类型不受支持");
        String runnerId = required(fields, "runnerId");
        if (!runnerId.matches("[A-Za-z0-9._-]{1,64}")) throw invalid("JMeter Runner 标识不合法");
        String code = required(fields, "code");
        if (!code.matches("[A-Z0-9_]{1,80}")) throw invalid("JMeter Runner 事件代码不合法");
        String message = required(fields, "message");
        if (message.length() > 512) throw invalid("JMeter Runner 事件消息过长");
        try {
            return new PerformanceRunnerEvent(ulid(required(fields, "taskId"), "taskId"),
                    ulid(required(fields, "executionId"), "executionId"), runnerId, type, code, message,
                    Instant.parse(required(fields, "occurredAt")));
        } catch (DateTimeParseException exception) {
            throw invalid("JMeter Runner 事件时间不合法");
        }
    }

    public static PerformanceRunnerMetric metric(Map<String, String> fields) {
        if (fields == null || !new LinkedHashSet<>(fields.keySet()).equals(METRIC_FIELDS)) {
            throw invalid("JMeter Runner 指标字段不兼容");
        }
        if (!METRIC_CONTRACT.equals(required(fields, "contract")) || !VERSION.equals(required(fields, "protocolVersion"))) {
            throw invalid("JMeter Runner 指标协议不兼容");
        }
        String runnerId = required(fields, "runnerId");
        String threadGroupName = required(fields, "threadGroupName");
        if (!runnerId.matches("[A-Za-z0-9._-]{1,64}") || threadGroupName.length() > 160) {
            throw invalid("JMeter Runner 指标范围不合法");
        }
        try {
            long sampleCount = nonNegativeLong(fields, "sampleCount");
            long successCount = nonNegativeLong(fields, "successCount");
            long errorCount = nonNegativeLong(fields, "errorCount");
            int activeThreads = nonNegativeInt(fields, "activeThreads");
            int averageLatencyMillis = nonNegativeInt(fields, "averageLatencyMillis");
            int p95LatencyMillis = nonNegativeInt(fields, "p95LatencyMillis");
            int minLatencyMillis = nonNegativeInt(fields, "minLatencyMillis");
            int maxLatencyMillis = nonNegativeInt(fields, "maxLatencyMillis");
            java.math.BigDecimal throughput = new java.math.BigDecimal(required(fields, "throughputPerSecond"));
            if (throughput.signum() < 0 || throughput.scale() > 4 || throughput.precision() > 14
                    || successCount + errorCount != sampleCount || minLatencyMillis > maxLatencyMillis
                    || averageLatencyMillis > maxLatencyMillis || p95LatencyMillis > maxLatencyMillis) {
                throw invalid("JMeter Runner 指标数值不合法");
            }
            return new PerformanceRunnerMetric(ulid(required(fields, "taskId"), "taskId"),
                    ulid(required(fields, "executionId"), "executionId"), runnerId, threadGroupName, sampleCount,
                    successCount, errorCount, activeThreads, throughput, averageLatencyMillis, p95LatencyMillis,
                    minLatencyMillis, maxLatencyMillis, Instant.parse(required(fields, "occurredAt")));
        } catch (NumberFormatException | DateTimeParseException exception) {
            throw invalid("JMeter Runner 指标数值或时间不合法");
        }
    }

    public static PerformanceRunnerError error(Map<String, String> fields) {
        if (fields == null || !new LinkedHashSet<>(fields.keySet()).equals(ERROR_FIELDS)) {
            throw invalid("JMeter Runner 错误归因字段不兼容");
        }
        if (!ERROR_CONTRACT.equals(required(fields, "contract")) || !VERSION.equals(required(fields, "protocolVersion"))) {
            throw invalid("JMeter Runner 错误归因协议不兼容");
        }
        String runnerId = required(fields, "runnerId");
        String threadGroupName = required(fields, "threadGroupName");
        String responseCode = required(fields, "responseCode");
        if (!runnerId.matches("[A-Za-z0-9._-]{1,64}") || threadGroupName.length() > 160
                || !responseCode.matches("(?:[0-9]{3}|NON_HTTP_ERROR)")) {
            throw invalid("JMeter Runner 错误归因范围不合法");
        }
        try {
            long errorCount = positiveLong(fields, "errorCount");
            return new PerformanceRunnerError(ulid(required(fields, "taskId"), "taskId"),
                    ulid(required(fields, "executionId"), "executionId"), runnerId, threadGroupName, responseCode,
                    errorCount, Instant.parse(required(fields, "occurredAt")));
        } catch (NumberFormatException | DateTimeParseException exception) {
            throw invalid("JMeter Runner 错误归因数值或时间不合法");
        }
    }

    public static PerformanceRunnerResource resource(Map<String, String> fields) {
        if (fields == null || !new LinkedHashSet<>(fields.keySet()).equals(RESOURCE_FIELDS)) {
            throw invalid("JMeter Runner 资源指标字段不兼容");
        }
        if (!RESOURCE_CONTRACT.equals(required(fields, "contract")) || !VERSION.equals(required(fields, "protocolVersion"))) {
            throw invalid("JMeter Runner 资源指标协议不兼容");
        }
        String runnerId = required(fields, "runnerId");
        if (!runnerId.matches("[A-Za-z0-9._-]{1,64}")) throw invalid("JMeter Runner 标识不合法");
        try {
            java.math.BigDecimal cpuPercent = new java.math.BigDecimal(required(fields, "cpuPercent"));
            long residentMemoryBytes = nonNegativeLong(fields, "residentMemoryBytes");
            if (cpuPercent.signum() < 0 || cpuPercent.scale() > 4 || cpuPercent.precision() > 9
                    || cpuPercent.compareTo(new java.math.BigDecimal("10000.0000")) > 0
                    || residentMemoryBytes > 1_099_511_627_776L) {
                throw invalid("JMeter Runner 资源指标数值不合法");
            }
            return new PerformanceRunnerResource(ulid(required(fields, "taskId"), "taskId"),
                    ulid(required(fields, "executionId"), "executionId"), runnerId, cpuPercent, residentMemoryBytes,
                    Instant.parse(required(fields, "occurredAt")));
        } catch (NumberFormatException | DateTimeParseException exception) {
            throw invalid("JMeter Runner 资源指标数值或时间不合法");
        }
    }

    private static String required(Map<String, String> fields, String field) {
        String value = fields.get(field);
        if (value == null || value.isBlank() || value.length() > 4096) throw invalid("JMeter Runner 事件缺少字段：" + field);
        return value;
    }

    private static String ulid(String value, String field) {
        if (!value.matches("[0-9A-HJKMNP-TV-Z]{26}")) throw invalid("JMeter Runner 事件 " + field + " 不合法");
        return value;
    }

    private static long nonNegativeLong(Map<String, String> fields, String field) {
        String value = required(fields, field);
        if (!value.matches("[0-9]{1,18}")) throw invalid("JMeter Runner 指标 " + field + " 不合法");
        return Long.parseLong(value);
    }

    private static long positiveLong(Map<String, String> fields, String field) {
        String value = required(fields, field);
        if (!value.matches("[1-9][0-9]{0,17}")) throw invalid("JMeter Runner 错误归因 " + field + " 不合法");
        return Long.parseLong(value);
    }

    private static int nonNegativeInt(Map<String, String> fields, String field) {
        long value = nonNegativeLong(fields, field);
        if (value > Integer.MAX_VALUE) throw invalid("JMeter Runner 指标 " + field + " 超出范围");
        return (int) value;
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record PerformanceRunnerEvent(String taskId, String executionId, String runnerId, String type, String code,
                                         String message, Instant occurredAt) {
    }

    public record PerformanceRunnerMetric(String taskId, String executionId, String runnerId, String threadGroupName,
                                          long sampleCount, long successCount, long errorCount, int activeThreads,
                                          java.math.BigDecimal throughputPerSecond, int averageLatencyMillis,
                                          int p95LatencyMillis, int minLatencyMillis, int maxLatencyMillis,
                                          Instant occurredAt) {
    }

    public record PerformanceRunnerResource(String taskId, String executionId, String runnerId,
                                            java.math.BigDecimal cpuPercent, long residentMemoryBytes,
                                            Instant occurredAt) {
    }

    public record PerformanceRunnerError(String taskId, String executionId, String runnerId, String threadGroupName,
                                         String responseCode, long errorCount, Instant occurredAt) {
    }
}
