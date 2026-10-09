package io.testhub.platform.shared.runtime;

import java.time.Duration;
import java.util.Set;

public interface RunnerRegistry {
    Set<String> members(String key);
    String get(String heartbeatKey);
    void remove(String key, String... members);
    void publish(String membersKey, String heartbeatKey, String member, String body, Duration ttl);
    void delete(String heartbeatKey);
}
