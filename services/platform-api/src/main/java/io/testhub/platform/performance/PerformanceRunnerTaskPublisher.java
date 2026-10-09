package io.testhub.platform.performance;

import java.util.Map;
import org.springframework.data.redis.connection.stream.StreamRecords;
import io.testhub.platform.shared.runtime.RuntimeStreams;
import org.springframework.stereotype.Component;

@Component
public class PerformanceRunnerTaskPublisher {
    private final RuntimeStreams streams;

    public PerformanceRunnerTaskPublisher(RuntimeStreams streams) {
        this.streams = streams;
    }

    public void publish(String streamKey, Map<String, String> payload) {
        if (!PerformanceRunnerProtocol.TASK_STREAM.equals(streamKey) && !PerformanceRunnerProtocol.CANCEL_STREAM.equals(streamKey)) {
            throw new IllegalArgumentException("不支持的 JMeter Runner 内部流");
        }
        streams.add(StreamRecords.mapBacked(payload).withStreamKey(streamKey));
    }
}
