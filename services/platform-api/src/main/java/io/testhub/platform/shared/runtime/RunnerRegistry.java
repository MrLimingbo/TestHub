package io.testhub.platform.shared.runtime;

import java.time.Duration;
import java.util.Set;

public interface RunnerRegistry {
    Set<String> members(String key);
    String get(String heartbeatKey);
    void remove(String key, String... members);
    // A missing read can become stale before cleanup; never remove a newly published heartbeat/member.
    void removeIfMissing(String membersKey, String heartbeatKey, String member);
    void publish(String membersKey, String heartbeatKey, String member, String body, Duration ttl);
    void delete(String heartbeatKey);
}
