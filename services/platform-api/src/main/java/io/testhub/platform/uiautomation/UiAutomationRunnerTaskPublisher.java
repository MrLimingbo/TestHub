package io.testhub.platform.uiautomation;

import java.util.Map;
import org.springframework.data.redis.connection.stream.StreamRecords;
import io.testhub.platform.shared.runtime.RuntimeStreams;
import org.springframework.stereotype.Component;

@Component
public class UiAutomationRunnerTaskPublisher {
    private final RuntimeStreams streams;
    public UiAutomationRunnerTaskPublisher(RuntimeStreams streams) { this.streams = streams; }
    public void publish(String streamKey, Map<String, String> payload) {
        if (!UiAutomationRunnerProtocol.TASK_STREAM.equals(streamKey) && !UiAutomationRunnerProtocol.CANCEL_STREAM.equals(streamKey)) {
            throw new IllegalArgumentException("不支持的 Playwright Runner 内部流");
        }
        streams.add(StreamRecords.mapBacked(payload).withStreamKey(streamKey));
    }
}
