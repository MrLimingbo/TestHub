package io.testhub.platform.uiautomation;

import java.util.Map;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class UiAutomationRunnerTaskPublisher {
    private final StringRedisTemplate redis;
    public UiAutomationRunnerTaskPublisher(StringRedisTemplate redis) { this.redis = redis; }
    public void publish(String streamKey, Map<String, String> payload) {
        if (!UiAutomationRunnerProtocol.TASK_STREAM.equals(streamKey) && !UiAutomationRunnerProtocol.CANCEL_STREAM.equals(streamKey)) {
            throw new IllegalArgumentException("不支持的 Playwright Runner 内部流");
        }
        redis.opsForStream().add(StreamRecords.mapBacked(payload).withStreamKey(streamKey));
    }
}
