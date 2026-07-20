package io.testhub.platform.apiautomation;

import java.util.LinkedHashMap;
import java.util.Map;

public record ApiRunnerOutboxMessage(String id, String executionId, String messageType, String streamKey,
                                     Map<String, String> payload, int dispatchAttempts, int maxDispatchAttempts) {
    public ApiRunnerOutboxMessage {
        payload = Map.copyOf(new LinkedHashMap<>(payload));
    }
}
