package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.performance.PerformanceRunnerProtocol;
import io.testhub.platform.performance.PerformanceTestExecutionService;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@org.springframework.boot.test.context.SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PerformanceTestExecutionArtifactFlowTests {
    private static final String RUNNER_ID = "jmeter-runner-artifact-test";
    private static final String KEY = "testhub-performance-runner-local-development-key-2026";
    private static final String PATH = "/internal/performance-runner/v1/artifacts";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcClient jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PerformanceTestExecutionService executions;

    @AfterEach
    void clean() {
        redis.delete("testhub:runner:jmeter:heartbeat:v1:" + RUNNER_ID);
        redis.opsForSet().remove("testhub:runner:jmeter:members:v1", RUNNER_ID);
        redis.delete("testhub:runner:jmeter:internal-nonce:v1:" + RUNNER_ID + ":nonce_archive_jtl_987654");
        redis.delete("testhub:runner:jmeter:internal-nonce:v1:" + RUNNER_ID + ":nonce_timeout_log_987654");
        redis.delete("testhub:runner:jmeter:internal-nonce:v1:" + RUNNER_ID + ":nonce_timeout_late_987654");
        jdbc.sql("DELETE FROM performance_test_execution WHERE plan_name LIKE '压测工件验收 %'").update();
        jdbc.sql("DELETE FROM performance_test_thread_group WHERE name LIKE '压测工件验收 %'").update();
        jdbc.sql("DELETE FROM performance_test_plan WHERE name LIKE '压测工件验收 %'").update();
    }

    @Test
    void archivesSignedRunnerOutputInMinioAndAllowsOnlyProjectScopedDownloads() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MvcResult plan = mvc.perform(post("/api/v1/projects/{projectId}/performance/plans", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"压测工件验收 归档","description":"MinIO 工件归档验收","status":"READY","threadGroups":[
                                {"name":"压测工件验收 线程组","targetUrl":"https://perf.example.test/orders","loadModel":"STEADY","threadCount":1,"rampUpSeconds":0,"durationSeconds":60,"targetTps":null,"p95ThresholdMillis":null,"errorThresholdPercent":null}]}
                                """))
                .andExpect(status().isOk()).andReturn();
        String planId = tree(plan).at("/data/id").asText();
        csrf = csrf(administrator);
        MvcResult created = mvc.perform(post("/api/v1/projects/{projectId}/performance/plans/{planId}/executions", projectId, planId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"timeoutSeconds\":120}"))
                .andExpect(status().isOk()).andReturn();
        String executionId = tree(created).at("/data/id").asText();
        String taskId = tree(created).at("/data/taskId").asText();
        heartbeat();
        Instant now = Instant.now();
        executions.applyRunnerEvent(new PerformanceRunnerProtocol.PerformanceRunnerEvent(taskId, executionId, RUNNER_ID,
                "ACCEPTED", "TASK_ACCEPTED", "Task accepted.", now));
        executions.applyRunnerEvent(new PerformanceRunnerProtocol.PerformanceRunnerEvent(taskId, executionId, RUNNER_ID,
                "RUNNING", "JMETER_PROCESS_STARTED", "Task started.", now.plusMillis(1)));

        byte[] jtl = "timeStamp,elapsed,label,responseCode,success,allThreads\n1710000000000,10,health,200,true,1\n"
                .getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(jtl);
        MvcResult archived = mvc.perform(post(PATH).contentType("text/csv").content(jtl)
                        .headers(headers(taskId, executionId, "JMETER_JTL", jtl.length, checksum, "nonce_archive_jtl_987654")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.artifactType").value("JMETER_JTL"))
                .andExpect(jsonPath("$.data.sizeBytes").value(jtl.length)).andReturn();
        String artifactId = tree(archived).at("/data/id").asText();

        mvc.perform(get("/api/v1/projects/{projectId}/performance/executions/{executionId}/artifacts", projectId, executionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(artifactId));
        mvc.perform(get("/api/v1/projects/{projectId}/performance/executions/{executionId}/artifacts/{artifactId}/download",
                        projectId, executionId, artifactId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(content().bytes(jtl));

        // A timeout is finalized by the control plane before the Runner can upload its last output.
        jdbc.sql("UPDATE performance_test_execution SET status = 'TIMED_OUT', finished_at = CURRENT_TIMESTAMP(6) WHERE id = :id")
                .param("id", executionId).update();
        byte[] log = "JMeter stopped at its deadline".getBytes(StandardCharsets.UTF_8);
        mvc.perform(post(PATH).contentType("text/plain").content(log)
                        .headers(headers(taskId, executionId, "JMETER_LOG", log.length, sha256(log), "nonce_timeout_log_987654")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.artifactType").value("JMETER_LOG"));
        jdbc.sql("UPDATE performance_test_execution SET finished_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 3 MINUTE) WHERE id = :id")
                .param("id", executionId).update();
        mvc.perform(post(PATH).contentType("text/plain").content(log)
                        .headers(headers(taskId, executionId, "JMETER_LOG", log.length, sha256(log), "nonce_timeout_late_987654")))
                .andExpect(status().isForbidden());
    }

    private void heartbeat() throws Exception {
        Map<String, Object> heartbeat = new LinkedHashMap<>();
        heartbeat.put("runnerId", RUNNER_ID); heartbeat.put("runnerType", "jmeter"); heartbeat.put("protocolVersion", "1");
        heartbeat.put("runnerVersion", "0.1.0-test"); heartbeat.put("executionEnabled", true);
        heartbeat.put("startedAt", Instant.now().minusSeconds(5).toString()); heartbeat.put("heartbeatAt", Instant.now().toString());
        heartbeat.put("trustVersion", "1"); heartbeat.put("signature", hmac(String.join("\n", RUNNER_ID, "jmeter", "1", "0.1.0-test",
                "true", String.valueOf(heartbeat.get("startedAt")), String.valueOf(heartbeat.get("heartbeatAt")))));
        redis.opsForSet().add("testhub:runner:jmeter:members:v1", RUNNER_ID);
        redis.opsForValue().set("testhub:runner:jmeter:heartbeat:v1:" + RUNNER_ID, json.writeValueAsString(heartbeat), Duration.ofMinutes(1));
    }

    private HttpHeaders headers(String taskId, String executionId, String type, long size, String checksum, String nonce) throws Exception {
        String timestamp = Instant.now().toString();
        String signature = hmac(String.join("\n", "testhub.performance-runner.internal-auth.v1", "POST", PATH, RUNNER_ID,
                timestamp, nonce, taskId, executionId, type, String.valueOf(size), checksum));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-TestHub-Runner-Id", RUNNER_ID); headers.set("X-TestHub-Runner-Timestamp", timestamp);
        headers.set("X-TestHub-Runner-Nonce", nonce); headers.set("X-TestHub-Runner-Signature", signature);
        headers.set("X-TestHub-Task-Id", taskId); headers.set("X-TestHub-Execution-Id", executionId);
        headers.set("X-TestHub-Artifact-Type", type); headers.set("X-TestHub-Artifact-Size", String.valueOf(size));
        headers.set("X-TestHub-Artifact-SHA256", checksum);
        return headers;
    }

    private LoginSession login() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode data = tree(csrf).path("data");
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(data.path("headerName").asText(), data.path("token").asText())
                        .content("{\"email\":\"admin@testhub.local\",\"password\":\"TestHub2026!\"}"))
                .andExpect(status().isOk()).andReturn();
        return new LoginSession(login.getResponse().getCookie("TESTHUB_SESSION"));
    }

    private Csrf csrf(LoginSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").cookie(session.sessionCookie())).andExpect(status().isOk()).andReturn();
        JsonNode data = tree(result).path("data");
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"), data.path("headerName").asText(), data.path("token").asText());
    }

    private String projectId(LoginSession session) throws Exception {
        MvcResult context = mvc.perform(get("/api/v1/access/context").cookie(session.sessionCookie())).andExpect(status().isOk()).andReturn();
        String organizationId = tree(context).at("/data/organizations/0/id").asText();
        return tree(context).at("/data/projects/" + organizationId + "/0/id").asText();
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private String hmac(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode tree(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private record LoginSession(Cookie sessionCookie) {
    }

    private record Csrf(Cookie cookie, String headerName, String token) {
    }
}
