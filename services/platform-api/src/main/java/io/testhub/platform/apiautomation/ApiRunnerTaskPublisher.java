package io.testhub.platform.apiautomation;

import java.util.Map;
import io.testhub.platform.shared.runtime.RuntimeStreams;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.stereotype.Component;

@Component
public class ApiRunnerTaskPublisher {
    private final RuntimeStreams streams;

    public ApiRunnerTaskPublisher(RuntimeStreams streams) {
        this.streams = streams;
    }

    public void publish(String streamKey, Map<String, String> payload) {
        if (!ApiRunnerProtocol.TASK_STREAM.equals(streamKey) && !ApiRunnerProtocol.CANCEL_STREAM.equals(streamKey)) {
            throw new IllegalArgumentException("不支持的 Runner 内部流");
        }
        streams.add(StreamRecords.mapBacked(payload).withStreamKey(streamKey));
    }
}
