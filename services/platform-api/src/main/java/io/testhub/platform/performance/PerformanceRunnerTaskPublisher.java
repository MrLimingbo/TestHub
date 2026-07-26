package io.testhub.platform.performance;

import java.util.Map;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class PerformanceRunnerTaskPublisher {
    private final StringRedisTemplate redis;

    public PerformanceRunnerTaskPublisher(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void publish(String streamKey, Map<String, String> payload) {
        if (!PerformanceRunnerProtocol.TASK_STREAM.equals(streamKey) && !PerformanceRunnerProtocol.CANCEL_STREAM.equals(streamKey)) {
            throw new IllegalArgumentException("不支持的 JMeter Runner 内部流");
        }
        redis.opsForStream().add(StreamRecords.mapBacked(payload).withStreamKey(streamKey));
    }
}
