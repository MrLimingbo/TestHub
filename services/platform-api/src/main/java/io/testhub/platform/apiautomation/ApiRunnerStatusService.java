package io.testhub.platform.apiautomation;

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
import io.testhub.platform.shared.runtime.RunnerRegistry;
import org.springframework.stereotype.Service;

@Service
public class ApiRunnerStatusService {
    static final String MEMBER_KEY = "testhub:runner:api:members:v1";
    static final String HEARTBEAT_KEY_PREFIX = "testhub:runner:api:heartbeat:v1:";
    private final AccessService access;
    private final RunnerRegistry registry;
    private final ObjectMapper objectMapper;
    private final byte[] heartbeatKey;

    public ApiRunnerStatusService(AccessService access, RunnerRegistry registry, ObjectMapper objectMapper,
                                  @Value("${testhub.runner.heartbeat-key:}") String heartbeatKey) {
        this.access = access;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.heartbeatKey = heartbeatKey == null ? new byte[0] : heartbeatKey.getBytes(StandardCharsets.UTF_8);
    }

    public List<ApiRunnerStatus> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        Set<String> runnerIds = registry.members(MEMBER_KEY);
        if (runnerIds == null || runnerIds.isEmpty()) return List.of();
        return runnerIds.stream().map(this::read).flatMap(java.util.Optional::stream)
                .sorted(Comparator.comparing(ApiRunnerStatus::heartbeatAt).reversed()).toList();
    }

    public boolean isVerified(String runnerId) {
        return read(runnerId).map(ApiRunnerStatus::trusted).orElse(false);
    }

    public boolean isVerifiedSince(String runnerId, Instant startedAfter) {
        return read(runnerId).map(status -> status.trusted() && !status.startedAt().isBefore(startedAfter)).orElse(false);
    }

    public boolean isTrustedExecutionEnabled(String runnerId) {
        return read(runnerId).map(status -> status.trusted() && status.executionEnabled()).orElse(false);
    }

    private java.util.Optional<ApiRunnerStatus> read(String runnerId) {
        if (runnerId == null || !runnerId.matches("[A-Za-z0-9._-]{1,64}")) return java.util.Optional.empty();
        String body = registry.get(HEARTBEAT_KEY_PREFIX + runnerId);
        if (body == null) {
            registry.removeIfMissing(MEMBER_KEY, HEARTBEAT_KEY_PREFIX + runnerId, runnerId);
            return java.util.Optional.empty();
        }
        try {
            JsonNode value = objectMapper.readTree(body);
            if (!runnerId.equals(text(value, "runnerId")) || !"api".equals(text(value, "runnerType"))
                    || !"1".equals(text(value, "protocolVersion")) || !value.path("executionEnabled").isBoolean()) {
                return java.util.Optional.empty();
            }
            String version = text(value, "runnerVersion");
            if (version.isBlank() || version.length() > 64) return java.util.Optional.empty();
            String trustStatus = trustStatus(value);
            return java.util.Optional.of(new ApiRunnerStatus(runnerId, "api", "1", version,
                    value.path("executionEnabled").asBoolean(), "VERIFIED".equals(trustStatus), trustStatus,
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
        String payload = String.join("\n", text(value, "runnerId"), text(value, "runnerType"), text(value, "protocolVersion"),
                text(value, "runnerVersion"), String.valueOf(value.path("executionEnabled").asBoolean()),
                text(value, "startedAt"), text(value, "heartbeatAt"));
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
        try {
            return Instant.parse(text(value, field));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Runner heartbeat time is invalid", exception);
        }
    }
}
