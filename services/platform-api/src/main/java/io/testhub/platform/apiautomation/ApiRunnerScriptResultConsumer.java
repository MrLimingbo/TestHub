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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Persists only fixed pm.test totals and immediately acknowledges malformed records. */
@Component
public class ApiRunnerScriptResultConsumer {
    private static final Logger log = LoggerFactory.getLogger(ApiRunnerScriptResultConsumer.class);
    private static final String CONSUMER = "platform-api";
    private final StringRedisTemplate redis;
    private final ApiAutomationExecutionService executions;
    private volatile boolean groupReady;

    public ApiRunnerScriptResultConsumer(StringRedisTemplate redis, ApiAutomationExecutionService executions) {
        this.redis = redis;
        this.executions = executions;
    }

    @Scheduled(fixedDelayString = "${testhub.runner.script-results.poll-interval-ms:500}")
    public void consume() {
        ensureGroup();
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(ApiRunnerProtocol.SCRIPT_RESULT_GROUP, CONSUMER), StreamReadOptions.empty().count(50),
                StreamOffset.create(ApiRunnerProtocol.SCRIPT_RESULT_STREAM, ReadOffset.lastConsumed()));
        if (records == null) return;
        for (MapRecord<String, Object, Object> record : records) consume(record);
    }

    private void consume(MapRecord<String, Object, Object> record) {
        try {
            executions.applyRunnerScriptResult(ApiRunnerProtocol.scriptResult(fields(record)), String.valueOf(record.getId()));
            acknowledge(record);
        } catch (org.springframework.web.server.ResponseStatusException exception) {
            log.warn("Discarding an invalid API Runner script result with stream id {}", record.getId());
            acknowledge(record);
        } catch (RuntimeException exception) {
            log.error("Unable to persist API Runner script result with stream id {}", record.getId(), exception);
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
        redis.opsForStream().acknowledge(ApiRunnerProtocol.SCRIPT_RESULT_GROUP, record);
    }

    private synchronized void ensureGroup() {
        if (groupReady) return;
        try {
            redis.opsForStream().createGroup(ApiRunnerProtocol.SCRIPT_RESULT_STREAM, ReadOffset.latest(), ApiRunnerProtocol.SCRIPT_RESULT_GROUP);
        } catch (Exception firstAttempt) {
            if (!contains(firstAttempt, "BUSYGROUP")) {
                redis.opsForStream().add(StreamRecords.mapBacked(Map.of("bootstrap", "1")).withStreamKey(ApiRunnerProtocol.SCRIPT_RESULT_STREAM));
                try {
                    redis.opsForStream().createGroup(ApiRunnerProtocol.SCRIPT_RESULT_STREAM, ReadOffset.latest(), ApiRunnerProtocol.SCRIPT_RESULT_GROUP);
                } catch (Exception secondAttempt) {
                    if (!contains(secondAttempt, "BUSYGROUP")) throw secondAttempt;
                }
            }
        }
        groupReady = true;
    }

    private boolean contains(Exception exception, String text) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(text)) return true;
        }
        return false;
    }
}
