package io.testhub.platform.apiautomation;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiRunnerInternalAuthenticationService {
    static final String CAPABILITIES_PATH = "/internal/api-runner/v1/capabilities";
    static final String RUNTIME_LEASES_PATH = "/internal/api-runner/v1/runtime-leases";
    static final String RUNTIME_LEASE_INSPECTION_PATH = "/internal/api-runner/v1/runtime-leases/inspect";
    static final String RUNTIME_CONFIGURATION_PATH = "/internal/api-runner/v1/runtime-configuration";
    private static final String SIGNATURE_PURPOSE = "testhub.api-runner.internal-auth.v1";
    private static final String NONCE_KEY_PREFIX = "testhub:runner:api:internal-nonce:v1:";
    private static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(60);
    private static final Duration NONCE_TTL = Duration.ofMinutes(2);
    private final ApiRunnerStatusService runners;
    private final StringRedisTemplate redis;
    private final byte[] signingKey;

    public ApiRunnerInternalAuthenticationService(ApiRunnerStatusService runners, StringRedisTemplate redis,
                                                  @Value("${testhub.runner.heartbeat-key:}") String signingKey) {
        this.runners = runners;
        this.redis = redis;
        this.signingKey = signingKey == null ? new byte[0] : signingKey.getBytes(StandardCharsets.UTF_8);
    }

    public String authenticateCapabilities(HttpServletRequest request) {
        return authenticate(request, "GET", CAPABILITIES_PATH);
    }

    public String authenticateRuntimeLease(HttpServletRequest request, String taskId, String executionId) {
        return authenticate(request, "POST", RUNTIME_LEASES_PATH, taskId, executionId);
    }

    public String authenticateRuntimeLeaseInspection(HttpServletRequest request, String taskId, String executionId,
                                                      String leaseFingerprint) {
        return authenticate(request, "POST", RUNTIME_LEASE_INSPECTION_PATH, taskId, executionId, leaseFingerprint);
    }

    public String authenticateRuntimeConfiguration(HttpServletRequest request, String taskId, String executionId,
                                                    String leaseFingerprint) {
        return authenticate(request, "POST", RUNTIME_CONFIGURATION_PATH, taskId, executionId, leaseFingerprint);
    }

    private String authenticate(HttpServletRequest request, String expectedMethod, String expectedPath, String... bindings) {
        if (signingKey.length == 0 || !expectedMethod.equals(request.getMethod()) || !expectedPath.equals(request.getRequestURI())) {
            throw rejected();
        }
        String runnerId = header(request, "X-TestHub-Runner-Id");
        String timestamp = header(request, "X-TestHub-Runner-Timestamp");
        String nonce = header(request, "X-TestHub-Runner-Nonce");
        String signature = header(request, "X-TestHub-Runner-Signature");
        if (!runnerId.matches("[A-Za-z0-9._-]{1,64}") || !nonce.matches("[A-Za-z0-9_-]{16,128}")
                || !signature.matches("[0-9a-f]{64}") || !recent(timestamp) || !runners.isVerified(runnerId)
                || !matches(expectedMethod, expectedPath, runnerId, timestamp, nonce, signature, bindings)) {
            throw rejected();
        }
        Boolean firstUse = redis.opsForValue().setIfAbsent(NONCE_KEY_PREFIX + runnerId + ":" + nonce, "1", NONCE_TTL);
        if (!Boolean.TRUE.equals(firstUse)) throw rejected();
        return runnerId;
    }

    private boolean recent(String value) {
        try {
            return Duration.between(Instant.parse(value), Instant.now()).abs().compareTo(MAX_CLOCK_SKEW) <= 0;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private boolean matches(String method, String path, String runnerId, String timestamp, String nonce, String supplied,
                            String... bindings) {
        try {
            String[] values = new String[6 + bindings.length];
            values[0] = SIGNATURE_PURPOSE;
            values[1] = method;
            values[2] = path;
            values[3] = runnerId;
            values[4] = timestamp;
            values[5] = nonce;
            System.arraycopy(bindings, 0, values, 6, bindings.length);
            String payload = String.join("\n", values);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            String expected = java.util.HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) {
            return false;
        }
    }

    private String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? "" : value;
    }

    private ResponseStatusException rejected() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runner 内部请求认证失败");
    }
}
