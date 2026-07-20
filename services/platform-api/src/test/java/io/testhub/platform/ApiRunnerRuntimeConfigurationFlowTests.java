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
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ApiRunnerRuntimeConfigurationFlowTests {
    private static final String RUNNER_ID = "api-runner-runtime-configuration-test";
    private static final String HEARTBEAT_KEY = "testhub-runner-contract-test-signing-key-2026-000000000000000000000000";
    private static final String LEASE_PATH = "/internal/api-runner/v1/runtime-leases";
    private static final String CONFIGURATION_PATH = "/internal/api-runner/v1/runtime-configuration";
    private static final String NONCE_PREFIX = "testhub:runner:api:internal-nonce:v1:" + RUNNER_ID + ":";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcClient jdbc;
    @Autowired private StringRedisTemplate redis;
    private Fixture fixture;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("testhub.runner.heartbeat-key", () -> HEARTBEAT_KEY);
        registry.add("testhub.runner.http-execution-enabled", () -> true);
    }

    @BeforeEach
    void clearNoncesBeforeEachExecution() {
        redis.delete(NONCE_PREFIX + "nonce_config_lease_012345");
        redis.delete(NONCE_PREFIX + "nonce_config_artifact_01234");
    }

    @AfterEach
    void clean() {
        redis.delete("testhub:runner:api:heartbeat:v1:" + RUNNER_ID);
        redis.opsForSet().remove("testhub:runner:api:members:v1", RUNNER_ID);
        redis.delete(NONCE_PREFIX + "nonce_config_lease_012345");
        redis.delete(NONCE_PREFIX + "nonce_config_artifact_01234");
        if (fixture != null) {
            redis.delete("testhub:runner:api:runtime-lease:v1:" + fixture.executionId());
            jdbc.sql("DELETE FROM api_automation_execution WHERE id = :id").param("id", fixture.executionId()).update();
            jdbc.sql("DELETE FROM api_automation_scenario WHERE id = :id").param("id", fixture.scenarioId()).update();
        }
    }

    @Test
    void issuesAnEphemeralScriptReadyArtifactOnlyToAnEnabledVerifiedRunner() throws Exception {
        fixture = fixture();
        heartbeat();
        String body = json.writeValueAsString(Map.of("taskId", fixture.taskId(), "executionId", fixture.executionId()));
        MvcResult leaseResponse = mvc.perform(post(LEASE_PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                        .headers(headers(LEASE_PATH, fixture.taskId(), fixture.executionId(), null, "nonce_config_lease_012345")))
                .andExpect(status().isOk()).andReturn();
        String lease = tree(leaseResponse).at("/data/lease").asText();
        org.junit.jupiter.api.Assertions.assertEquals("HTTP_REQUEST_EXECUTION", tree(leaseResponse).at("/data/purpose").asText());

        String configurationBody = json.writeValueAsString(Map.of("taskId", fixture.taskId(),
                "executionId", fixture.executionId(), "lease", lease));
        MvcResult configuration = mvc.perform(post(CONFIGURATION_PATH).contentType(MediaType.APPLICATION_JSON).content(configurationBody)
                        .headers(headers(CONFIGURATION_PATH, fixture.taskId(), fixture.executionId(), lease,
                                "nonce_config_artifact_01234")))
                .andExpect(status().isOk()).andReturn();
        JsonNode artifact = tree(configuration).path("data");
        org.junit.jupiter.api.Assertions.assertEquals("testhub.api-runner.runtime-configuration", artifact.path("contract").asText());
        org.junit.jupiter.api.Assertions.assertEquals(5, artifact.path("artifactVersion").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(fixture.taskId(), artifact.path("taskId").asText());
        org.junit.jupiter.api.Assertions.assertEquals("STOP", artifact.path("failureStrategy").asText());
        org.junit.jupiter.api.Assertions.assertEquals("frozen-order", artifact.at("/variables/orderId").asText());
        org.junit.jupiter.api.Assertions.assertEquals("variables.orderId = variables.orderId + '-pre';", artifact.at("/scripts/preRequestScript").asText());
        org.junit.jupiter.api.Assertions.assertEquals("if (response.status !== 200) { throw new Error('status'); }", artifact.at("/scripts/postResponseScript").asText());
        org.junit.jupiter.api.Assertions.assertEquals("https://api.example.test/orders/{{orderId}}", artifact.at("/steps/0/url").asText());
        org.junit.jupiter.api.Assertions.assertEquals("EXTRACT", artifact.at("/steps/1/type").asText());
        org.junit.jupiter.api.Assertions.assertEquals("ASSERT", artifact.at("/steps/2/type").asText());
        org.junit.jupiter.api.Assertions.assertEquals("https://api.example.test/status/{{lastStatus}}", artifact.at("/steps/3/url").asText());
        org.junit.jupiter.api.Assertions.assertEquals("WAIT", artifact.at("/steps/4/type").asText());
        org.junit.jupiter.api.Assertions.assertEquals(50, artifact.at("/steps/4/delayMillis").asInt());
        org.junit.jupiter.api.Assertions.assertEquals("CONDITION", artifact.at("/steps/5/type").asText());
        org.junit.jupiter.api.Assertions.assertEquals("EQUALS", artifact.at("/steps/5/conditionOperator").asText());
        org.junit.jupiter.api.Assertions.assertEquals("LOOP", artifact.at("/steps/8/type").asText());
        org.junit.jupiter.api.Assertions.assertEquals(3, artifact.at("/steps/8/loopTotalRuns").asInt());
        org.junit.jupiter.api.Assertions.assertFalse(configuration.getResponse().getContentAsString().contains(lease));
        org.junit.jupiter.api.Assertions.assertFalse(configuration.getResponse().getContentAsString().contains("environment-secret"));
    }

    private Fixture fixture() throws Exception {
        Project project = jdbc.sql("SELECT id, organization_id FROM project ORDER BY id LIMIT 1")
                .query((rs, row) -> new Project(rs.getString("id"), rs.getString("organization_id"))).single();
        String scenarioId = UlidCreator.getUlid().toString();
        String executionId = UlidCreator.getUlid().toString();
        String taskId = UlidCreator.getUlid().toString();
        jdbc.sql("""
                INSERT INTO api_automation_scenario
                    (id, organization_id, project_id, name, environment_code, failure_strategy, steps, variables, created_by)
                VALUES (:id, :organizationId, :projectId, :name, 'test', 'STOP', CAST('[]' AS JSON), CAST('{}' AS JSON), :createdBy)
                """).param("id", scenarioId).param("organizationId", project.organizationId()).param("projectId", project.id())
                .param("name", "Runner runtime configuration " + scenarioId).param("createdBy", project.id()).update();
        String revision = json.writeValueAsString(Map.of("name", "Runner runtime configuration", "description", "",
                "environmentCode", "test", "failureStrategy", "STOP", "variables", Map.of("orderId", "ignored-current-value"),
                "preRequestScript", "variables.orderId = variables.orderId + '-pre';",
                "postResponseScript", "if (response.status !== 200) { throw new Error('status'); }",
                "steps", List.of(
                        Map.of("id", "request-1", "type", "REQUEST", "name", "读取订单", "config", Map.of(
                                "method", "GET", "url", "https://api.example.test/orders/{{orderId}}")),
                        Map.of("id", "extract-status", "type", "EXTRACT", "name", "提取状态", "config", Map.of(
                                "source", "LAST_STATUS_CODE", "variable", "lastStatus")),
                        Map.of("id", "assert-status", "type", "ASSERT", "name", "断言状态", "config", Map.of(
                                "source", "LAST_STATUS_CODE", "expectedStatus", 200)),
                        Map.of("id", "request-status", "type", "REQUEST", "name", "读取状态", "config", Map.of(
                                "method", "GET", "url", "https://api.example.test/status/{{lastStatus}}")),
                        Map.of("id", "wait-stable", "type", "WAIT", "name", "等待稳定", "config", Map.of(
                                "delayMillis", 50)),
                        Map.of("id", "condition-followup", "type", "CONDITION", "name", "状态条件", "config", Map.of(
                                "source", "LAST_STATUS_CODE", "operator", "EQUALS", "expectedStatus", 200)),
                        Map.of("id", "request-conditional", "type", "REQUEST", "name", "条件读取", "config", Map.of(
                                "method", "GET", "url", "https://api.example.test/conditional")),
                        Map.of("id", "request-repeat", "type", "REQUEST", "name", "重复读取", "config", Map.of(
                                "method", "GET", "url", "https://api.example.test/retry")),
                        Map.of("id", "loop-repeat", "type", "LOOP", "name", "重复请求", "config", Map.of(
                                "totalRuns", 3)))));
        jdbc.sql("""
                INSERT INTO api_automation_scenario_revision
                    (id, scenario_id, organization_id, project_id, version, snapshot, created_by)
                VALUES (:id, :scenarioId, :organizationId, :projectId, 1, CAST(:snapshot AS JSON), :createdBy)
                """).param("id", UlidCreator.getUlid().toString()).param("scenarioId", scenarioId)
                .param("organizationId", project.organizationId()).param("projectId", project.id()).param("snapshot", revision)
                .param("createdBy", project.id()).update();
        jdbc.sql("""
                INSERT INTO api_automation_execution
                    (id, task_id, organization_id, project_id, scenario_id, scenario_name, scenario_version, status,
                     manifest_ref, requested_by, requested_at, deadline_at)
                VALUES (:id, :taskId, :organizationId, :projectId, :scenarioId, 'Runner runtime configuration', 1, 'QUEUED',
                        :manifestRef, :requestedBy, :requestedAt, :deadlineAt)
                """).param("id", executionId).param("taskId", taskId).param("organizationId", project.organizationId())
                .param("projectId", project.id()).param("scenarioId", scenarioId)
                .param("manifestRef", "api-automation/manifests/" + scenarioId + "/v1.json").param("requestedBy", project.id())
                .param("requestedAt", java.sql.Timestamp.from(Instant.now()))
                .param("deadlineAt", java.sql.Timestamp.from(Instant.now().plus(Duration.ofMinutes(5)))).update();
        jdbc.sql("""
                INSERT INTO api_runner_outbox (id, execution_id, message_type, stream_key, payload, status, sent_at)
                VALUES (:id, :executionId, 'TASK', 'testhub:runner:api:tasks:v1', CAST('{}' AS JSON), 'SENT', CURRENT_TIMESTAMP(3))
                """).param("id", UlidCreator.getUlid().toString()).param("executionId", executionId).update();
        String runtimeSnapshot = json.writeValueAsString(Map.of("contract", "testhub.api-automation.runtime-snapshot",
                "snapshotVersion", 1, "environmentCode", "test", "variables", List.of(Map.of("name", "orderId",
                        "source", "SCENARIO", "value", "frozen-order")), "withheldSecretNames", List.of()));
        jdbc.sql("""
                INSERT INTO api_automation_execution_runtime_snapshot
                    (id, execution_id, organization_id, project_id, content, checksum, variable_count, withheld_secret_count)
                VALUES (:id, :executionId, :organizationId, :projectId, CAST(:content AS JSON), :checksum, 1, 0)
                """).param("id", UlidCreator.getUlid().toString()).param("executionId", executionId)
                .param("organizationId", project.organizationId()).param("projectId", project.id()).param("content", runtimeSnapshot)
                .param("checksum", "2".repeat(64)).update();
        return new Fixture(scenarioId, executionId, taskId);
    }

    private void heartbeat() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("runnerId", RUNNER_ID); value.put("runnerType", "api"); value.put("protocolVersion", "1");
        value.put("runnerVersion", "0.1.0-test"); value.put("executionEnabled", true);
        value.put("startedAt", Instant.now().minusSeconds(5).toString()); value.put("heartbeatAt", Instant.now().toString());
        value.put("trustVersion", "1"); value.put("signature", hmac(String.join("\n", RUNNER_ID, "api", "1", "0.1.0-test",
                "true", String.valueOf(value.get("startedAt")), String.valueOf(value.get("heartbeatAt")))));
        redis.opsForSet().add("testhub:runner:api:members:v1", RUNNER_ID);
        redis.opsForValue().set("testhub:runner:api:heartbeat:v1:" + RUNNER_ID, json.writeValueAsString(value), Duration.ofMinutes(1));
    }

    private HttpHeaders headers(String path, String taskId, String executionId, String lease, String nonce) throws Exception {
        String timestamp = Instant.now().toString();
        List<String> parts = new java.util.ArrayList<>(List.of("testhub.api-runner.internal-auth.v1", "POST", path, RUNNER_ID,
                timestamp, nonce, taskId, executionId));
        if (lease != null) parts.add(leaseFingerprint(lease));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-TestHub-Runner-Id", RUNNER_ID); headers.set("X-TestHub-Runner-Timestamp", timestamp);
        headers.set("X-TestHub-Runner-Nonce", nonce); headers.set("X-TestHub-Runner-Signature", hmac(String.join("\n", parts)));
        return headers;
    }

    private String leaseFingerprint(String lease) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(lease.getBytes(StandardCharsets.UTF_8)));
    }
    private String hmac(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HEARTBEAT_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
    private JsonNode tree(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private static String randomKey() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
    private record Project(String id, String organizationId) { }
    private record Fixture(String scenarioId, String executionId, String taskId) { }
}
