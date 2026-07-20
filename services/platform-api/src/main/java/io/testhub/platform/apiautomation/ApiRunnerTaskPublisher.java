package io.testhub.platform.apiautomation;

import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.stereotype.Component;

@Component
public class ApiRunnerTaskPublisher {
    private final StringRedisTemplate redis;

    public ApiRunnerTaskPublisher(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void publish(String streamKey, Map<String, String> payload) {
        if (!ApiRunnerProtocol.TASK_STREAM.equals(streamKey) && !ApiRunnerProtocol.CANCEL_STREAM.equals(streamKey)) {
            throw new IllegalArgumentException("不支持的 Runner 内部流");
        }
        redis.opsForStream().add(StreamRecords.mapBacked(payload).withStreamKey(streamKey));
    }
}
