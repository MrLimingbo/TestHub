package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ApiRunnerRuntimeLeaseFlowTests {
    private static final String RUNNER_ID = "api-runner-runtime-lease-test";
    private static final String MEMBERS = "testhub:runner:api:members:v1";
    private static final String HEARTBEAT = "testhub:runner:api:heartbeat:v1:" + RUNNER_ID;
    private static final String NONCE_PREFIX = "testhub:runner:api:internal-nonce:v1:" + RUNNER_ID + ":";
    private static final String LEASE_PREFIX = "testhub:runner:api:runtime-lease:v1:";
    private static final String PATH = "/internal/api-runner/v1/runtime-leases";
    private static final String INSPECTION_PATH = "/internal/api-runner/v1/runtime-leases/inspect";
    private static final String HEARTBEAT_KEY = "testhub-runner-contract-test-signing-key-2026-000000000000000000000000";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcClient jdbc;
    private Fixture fixture;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("testhub.runner.heartbeat-key", () -> HEARTBEAT_KEY);
    }

    @AfterEach
    void clean() {
        redis.delete(HEARTBEAT);
        redis.opsForSet().remove(MEMBERS, RUNNER_ID);
        redis.delete(NONCE_PREFIX + "nonce_lease_0123456789abc");
        redis.delete(NONCE_PREFIX + "nonce_lease_abcdef0123456");
        redis.delete(NONCE_PREFIX + "nonce_lease_bad012345678");
        redis.delete(NONCE_PREFIX + "nonce_inspect_0123456789");
        redis.delete(NONCE_PREFIX + "nonce_inspect_bad012345");
        if (fixture != null) {
            redis.delete(LEASE_PREFIX + fixture.executionId());
            jdbc.sql("DELETE FROM audit_log WHERE action = 'API_AUTOMATION_RUNTIME_LEASE_ISSUED' AND resource_id = :executionId")
                    .param("executionId", fixture.executionId()).update();
            jdbc.sql("DELETE FROM audit_log WHERE action = 'API_AUTOMATION_RUNTIME_LEASE_INSPECTED' AND resource_id = :executionId")
                    .param("executionId", fixture.executionId()).update();
            jdbc.sql("DELETE FROM api_automation_execution WHERE id = :executionId").param("executionId", fixture.executionId()).update();
            jdbc.sql("DELETE FROM api_automation_scenario WHERE id = :scenarioId").param("scenarioId", fixture.scenarioId()).update();
        }
    }

    @Test
    void issuesOneShortLeaseForOnlyASentNonExpiredTaskAndNeverPersistsTheLeaseValue() throws Exception {
        fixture = createSentExecution();
        writeVerifiedHeartbeat();
        String body = objectMapper.writeValueAsString(Map.of("taskId", fixture.taskId(), "executionId", fixture.executionId()));

        MvcResult response = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                        .headers(headers(fixture.taskId(), fixture.executionId(), "nonce_lease_0123456789abc", false)))
                .andExpect(status().isOk()).andReturn();
        JsonNode lease = json(response).path("data");
        String leaseValue = lease.path("lease").asText();
        org.junit.jupiter.api.Assertions.assertTrue(leaseValue.matches("[A-Za-z0-9_-]{43}"));
        org.junit.jupiter.api.Assertions.assertEquals(fixture.executionId(), lease.path("executionId").asText());
        org.junit.jupiter.api.Assertions.assertEquals(fixture.taskId(), lease.path("taskId").asText());
        org.junit.jupiter.api.Assertions.assertEquals(RUNNER_ID, lease.path("runnerId").asText());
        org.junit.jupiter.api.Assertions.assertEquals("RUNTIME_CONFIGURATION_DISABLED", lease.path("purpose").asText());
        org.junit.jupiter.api.Assertions.assertTrue(Instant.parse(lease.path("expiresAt").asText()).isBefore(Instant.now().plus(Duration.ofMinutes(6))));

        String stored = redis.opsForValue().get(LEASE_PREFIX + fixture.executionId());
        org.junit.jupiter.api.Assertions.assertNotNull(stored);
        org.junit.jupiter.api.Assertions.assertFalse(stored.contains(leaseValue));
        org.junit.jupiter.api.Assertions.assertTrue(stored.contains("leaseHash"));
        org.junit.jupiter.api.Assertions.assertFalse(response.getResponse().getContentAsString().contains("environmentCode"));

        String inspectionBody = objectMapper.writeValueAsString(Map.of("taskId", fixture.taskId(),
                "executionId", fixture.executionId(), "lease", leaseValue));
        MvcResult inspection = mvc.perform(post(INSPECTION_PATH).contentType(MediaType.APPLICATION_JSON).content(inspectionBody)
                        .headers(inspectionHeaders(fixture.taskId(), fixture.executionId(), leaseValue, "nonce_inspect_0123456789", false)))
                .andExpect(status().isOk()).andReturn();
        JsonNode index = json(inspection).path("data");
        org.junit.jupiter.api.Assertions.assertEquals("test", index.path("environmentCode").asText());
        org.junit.jupiter.api.Assertions.assertEquals(1, index.path("scenarioVersion").asInt());
        org.junit.jupiter.api.Assertions.assertEquals("RUNTIME_CONFIGURATION_DISABLED", index.path("purpose").asText());
        org.junit.jupiter.api.Assertions.assertEquals("lease_metadata_only", index.at("/variableReferences/0/name").asText());
        org.junit.jupiter.api.Assertions.assertEquals("ENVIRONMENT_SECRET", index.at("/variableReferences/0/source").asText());
        org.junit.jupiter.api.Assertions.assertTrue(index.at("/variableReferences/0/secret").asBoolean());
        org.junit.jupiter.api.Assertions.assertTrue(index.path("runtimeSnapshotAvailable").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals("1".repeat(64), index.path("runtimeSnapshotChecksum").asText());
        org.junit.jupiter.api.Assertions.assertEquals(1, index.path("runtimeVariableCount").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(1, index.path("withheldSecretCount").asInt());
        org.junit.jupiter.api.Assertions.assertFalse(inspection.getResponse().getContentAsString().contains(leaseValue));
        org.junit.jupiter.api.Assertions.assertFalse(inspection.getResponse().getContentAsString()
                .contains("lease-runtime-value-must-not-leak"));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                        .headers(headers(fixture.taskId(), fixture.executionId(), "nonce_lease_abcdef0123456", false)))
                .andExpect(status().isConflict());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                        .headers(headers(fixture.taskId(), fixture.executionId(), "nonce_lease_bad012345678", true)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(INSPECTION_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(inspectionBody.replace(leaseValue, "A".repeat(43)))
                        .headers(inspectionHeaders(fixture.taskId(), fixture.executionId(), leaseValue, "nonce_inspect_bad012345", false)))
                .andExpect(status().isUnauthorized());
    }

    private Fixture createSentExecution() {
        ProjectScope project = jdbc.sql("SELECT id, organization_id FROM project ORDER BY id LIMIT 1")
                .query((rs, row) -> new ProjectScope(rs.getString("id"), rs.getString("organization_id"))).single();
        String scenarioId = UlidCreator.getUlid().toString();
        String executionId = UlidCreator.getUlid().toString();
        String taskId = UlidCreator.getUlid().toString();
        jdbc.sql("""
                INSERT INTO api_automation_scenario
                    (id, organization_id, project_id, name, environment_code, failure_strategy, steps, variables, created_by)
                VALUES (:id, :organizationId, :projectId, :name, 'test', 'STOP', CAST('[]' AS JSON), CAST('{}' AS JSON), :createdBy)
                """).param("id", scenarioId).param("organizationId", project.organizationId()).param("projectId", project.id())
                .param("name", "Runner runtime lease " + scenarioId).param("createdBy", project.id()).update();
        jdbc.sql("""
                INSERT INTO api_automation_execution
                    (id, task_id, organization_id, project_id, scenario_id, scenario_name, scenario_version, status,
                     manifest_ref, requested_by, requested_at, deadline_at)
                VALUES (:id, :taskId, :organizationId, :projectId, :scenarioId, :scenarioName, 1, 'QUEUED',
                        :manifestRef, :requestedBy, :requestedAt, :deadlineAt)
                """).param("id", executionId).param("taskId", taskId).param("organizationId", project.organizationId())
                .param("projectId", project.id()).param("scenarioId", scenarioId).param("scenarioName", "Runner runtime lease")
                .param("manifestRef", "api-automation/manifests/" + scenarioId + "/v1.json").param("requestedBy", project.id())
                .param("requestedAt", java.sql.Timestamp.from(Instant.now())).param("deadlineAt", java.sql.Timestamp.from(Instant.now().plus(Duration.ofMinutes(5)))).update();
        jdbc.sql("""
                INSERT INTO api_runner_outbox (id, execution_id, message_type, stream_key, payload, status, sent_at)
                VALUES (:id, :executionId, 'TASK', :streamKey, CAST('{}' AS JSON), 'SENT', CURRENT_TIMESTAMP(3))
                """).param("id", UlidCreator.getUlid().toString()).param("executionId", executionId)
                .param("streamKey", "testhub:runner:api:tasks:v1").update();
        String content;
        try {
            content = objectMapper.writeValueAsString(Map.of("contract", "testhub.api-automation.execution-manifest",
                    "manifestVersion", 2, "environmentCode", "test", "failureStrategy", "STOP", "steps", java.util.List.of(),
                    "variableBindings", java.util.List.of(Map.of("name", "lease_metadata_only", "source", "ENVIRONMENT_SECRET", "secret", true)),
                    "variableReferences", java.util.List.of(Map.of("name", "lease_metadata_only", "source", "ENVIRONMENT_SECRET",
                            "secret", true, "locations", java.util.List.of(Map.of("stepId", "request-1", "configKey", "header"))))));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        jdbc.sql("""
                INSERT INTO api_automation_execution_manifest
                    (id, execution_id, organization_id, project_id, scenario_id, scenario_version, content, checksum,
                     step_count, variable_count, secret_reference_count)
                VALUES (:id, :executionId, :organizationId, :projectId, :scenarioId, 1, CAST(:content AS JSON), :checksum, 0, 1, 1)
                """).param("id", UlidCreator.getUlid().toString()).param("executionId", executionId)
                .param("organizationId", project.organizationId()).param("projectId", project.id()).param("scenarioId", scenarioId)
                .param("content", content).param("checksum", "0".repeat(64)).update();
        String runtimeSnapshot;
        try {
            runtimeSnapshot = objectMapper.writeValueAsString(Map.of("contract", "testhub.api-automation.runtime-snapshot",
                    "snapshotVersion", 1, "environmentCode", "test", "variables", java.util.List.of(Map.of(
                            "name", "lease_runtime_nonsecret", "source", "PROJECT_PARAMETER",
                            "value", "lease-runtime-value-must-not-leak")),
                    "withheldSecretNames", java.util.List.of("lease_metadata_only")));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        jdbc.sql("""
                INSERT INTO api_automation_execution_runtime_snapshot
                    (id, execution_id, organization_id, project_id, content, checksum, variable_count, withheld_secret_count)
                VALUES (:id, :executionId, :organizationId, :projectId, CAST(:content AS JSON), :checksum, 1, 1)
                """).param("id", UlidCreator.getUlid().toString()).param("executionId", executionId)
                .param("organizationId", project.organizationId()).param("projectId", project.id())
                .param("content", runtimeSnapshot).param("checksum", "1".repeat(64)).update();
        return new Fixture(scenarioId, executionId, taskId);
    }

    private void writeVerifiedHeartbeat() throws Exception {
        Map<String, Object> heartbeat = new LinkedHashMap<>();
        heartbeat.put("runnerId", RUNNER_ID);
        heartbeat.put("runnerType", "api");
        heartbeat.put("protocolVersion", "1");
        heartbeat.put("runnerVersion", "0.1.0-test");
        heartbeat.put("executionEnabled", false);
        heartbeat.put("startedAt", Instant.now().minusSeconds(10).toString());
        heartbeat.put("heartbeatAt", Instant.now().toString());
        heartbeat.put("trustVersion", "1");
        heartbeat.put("signature", heartbeatSignature(heartbeat));
        redis.opsForSet().add(MEMBERS, RUNNER_ID);
        redis.opsForValue().set(HEARTBEAT, objectMapper.writeValueAsString(heartbeat), Duration.ofSeconds(60));
    }

    private HttpHeaders headers(String taskId, String executionId, String nonce, boolean tamper) throws Exception {
        String timestamp = Instant.now().toString();
        String payload = String.join("\n", "testhub.api-runner.internal-auth.v1", "POST", PATH, RUNNER_ID, timestamp,
                nonce, taskId, executionId);
        String signature = hmac(payload);
        if (tamper) signature = (signature.charAt(0) == '0' ? "1" : "0") + signature.substring(1);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-TestHub-Runner-Id", RUNNER_ID);
        headers.set("X-TestHub-Runner-Timestamp", timestamp);
        headers.set("X-TestHub-Runner-Nonce", nonce);
        headers.set("X-TestHub-Runner-Signature", signature);
        return headers;
    }

    private HttpHeaders inspectionHeaders(String taskId, String executionId, String lease, String nonce, boolean tamper) throws Exception {
        String timestamp = Instant.now().toString();
        String payload = String.join("\n", "testhub.api-runner.internal-auth.v1", "POST", INSPECTION_PATH, RUNNER_ID,
                timestamp, nonce, taskId, executionId, leaseFingerprint(lease));
        String signature = hmac(payload);
        if (tamper) signature = (signature.charAt(0) == '0' ? "1" : "0") + signature.substring(1);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-TestHub-Runner-Id", RUNNER_ID);
        headers.set("X-TestHub-Runner-Timestamp", timestamp);
        headers.set("X-TestHub-Runner-Nonce", nonce);
        headers.set("X-TestHub-Runner-Signature", signature);
        return headers;
    }

    private String leaseFingerprint(String lease) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(lease.getBytes(StandardCharsets.UTF_8)));
    }

    private String heartbeatSignature(Map<String, Object> heartbeat) throws Exception {
        return hmac(String.join("\n", String.valueOf(heartbeat.get("runnerId")), String.valueOf(heartbeat.get("runnerType")),
                String.valueOf(heartbeat.get("protocolVersion")), String.valueOf(heartbeat.get("runnerVersion")),
                String.valueOf(heartbeat.get("executionEnabled")), String.valueOf(heartbeat.get("startedAt")),
                String.valueOf(heartbeat.get("heartbeatAt"))));
    }

    private String hmac(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HEARTBEAT_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record ProjectScope(String id, String organizationId) {
    }

    private record Fixture(String scenarioId, String executionId, String taskId) {
    }
}
