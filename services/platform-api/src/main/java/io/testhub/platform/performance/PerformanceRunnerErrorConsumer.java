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
import io.testhub.platform.shared.runtime.RuntimeStreams;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PerformanceRunnerErrorConsumer {
    private static final Logger log = LoggerFactory.getLogger(PerformanceRunnerErrorConsumer.class);
    private static final String CONSUMER = "platform-performance-errors";
    private final RuntimeStreams streams;
    private final PerformanceTestExecutionService executions;
    private volatile boolean groupReady;

    public PerformanceRunnerErrorConsumer(RuntimeStreams streams, PerformanceTestExecutionService executions) {
        this.streams = streams;
        this.executions = executions;
    }

    @Scheduled(fixedDelayString = "${testhub.performance-runner.errors.poll-interval-ms:500}")
    public void consume() {
        ensureGroup();
        List<MapRecord<String, Object, Object>> records = streams.read(
                Consumer.from(PerformanceRunnerProtocol.ERROR_GROUP, CONSUMER), StreamReadOptions.empty().count(100),
                StreamOffset.create(PerformanceRunnerProtocol.ERROR_STREAM, ReadOffset.lastConsumed()));
        if (records == null) return;
        for (MapRecord<String, Object, Object> record : records) consume(record);
    }

    private void consume(MapRecord<String, Object, Object> record) {
        try {
            executions.applyRunnerError(PerformanceRunnerProtocol.error(fields(record)));
            acknowledge(record);
        } catch (IllegalArgumentException | ResponseStatusException exception) {
            log.warn("Discarding an invalid JMeter Runner error summary with stream id {}", record.getId());
            acknowledge(record);
        } catch (RuntimeException exception) {
            log.error("Unable to persist JMeter Runner error summary with stream id {}", record.getId(), exception);
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
        streams.acknowledge(PerformanceRunnerProtocol.ERROR_GROUP, record);
    }

    private synchronized void ensureGroup() {
        if (groupReady) return;
        try {
            streams.createGroup(PerformanceRunnerProtocol.ERROR_STREAM, ReadOffset.from("0-0"), PerformanceRunnerProtocol.ERROR_GROUP);
        } catch (Exception firstAttempt) {
            if (!contains(firstAttempt, "BUSYGROUP")) {
                streams.add(StreamRecords.mapBacked(Map.of("bootstrap", "1"))
                        .withStreamKey(PerformanceRunnerProtocol.ERROR_STREAM));
                try {
                    streams.createGroup(PerformanceRunnerProtocol.ERROR_STREAM, ReadOffset.from("0-0"), PerformanceRunnerProtocol.ERROR_GROUP);
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
