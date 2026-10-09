package io.testhub.platform.shared.runtime;

import java.time.Duration;

/** Security coordination state: atomic nonce/lease admission and fixed-window counters. */
public interface ExpiringStateStore {
    String get(String key);
    boolean putIfAbsent(String key, String value, Duration lifetime);
    long incrementWindow(String key, Duration lifetime);
    void delete(String key);
}
