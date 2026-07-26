package io.testhub.platform.uiautomation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class UiAutomationRunnerStatusService {
    static final String MEMBER_KEY = "testhub:runner:playwright:members:v1";
    static final String HEARTBEAT_KEY_PREFIX = "testhub:runner:playwright:heartbeat:v1:";
    private final AccessService access;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final byte[] key;

    public UiAutomationRunnerStatusService(AccessService access, StringRedisTemplate redis, ObjectMapper json,
                                           @Value("${testhub.ui-automation-runner.heartbeat-key:}") String key) {
        this.access = access;
        this.redis = redis;
        this.json = json;
        this.key = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    }

    public List<UiAutomationRunnerStatus> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        Set<String> ids = redis.opsForSet().members(MEMBER_KEY);
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream().map(this::read).flatMap(Optional::stream)
                .sorted(Comparator.comparing(UiAutomationRunnerStatus::heartbeatAt).reversed()).toList();
    }

    public boolean isTrustedExecutionEnabled(String runnerId) {
        return read(runnerId).map(status -> status.trusted() && status.executionEnabled()).orElse(false);
    }

    private Optional<UiAutomationRunnerStatus> read(String runnerId) {
        if (runnerId == null || !runnerId.matches("[A-Za-z0-9._-]{1,64}")) return Optional.empty();
        String body = redis.opsForValue().get(HEARTBEAT_KEY_PREFIX + runnerId);
        if (body == null) { redis.opsForSet().remove(MEMBER_KEY, runnerId); return Optional.empty(); }
        try {
            JsonNode value = json.readTree(body);
            if (!runnerId.equals(text(value, "runnerId")) || !UiAutomationRunnerProtocol.RUNNER_TYPE.equals(text(value, "runnerType"))
                    || !UiAutomationRunnerProtocol.VERSION.equals(text(value, "protocolVersion")) || !value.path("executionEnabled").isBoolean()) return Optional.empty();
            String version = text(value, "runnerVersion");
            int max = number(value, "maxConcurrentTasks", 1, 1, 2);
            int active = number(value, "activeTaskCount", 0, 0, max);
            if (version.isBlank() || version.length() > 64 || max < 0 || active < 0) return Optional.empty();
            String trust = trust(value);
            return Optional.of(new UiAutomationRunnerStatus(runnerId, UiAutomationRunnerProtocol.RUNNER_TYPE,
                    UiAutomationRunnerProtocol.VERSION, version, value.path("executionEnabled").asBoolean(), "VERIFIED".equals(trust),
                    trust, max, active, instant(value, "startedAt"), instant(value, "heartbeatAt")));
        } catch (Exception exception) { return Optional.empty(); }
    }

    private String trust(JsonNode value) {
        if (key.length == 0) return "NOT_CONFIGURED";
        if (!"1".equals(text(value, "trustVersion")) || !text(value, "signature").matches("[0-9a-f]{64}")) return "UNVERIFIED";
        String payload = String.join("\n", text(value, "runnerId"), text(value, "runnerType"), text(value, "protocolVersion"),
                text(value, "runnerVersion"), String.valueOf(value.path("executionEnabled").asBoolean()), text(value, "startedAt"),
                text(value, "heartbeatAt"), String.valueOf(value.path("maxConcurrentTasks").asInt()), String.valueOf(value.path("activeTaskCount").asInt()));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String expected = java.util.HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), text(value, "signature").getBytes(StandardCharsets.US_ASCII)) ? "VERIFIED" : "UNVERIFIED";
        } catch (Exception exception) { return "UNVERIFIED"; }
    }

    private String text(JsonNode value, String field) { return value.path(field).isTextual() ? value.path(field).asText() : ""; }
    private int number(JsonNode value, String field, int fallback, int min, int max) {
        if (!value.has(field)) return fallback;
        JsonNode node = value.path(field);
        return node.isIntegralNumber() && node.canConvertToInt() && node.asInt() >= min && node.asInt() <= max ? node.asInt() : -1;
    }
    private Instant instant(JsonNode value, String field) {
        try { return Instant.parse(text(value, field)); } catch (DateTimeParseException exception) { throw new IllegalArgumentException("Runner 心跳时间不合法", exception); }
    }
}
