package io.testhub.platform.shared.runtime;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!desktop")
public class RedisRuntimeStreams implements RuntimeStreams {
    private final StringRedisTemplate redis;
    public RedisRuntimeStreams(StringRedisTemplate redis) { this.redis = redis; }
    public RecordId add(MapRecord<String, ?, ?> record) { return redis.opsForStream().add(record); }
    public String createGroup(String stream, ReadOffset offset, String group) { return redis.opsForStream().createGroup(stream, offset, group); }
    public List<MapRecord<String, Object, Object>> read(Consumer consumer, StreamReadOptions options, StreamOffset<String> stream) {
        return redis.opsForStream().read(consumer, options, stream);
    }
    public Long acknowledge(String group, MapRecord<String, ?, ?> record) { return redis.opsForStream().acknowledge(group, record); }
    public String label() { return "Redis"; }
    public void check() {
        try (var connection = redis.getConnectionFactory().getConnection()) {
            if (!"PONG".equals(connection.ping())) throw new IllegalStateException("Redis 未就绪");
        }
    }
}
