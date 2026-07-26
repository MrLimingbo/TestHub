package io.testhub.platform.uiautomation;

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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UiAutomationRunnerInternalAuthenticationService {
    static final String RUNTIME_CONFIGURATION_PATH = "/internal/ui-automation-runner/v1/runtime-configuration";
    static final String ARTIFACT_UPLOAD_PATH = "/internal/ui-automation-runner/v1/artifacts";
    private static final String PURPOSE = "testhub.ui-automation-runner.internal-auth.v1";
    private static final Duration MAX_SKEW = Duration.ofSeconds(60);
    private final UiAutomationRunnerStatusService runners;
    private final StringRedisTemplate redis;
    private final byte[] key;

    public UiAutomationRunnerInternalAuthenticationService(UiAutomationRunnerStatusService runners, StringRedisTemplate redis,
                                                           @Value("${testhub.ui-automation-runner.heartbeat-key:}") String key) {
        this.runners = runners;
        this.redis = redis;
        this.key = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    }
    public String authenticateRuntime(HttpServletRequest request, String taskId, String executionId) {
        return authenticate(request, RUNTIME_CONFIGURATION_PATH, List.of(taskId, executionId));
    }
    public String authenticateArtifact(HttpServletRequest request, UiAutomationExecutionArtifactService.Upload upload) {
        return authenticate(request, ARTIFACT_UPLOAD_PATH, List.of(upload.taskId(), upload.executionId(), upload.artifactType(), String.valueOf(upload.sizeBytes()), upload.checksum()));
    }
    private String authenticate(HttpServletRequest request, String path, List<String> bindings) {
        if (key.length == 0 || !"POST".equals(request.getMethod()) || !path.equals(request.getRequestURI())) throw rejected();
        String runner = header(request, "X-TestHub-Runner-Id");
        String timestamp = header(request, "X-TestHub-Runner-Timestamp");
        String nonce = header(request, "X-TestHub-Runner-Nonce");
        String signature = header(request, "X-TestHub-Runner-Signature");
        if (!runner.matches("[A-Za-z0-9._-]{1,64}") || !nonce.matches("[A-Za-z0-9_-]{16,128}") || !signature.matches("[0-9a-f]{64}")
                || !recent(timestamp) || !runners.isTrustedExecutionEnabled(runner) || !matches(path, runner, timestamp, nonce, signature, bindings)) throw rejected();
        Boolean newNonce = redis.opsForValue().setIfAbsent("testhub:runner:playwright:internal-nonce:v1:" + runner + ":" + nonce, "1", Duration.ofMinutes(2));
        if (!Boolean.TRUE.equals(newNonce)) throw rejected();
        return runner;
    }
    private boolean recent(String value) { try { return Duration.between(Instant.parse(value), Instant.now()).abs().compareTo(MAX_SKEW) <= 0; } catch (DateTimeParseException exception) { return false; } }
    private boolean matches(String path, String runner, String timestamp, String nonce, String signature, List<String> bindings) {
        try {
            java.util.ArrayList<String> payload = new java.util.ArrayList<>(List.of(PURPOSE, "POST", path, runner, timestamp, nonce));
            payload.addAll(bindings);
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String expected = java.util.HexFormat.of().formatHex(mac.doFinal(String.join("\n", payload).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) { return false; }
    }
    private String header(HttpServletRequest request, String name) { String value = request.getHeader(name); return value == null ? "" : value; }
    private ResponseStatusException rejected() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Playwright Runner 内部请求认证失败"); }
}
