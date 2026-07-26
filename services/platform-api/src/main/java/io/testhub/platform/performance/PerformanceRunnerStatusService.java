package io.testhub.platform.performance;

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
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class PerformanceRunnerStatusService {
    static final String MEMBER_KEY = "testhub:runner:jmeter:members:v1";
    static final String HEARTBEAT_KEY_PREFIX = "testhub:runner:jmeter:heartbeat:v1:";
    private final AccessService access;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final byte[] heartbeatKey;

    public PerformanceRunnerStatusService(AccessService access, StringRedisTemplate redis, ObjectMapper objectMapper,
                                          @Value("${testhub.performance-runner.heartbeat-key:}") String heartbeatKey) {
        this.access = access;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.heartbeatKey = heartbeatKey == null ? new byte[0] : heartbeatKey.getBytes(StandardCharsets.UTF_8);
    }

    public List<PerformanceRunnerStatus> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        Set<String> runnerIds = redis.opsForSet().members(MEMBER_KEY);
        if (runnerIds == null || runnerIds.isEmpty()) return List.of();
        return runnerIds.stream().map(this::read).flatMap(java.util.Optional::stream)
                .sorted(Comparator.comparing(PerformanceRunnerStatus::heartbeatAt).reversed()).toList();
    }

    public boolean isTrustedExecutionEnabled(String runnerId) {
        return read(runnerId).map(status -> status.trusted() && status.executionEnabled()).orElse(false);
    }

    public boolean isLive(String runnerId) {
        return read(runnerId).map(PerformanceRunnerStatus::trusted).orElse(false);
    }

    public boolean hasAvailableCapacity(String runnerId) {
        return read(runnerId).map(status -> status.trusted() && status.executionEnabled()
                && status.activeTaskCount() < status.maxConcurrentTasks()).orElse(false);
    }

    private java.util.Optional<PerformanceRunnerStatus> read(String runnerId) {
        if (runnerId == null || !runnerId.matches("[A-Za-z0-9._-]{1,64}")) return java.util.Optional.empty();
        String body = redis.opsForValue().get(HEARTBEAT_KEY_PREFIX + runnerId);
        if (body == null) {
            redis.opsForSet().remove(MEMBER_KEY, runnerId);
            return java.util.Optional.empty();
        }
        try {
            JsonNode value = objectMapper.readTree(body);
            if (!runnerId.equals(text(value, "runnerId")) || !PerformanceRunnerProtocol.RUNNER_TYPE.equals(text(value, "runnerType"))
                    || !PerformanceRunnerProtocol.VERSION.equals(text(value, "protocolVersion"))
                    || !value.path("executionEnabled").isBoolean()) return java.util.Optional.empty();
            String version = text(value, "runnerVersion");
            if (version.isBlank() || version.length() > 64) return java.util.Optional.empty();
            int maxConcurrentTasks = positive(value, "maxConcurrentTasks", 1);
            int activeTaskCount = nonNegative(value, "activeTaskCount", 0);
            if (maxConcurrentTasks > 8 || activeTaskCount > maxConcurrentTasks) return java.util.Optional.empty();
            String trustStatus = trustStatus(value);
            return java.util.Optional.of(new PerformanceRunnerStatus(runnerId, PerformanceRunnerProtocol.RUNNER_TYPE,
                    PerformanceRunnerProtocol.VERSION, version, value.path("executionEnabled").asBoolean(),
                    "VERIFIED".equals(trustStatus), trustStatus, maxConcurrentTasks, activeTaskCount,
                    instant(value, "startedAt"), instant(value, "heartbeatAt")));
        } catch (Exception exception) {
            return java.util.Optional.empty();
        }
    }

    private String trustStatus(JsonNode value) {
        if (heartbeatKey.length == 0) return "NOT_CONFIGURED";
        if (!"1".equals(text(value, "trustVersion"))) return "UNVERIFIED";
        String signature = text(value, "signature");
        if (!signature.matches("[0-9a-f]{64}")) return "UNVERIFIED";
        boolean hasCapacity = value.has("maxConcurrentTasks") || value.has("activeTaskCount");
        if (hasCapacity && (!value.has("maxConcurrentTasks") || !value.has("activeTaskCount"))) return "UNVERIFIED";
        String payload = String.join("\n", text(value, "runnerId"), text(value, "runnerType"), text(value, "protocolVersion"),
                text(value, "runnerVersion"), String.valueOf(value.path("executionEnabled").asBoolean()),
                text(value, "startedAt"), text(value, "heartbeatAt"));
        if (hasCapacity) payload += "\n" + value.path("maxConcurrentTasks").asInt() + "\n" + value.path("activeTaskCount").asInt();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(heartbeatKey, "HmacSHA256"));
            String expected = java.util.HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII))
                    ? "VERIFIED" : "UNVERIFIED";
        } catch (Exception exception) {
            return "UNVERIFIED";
        }
    }

    private String text(JsonNode value, String field) {
        JsonNode node = value.path(field);
        return node.isTextual() ? node.asText() : "";
    }

    private Instant instant(JsonNode value, String field) {
        try { return Instant.parse(text(value, field)); }
        catch (DateTimeParseException exception) { throw new IllegalArgumentException("JMeter Runner 心跳时间不合法", exception); }
    }

    private int positive(JsonNode value, String field, int fallback) {
        if (!value.has(field)) return fallback;
        JsonNode node = value.path(field);
        return node.isIntegralNumber() && node.canConvertToInt() && node.asInt() >= 1 ? node.asInt() : -1;
    }

    private int nonNegative(JsonNode value, String field, int fallback) {
        if (!value.has(field)) return fallback;
        JsonNode node = value.path(field);
        return node.isIntegralNumber() && node.canConvertToInt() && node.asInt() >= 0 ? node.asInt() : -1;
    }
}
