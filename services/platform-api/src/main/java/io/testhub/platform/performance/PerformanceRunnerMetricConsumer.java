package io.testhub.platform.performance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PerformanceRunnerMetricConsumer {
    private static final Logger log = LoggerFactory.getLogger(PerformanceRunnerMetricConsumer.class);
    private static final String CONSUMER = "platform-performance-metrics";
    private final StringRedisTemplate redis;
    private final PerformanceTestExecutionService executions;
    private volatile boolean groupReady;

    public PerformanceRunnerMetricConsumer(StringRedisTemplate redis, PerformanceTestExecutionService executions) {
        this.redis = redis;
        this.executions = executions;
    }

    @Scheduled(fixedDelayString = "${testhub.performance-runner.metrics.poll-interval-ms:500}")
    public void consume() {
        ensureGroup();
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(PerformanceRunnerProtocol.METRIC_GROUP, CONSUMER), StreamReadOptions.empty().count(100),
                StreamOffset.create(PerformanceRunnerProtocol.METRIC_STREAM, ReadOffset.lastConsumed()));
        if (records == null) return;
        for (MapRecord<String, Object, Object> record : records) consume(record);
    }

    private void consume(MapRecord<String, Object, Object> record) {
        try {
            executions.applyRunnerMetric(PerformanceRunnerProtocol.metric(fields(record)));
            acknowledge(record);
        } catch (IllegalArgumentException | ResponseStatusException exception) {
            log.warn("Discarding an invalid JMeter Runner metric with stream id {}", record.getId());
            acknowledge(record);
        } catch (RuntimeException exception) {
            log.error("Unable to persist JMeter Runner metric with stream id {}", record.getId(), exception);
        }
    }

    private Map<String, String> fields(MapRecord<String, Object, Object> record) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : record.getValue().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) fields.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return Map.copyOf(fields);
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        redis.opsForStream().acknowledge(PerformanceRunnerProtocol.METRIC_GROUP, record);
    }

    private synchronized void ensureGroup() {
        if (groupReady) return;
        try {
            redis.opsForStream().createGroup(PerformanceRunnerProtocol.METRIC_STREAM, ReadOffset.latest(), PerformanceRunnerProtocol.METRIC_GROUP);
        } catch (Exception firstAttempt) {
            if (!contains(firstAttempt, "BUSYGROUP")) {
                redis.opsForStream().add(StreamRecords.mapBacked(Map.of("bootstrap", "1"))
                        .withStreamKey(PerformanceRunnerProtocol.METRIC_STREAM));
                try {
                    redis.opsForStream().createGroup(PerformanceRunnerProtocol.METRIC_STREAM, ReadOffset.latest(), PerformanceRunnerProtocol.METRIC_GROUP);
                } catch (Exception secondAttempt) {
                    if (!contains(secondAttempt, "BUSYGROUP")) throw secondAttempt;
                }
            }
        }
        groupReady = true;
    }

    private boolean contains(Exception exception, String text) {
        Throwable current = exception;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(text)) return true;
            current = current.getCause();
        }
        return false;
    }
}
