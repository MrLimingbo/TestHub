package io.testhub.platform.shared.runtime;

import java.time.Duration;
import java.util.Set;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@Profile("!desktop")
public class RedisRunnerRegistry implements RunnerRegistry {
    private static final DefaultRedisScript<Long> REMOVE_IF_MISSING = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 0 then
              return redis.call('SREM', KEYS[1], ARGV[1])
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    public RedisRunnerRegistry(StringRedisTemplate redis) { this.redis = redis; }
    public Set<String> members(String key) { return redis.opsForSet().members(key); }
    public String get(String key) { return redis.opsForValue().get(key); }
    public void remove(String key, String... members) { redis.opsForSet().remove(key, (Object[]) members); }
    public void removeIfMissing(String membersKey, String heartbeatKey, String member) {
        redis.execute(REMOVE_IF_MISSING, List.of(membersKey, heartbeatKey), member);
    }
    public void delete(String key) { redis.delete(key); }
    public void publish(String membersKey, String heartbeatKey, String member, String body, Duration ttl) {
        redis.opsForValue().set(heartbeatKey, body, ttl);
        redis.opsForSet().add(membersKey, member);
    }
}
