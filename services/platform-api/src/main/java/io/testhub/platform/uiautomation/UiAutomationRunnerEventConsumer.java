package io.testhub.platform.uiautomation;

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
public class UiAutomationRunnerEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(UiAutomationRunnerEventConsumer.class);
    private final RuntimeStreams streams;
    private final UiAutomationService automation;
    private volatile boolean ready;
    public UiAutomationRunnerEventConsumer(RuntimeStreams streams, UiAutomationService automation) {
        this.streams = streams; this.automation = automation;
    }
    @Scheduled(fixedDelayString = "${testhub.ui-automation-runner.events.poll-interval-ms:500}")
    public void consume() {
        ensureGroup();
        List<MapRecord<String, Object, Object>> records = streams.read(Consumer.from(UiAutomationRunnerProtocol.EVENT_GROUP, "platform-ui-automation"),
                StreamReadOptions.empty().count(20), StreamOffset.create(UiAutomationRunnerProtocol.EVENT_STREAM, ReadOffset.lastConsumed()));
        if (records != null) records.forEach(this::consume);
    }
    private void consume(MapRecord<String, Object, Object> record) {
        try { automation.applyRunnerEvent(UiAutomationRunnerProtocol.event(fields(record))); acknowledge(record); }
        catch (IllegalArgumentException | ResponseStatusException exception) { log.warn("Discarding invalid Playwright Runner event {}", record.getId()); acknowledge(record); }
        catch (RuntimeException exception) { log.error("Unable to persist Playwright Runner event {}", record.getId(), exception); }
    }
    private Map<String, String> fields(MapRecord<String, Object, Object> record) {
        Map<String, String> result = new LinkedHashMap<>();
        record.getValue().forEach((key, value) -> { if (key != null && value != null) result.put(String.valueOf(key), String.valueOf(value)); });
        return Map.copyOf(result);
    }
    private void acknowledge(MapRecord<String, Object, Object> record) { streams.acknowledge(UiAutomationRunnerProtocol.EVENT_GROUP, record); }
    private synchronized void ensureGroup() {
        if (ready) return;
        try { streams.createGroup(UiAutomationRunnerProtocol.EVENT_STREAM, ReadOffset.latest(), UiAutomationRunnerProtocol.EVENT_GROUP); }
        catch (Exception first) {
            if (!contains(first, "BUSYGROUP")) {
                streams.add(StreamRecords.mapBacked(Map.of("bootstrap", "1")).withStreamKey(UiAutomationRunnerProtocol.EVENT_STREAM));
                try { streams.createGroup(UiAutomationRunnerProtocol.EVENT_STREAM, ReadOffset.latest(), UiAutomationRunnerProtocol.EVENT_GROUP); }
                catch (Exception second) { if (!contains(second, "BUSYGROUP")) throw second; }
            }
        }
        ready = true;
    }
    private boolean contains(Exception exception, String text) { for (Throwable item = exception; item != null; item = item.getCause()) if (item.getMessage() != null && item.getMessage().contains(text)) return true; return false; }
}
