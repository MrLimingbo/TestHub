package io.testhub.platform.apiautomation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.security.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiAutomationWebhookTriggerService {
    private static final Set<String> MANAGERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN");
    private static final Duration SIGNATURE_SKEW = Duration.ofMinutes(5);
    private static final Duration NONCE_TTL = Duration.ofMinutes(6);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String NONCE_PREFIX = "testhub:api-automation:webhook:nonce:";
    private final ApiAutomationWebhookTriggerRepository webhooks;
    private final ApiAutomationScenarioRepository scenarios;
    private final ApiAutomationExecutionService executions;
    private final AccessService access;
    private final AuditService audit;
    private final SecretCipher cipher;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public ApiAutomationWebhookTriggerService(ApiAutomationWebhookTriggerRepository webhooks,
                                               ApiAutomationScenarioRepository scenarios,
                                               ApiAutomationExecutionService executions, AccessService access,
                                               AuditService audit, SecretCipher cipher, StringRedisTemplate redis,
                                               ObjectMapper json) {
        this.webhooks = webhooks;
        this.scenarios = scenarios;
        this.executions = executions;
        this.access = access;
        this.audit = audit;
        this.cipher = cipher;
        this.redis = redis;
        this.json = json;
    }

    public List<ApiAutomationWebhookTrigger> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectParameterWrite(principal, projectId, MANAGERS);
        return webhooks.findByProject(projectId);
    }

    @Transactional
    public CreatedWebhookTrigger create(AuthenticatedPrincipal principal, String projectId, String scenarioId, String name) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, MANAGERS);
        if (name == null || name.isBlank() || name.trim().length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook 名称长度应在 1 到 160 字符之间");
        }
        scenarios.findDetail(projectId, scenarioId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "接口自动化场景不存在"));
        String secret = newSecret();
        String id = UlidCreator.getUlid().toString();
        webhooks.insert(id, project.organizationId(), projectId, scenarioId, name.trim(), cipher.encrypt(secret), principal.userId());
        ApiAutomationWebhookTrigger trigger = webhooks.findStoredById(id).orElseThrow().trigger();
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_WEBHOOK_CREATED",
                "API_AUTOMATION_WEBHOOK", id, Map.of("name", trigger.name(), "scenarioId", scenarioId));
        return new CreatedWebhookTrigger(trigger, secret);
    }

    @Transactional
    public ApiAutomationWebhookTrigger setEnabled(AuthenticatedPrincipal principal, String projectId, String webhookId,
                                                   boolean enabled) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, MANAGERS);
        ApiAutomationWebhookTrigger current = owned(projectId, webhookId);
        webhooks.setEnabled(webhookId, projectId, enabled);
        ApiAutomationWebhookTrigger result = webhooks.findStoredById(webhookId).orElseThrow().trigger();
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_WEBHOOK_" + (enabled ? "ENABLED" : "DISABLED"),
                "API_AUTOMATION_WEBHOOK", current.id(), Map.of("scenarioId", current.scenarioId()));
        return result;
    }

    @Transactional
    public void revoke(AuthenticatedPrincipal principal, String projectId, String webhookId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, MANAGERS);
        ApiAutomationWebhookTrigger current = owned(projectId, webhookId);
        webhooks.delete(webhookId, projectId);
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_WEBHOOK_REVOKED",
                "API_AUTOMATION_WEBHOOK", current.id(), Map.of("scenarioId", current.scenarioId()));
    }

    @Transactional
    public ApiAutomationExecution trigger(String webhookId, String timestamp, String nonce, String signature,
                                          String rawBody) {
        ApiAutomationWebhookTriggerRepository.StoredWebhookTrigger stored = webhooks.findStoredById(webhookId)
                .orElseThrow(this::unavailable);
        ApiAutomationWebhookTrigger trigger = stored.trigger();
        if (!trigger.enabled()) throw unavailable();
        Instant signedAt = parseTimestamp(timestamp);
        validateNonce(nonce);
        if (!validSignature(cipher.decrypt(stored.secretCiphertext()), timestamp, nonce, rawBody, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook 签名校验失败");
        }
        String nonceKey = NONCE_PREFIX + webhookId + ':' + sha256(nonce);
        Boolean accepted = redis.opsForValue().setIfAbsent(nonceKey, "1", NONCE_TTL);
        if (!Boolean.TRUE.equals(accepted)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook nonce 已使用");
        }
        int timeoutSeconds = timeout(rawBody);
        WebhookAutomationPrincipal principal = new WebhookAutomationPrincipal(trigger.createdBy(), trigger.projectId(), trigger.id());
        ApiAutomationExecution execution = executions.create(principal, trigger.projectId(), trigger.scenarioId(), timeoutSeconds,
                null, Map.of());
        audit.record(principal, trigger.organizationId(), trigger.projectId(), "API_AUTOMATION_WEBHOOK_TRIGGERED",
                "API_AUTOMATION_EXECUTION", execution.id(), Map.of("webhookId", trigger.id(), "scenarioId", trigger.scenarioId(),
                        "signedAt", signedAt.toString(), "timeoutSeconds", timeoutSeconds));
        return execution;
    }

    private ApiAutomationWebhookTrigger owned(String projectId, String webhookId) {
        ApiAutomationWebhookTrigger trigger = webhooks.findStoredById(webhookId).map(ApiAutomationWebhookTriggerRepository.StoredWebhookTrigger::trigger)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook 不存在"));
        if (!trigger.projectId().equals(projectId)) throw new AccessDeniedException("Webhook 不属于当前项目");
        return trigger;
    }

    private int timeout(String rawBody) {
        if (rawBody == null || rawBody.length() > 4096) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook 请求体不合法");
        }
        try {
            JsonNode root = json.readTree(rawBody);
            if (root == null || !root.isObject()) throw invalidPayload();
            Iterator<String> fields = root.fieldNames();
            while (fields.hasNext()) {
                if (!"timeoutSeconds".equals(fields.next())) throw invalidPayload();
            }
            JsonNode timeout = root.get("timeoutSeconds");
            if (timeout == null || timeout.isNull()) return 300;
            if (!timeout.canConvertToInt() || !timeout.isIntegralNumber()) throw invalidPayload();
            int value = timeout.intValue();
            if (value < 10 || value > 3600) throw invalidPayload();
            return value;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidPayload();
        }
    }

    private Instant parseTimestamp(String timestamp) {
        try {
            Instant value = Instant.parse(timestamp);
            if (Duration.between(value, Instant.now()).abs().compareTo(SIGNATURE_SKEW) > 0) throw unavailable();
            return value;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook 时间戳无效");
        }
    }

    private void validateNonce(String nonce) {
        if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook nonce 无效");
        }
    }

    private boolean validSignature(String secret, String timestamp, String nonce, String rawBody, String signature) {
        if (signature == null || !signature.matches("(?i)[0-9a-f]{64}")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((timestamp + "\n" + nonce + "\n" + rawBody).getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature));
        } catch (Exception exception) {
            throw new IllegalStateException("Webhook 签名校验不可用", exception);
        }
    }

    private String newSecret() {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return "thw_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Webhook nonce 保护不可用", exception);
        }
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook 不可用");
    }

    private ResponseStatusException invalidPayload() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook 请求体只允许 timeoutSeconds");
    }

    public record CreatedWebhookTrigger(ApiAutomationWebhookTrigger webhook, String secret) {
    }
}
