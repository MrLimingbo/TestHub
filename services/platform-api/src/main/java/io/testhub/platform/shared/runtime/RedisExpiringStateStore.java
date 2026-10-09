package io.testhub.platform.shared.runtime;

import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@Profile("!desktop")
public class RedisExpiringStateStore implements ExpiringStateStore {
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);
    private final StringRedisTemplate redis;

    public RedisExpiringStateStore(StringRedisTemplate redis) { this.redis = redis; }

    @Override public String get(String key) { return redis.opsForValue().get(key); }

    @Override public boolean putIfAbsent(String key, String value, Duration lifetime) {
        requireLifetime(lifetime);
        Boolean result = redis.opsForValue().setIfAbsent(key, value, lifetime);
        if (result == null) throw new IllegalStateException("Runtime state admission returned no result");
        return result;
    }

    @Override public long incrementWindow(String key, Duration lifetime) {
        requireLifetime(lifetime);
        Long result = redis.execute(INCREMENT, List.of(key), Long.toString(lifetime.toMillis()));
        if (result == null) throw new IllegalStateException("Runtime state counter returned no result");
        return result;
    }

    @Override public void delete(String key) { redis.delete(key); }

    public static void requireLifetime(Duration lifetime) {
        if (lifetime == null || lifetime.toMillis() <= 0) throw new IllegalArgumentException("State lifetime must be positive");
    }
}
