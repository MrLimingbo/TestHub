package io.testhub.platform.performance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import io.testhub.platform.shared.runtime.ExpiringStateStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PerformanceRunnerInternalAuthenticationService {
    static final String RUNTIME_CONFIGURATION_PATH = "/internal/performance-runner/v1/runtime-configuration";
    static final String ARTIFACT_UPLOAD_PATH = "/internal/performance-runner/v1/artifacts";
    private static final String PURPOSE = "testhub.performance-runner.internal-auth.v1";
    private static final String NONCE_KEY_PREFIX = "testhub:runner:jmeter:internal-nonce:v1:";
    private static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(60);
    private static final Duration NONCE_TTL = Duration.ofMinutes(2);
    private final PerformanceRunnerStatusService runners;
    private final ExpiringStateStore state;
    private final byte[] signingKey;

    public PerformanceRunnerInternalAuthenticationService(PerformanceRunnerStatusService runners, ExpiringStateStore state,
                                                          @Value("${testhub.performance-runner.heartbeat-key:}") String signingKey) {
        this.runners = runners;
        this.state = state;
        this.signingKey = signingKey == null ? new byte[0] : signingKey.getBytes(StandardCharsets.UTF_8);
    }

    public String authenticateRuntimeConfiguration(HttpServletRequest request, String taskId, String executionId) {
        return authenticate(request, RUNTIME_CONFIGURATION_PATH, List.of(taskId, executionId));
    }

    public String authenticateArtifactUpload(HttpServletRequest request, PerformanceTestExecutionArtifactService.Upload upload) {
        return authenticate(request, ARTIFACT_UPLOAD_PATH, List.of(upload.taskId(), upload.executionId(), upload.artifactType(),
                String.valueOf(upload.sizeBytes()), upload.checksum()));
    }

    private String authenticate(HttpServletRequest request, String path, List<String> bindings) {
        if (signingKey.length == 0 || !"POST".equals(request.getMethod()) || !path.equals(request.getRequestURI())) throw rejected();
        String runnerId = header(request, "X-TestHub-Runner-Id");
        String timestamp = header(request, "X-TestHub-Runner-Timestamp");
        String nonce = header(request, "X-TestHub-Runner-Nonce");
        String signature = header(request, "X-TestHub-Runner-Signature");
        if (!runnerId.matches("[A-Za-z0-9._-]{1,64}") || !nonce.matches("[A-Za-z0-9_-]{16,128}")
                || !signature.matches("[0-9a-f]{64}") || !recent(timestamp) || !runners.isTrustedExecutionEnabled(runnerId)
                || !matches(path, runnerId, timestamp, nonce, signature, bindings)) throw rejected();
        Boolean firstUse = state.putIfAbsent(NONCE_KEY_PREFIX + runnerId + ":" + nonce, "1", NONCE_TTL);
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

    private boolean matches(String path, String runnerId, String timestamp, String nonce, String supplied, List<String> bindings) {
        try {
            java.util.ArrayList<String> fields = new java.util.ArrayList<>(List.of(PURPOSE, "POST", path, runnerId, timestamp, nonce));
            fields.addAll(bindings);
            String payload = String.join("\n", fields);
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
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JMeter Runner 内部请求认证失败");
    }
}
