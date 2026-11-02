package io.testhub.platform.apiautomation;

import java.util.LinkedHashMap;
import java.util.Map;

public record AutomationStep(String id, String type, String name, @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = WaitStepJson.Config.class) Map<String, Object> config) {
    public AutomationStep {
        config = config == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(config));
    }
}
