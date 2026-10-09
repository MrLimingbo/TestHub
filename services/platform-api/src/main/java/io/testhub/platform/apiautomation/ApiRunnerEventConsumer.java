package io.testhub.platform.apiautomation;

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

@Component
public class ApiRunnerEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(ApiRunnerEventConsumer.class);
    private static final String CONSUMER = "platform-api";
    private final RuntimeStreams streams;
    private final ApiAutomationExecutionService executions;
    private volatile boolean groupReady;

    public ApiRunnerEventConsumer(RuntimeStreams streams, ApiAutomationExecutionService executions) {
        this.streams = streams;
        this.executions = executions;
    }

    @Scheduled(fixedDelayString = "${testhub.runner.events.poll-interval-ms:500}")
    public void consume() {
        ensureGroup();
        List<MapRecord<String, Object, Object>> records = streams.read(
                Consumer.from(ApiRunnerProtocol.EVENT_GROUP, CONSUMER), StreamReadOptions.empty().count(20),
                StreamOffset.create(ApiRunnerProtocol.EVENT_STREAM, ReadOffset.lastConsumed()));
        if (records == null) return;
        for (MapRecord<String, Object, Object> record : records) consume(record);
    }

    private void consume(MapRecord<String, Object, Object> record) {
        ApiRunnerProtocol.ApiRunnerEvent event;
        try {
            event = ApiRunnerProtocol.event(fields(record));
        } catch (RuntimeException exception) {
            log.warn("Discarding an invalid API Runner event with stream id {}", record.getId());
            acknowledge(record);
            return;
        }
        try {
            executions.applyRunnerEvent(event);
            acknowledge(record);
        } catch (RuntimeException exception) {
            log.error("Unable to persist API Runner event with stream id {}", record.getId(), exception);
        }
    }

    private Map<String, String> fields(MapRecord<String, Object, Object> record) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : record.getValue().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            fields.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return Map.copyOf(fields);
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        streams.acknowledge(ApiRunnerProtocol.EVENT_GROUP, record);
    }

    private synchronized void ensureGroup() {
        if (groupReady) return;
        try {
            streams.createGroup(ApiRunnerProtocol.EVENT_STREAM, ReadOffset.latest(), ApiRunnerProtocol.EVENT_GROUP);
        } catch (Exception firstAttempt) {
            if (!contains(firstAttempt, "BUSYGROUP")) {
                streams.add(StreamRecords.mapBacked(Map.of("bootstrap", "1"))
                        .withStreamKey(ApiRunnerProtocol.EVENT_STREAM));
                try {
                    streams.createGroup(ApiRunnerProtocol.EVENT_STREAM, ReadOffset.latest(), ApiRunnerProtocol.EVENT_GROUP);
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
