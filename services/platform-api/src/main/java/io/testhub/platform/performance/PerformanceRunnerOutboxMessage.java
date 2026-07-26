package io.testhub.platform.performance;

import java.util.LinkedHashMap;
import java.util.Map;

public record PerformanceRunnerOutboxMessage(String id, String executionId, String messageType, String streamKey,
                                             Map<String, String> payload, int dispatchAttempts, int maxDispatchAttempts) {
    public PerformanceRunnerOutboxMessage {
        payload = Map.copyOf(new LinkedHashMap<>(payload));
    }
}
