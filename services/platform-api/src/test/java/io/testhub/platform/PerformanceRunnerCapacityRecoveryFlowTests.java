package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.performance.PerformanceRunnerProtocol;
import io.testhub.platform.performance.PerformanceTestExecutionService;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@org.springframework.boot.test.context.SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PerformanceRunnerCapacityRecoveryFlowTests {
    private static final String CAPACITY_RUNNER = "jmeter-runner-capacity-test";
    private static final String OFFLINE_RUNNER = "jmeter-runner-offline-test";
    private static final String HEARTBEAT_KEY = "testhub-performance-runner-local-development-key-2026";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcClient jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PerformanceTestExecutionService executions;

    @AfterEach
    void clean() {
        for (String runnerId : new String[] { CAPACITY_RUNNER, OFFLINE_RUNNER }) {
            redis.delete("testhub:runner:jmeter:heartbeat:v1:" + runnerId);
            redis.opsForSet().remove("testhub:runner:jmeter:members:v1", runnerId);
        }
        jdbc.sql("DELETE FROM performance_test_execution WHERE plan_name LIKE '压测调度验收 %'").update();
        jdbc.sql("DELETE FROM performance_test_thread_group WHERE name LIKE '压测调度验收 %'").update();
        jdbc.sql("DELETE FROM performance_test_plan WHERE name LIKE '压测调度验收 %'").update();
    }

    @Test
    void exposesVerifiedCapacityAndSafelyHandlesDeferralAndRunnerLoss() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        heartbeat(CAPACITY_RUNNER, 2, 1);
        mvc.perform(get("/api/v1/projects/{projectId}/performance/runner-status", projectId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].runnerId").value(CAPACITY_RUNNER))
                .andExpect(jsonPath("$.data[0].maxConcurrentTasks").value(2))
                .andExpect(jsonPath("$.data[0].activeTaskCount").value(1));

        String planId = createPlan(administrator, projectId);
        Execution deferred = createExecution(administrator, projectId, planId);
        executions.applyRunnerEvent(new PerformanceRunnerProtocol.PerformanceRunnerEvent(deferred.taskId(), deferred.executionId(),
                CAPACITY_RUNNER, "DEFERRED", "RUNNER_CAPACITY_EXHAUSTED", "No free slot.", Instant.now()));
        mvc.perform(get("/api/v1/projects/{projectId}/performance/executions/{executionId}", projectId, deferred.executionId())
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.statusCode").value("RUNNER_CAPACITY_DEFERRED"))
                .andExpect(jsonPath("$.data.runnerId").isEmpty()).andExpect(jsonPath("$.data.routingAttempts").value(1));
        Integer taskDeliveries = jdbc.sql("SELECT COUNT(*) FROM performance_runner_outbox WHERE execution_id = :executionId "
                        + "AND message_type = 'TASK'")
                .param("executionId", deferred.executionId()).query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(2, taskDeliveries);

        Execution offline = createExecution(administrator, projectId, planId);
        executions.applyRunnerEvent(new PerformanceRunnerProtocol.PerformanceRunnerEvent(offline.taskId(), offline.executionId(),
                OFFLINE_RUNNER, "ACCEPTED", "TASK_ACCEPTED", "Accepted by a runner that will disappear.", Instant.now()));
        executions.expireOfflineRunnerExecutions();
        mvc.perform(get("/api/v1/projects/{projectId}/performance/executions/{executionId}", projectId, offline.executionId())
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.statusCode").value("RUNNER_OFFLINE"));
    }

    private String createPlan(LoginSession administrator, String projectId) throws Exception {
        Csrf csrf = csrf(administrator);
        MvcResult plan = mvc.perform(post("/api/v1/projects/{projectId}/performance/plans", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"压测调度验收 Runner 容量","description":"多 Runner 调度与失联恢复验收","status":"READY","threadGroups":[
                                {"name":"压测调度验收 线程组","targetUrl":"https://perf.example.test/orders","loadModel":"STEADY","threadCount":1,"rampUpSeconds":0,"durationSeconds":60,"targetTps":null,"p95ThresholdMillis":null,"errorThresholdPercent":null}]}
                                """))
                .andExpect(status().isOk()).andReturn();
        return tree(plan).at("/data/id").asText();
    }

    private Execution createExecution(LoginSession administrator, String projectId, String planId) throws Exception {
        Csrf csrf = csrf(administrator);
        MvcResult execution = mvc.perform(post("/api/v1/projects/{projectId}/performance/plans/{planId}/executions", projectId, planId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"timeoutSeconds\":120}"))
                .andExpect(status().isOk()).andReturn();
        return new Execution(tree(execution).at("/data/id").asText(), tree(execution).at("/data/taskId").asText());
    }

    private void heartbeat(String runnerId, int maxConcurrentTasks, int activeTaskCount) throws Exception {
        Instant startedAt = Instant.now().minusSeconds(5);
        Instant heartbeatAt = Instant.now();
        Map<String, Object> heartbeat = new LinkedHashMap<>();
        heartbeat.put("runnerId", runnerId); heartbeat.put("runnerType", "jmeter"); heartbeat.put("protocolVersion", "1");
        heartbeat.put("runnerVersion", "0.1.0-test"); heartbeat.put("executionEnabled", true);
        heartbeat.put("startedAt", startedAt.toString()); heartbeat.put("heartbeatAt", heartbeatAt.toString());
        heartbeat.put("maxConcurrentTasks", maxConcurrentTasks); heartbeat.put("activeTaskCount", activeTaskCount);
        heartbeat.put("trustVersion", "1");
        heartbeat.put("signature", hmac(String.join("\n", runnerId, "jmeter", "1", "0.1.0-test", "true",
                startedAt.toString(), heartbeatAt.toString(), String.valueOf(maxConcurrentTasks), String.valueOf(activeTaskCount))));
        redis.opsForSet().add("testhub:runner:jmeter:members:v1", runnerId);
        redis.opsForValue().set("testhub:runner:jmeter:heartbeat:v1:" + runnerId, json.writeValueAsString(heartbeat), Duration.ofMinutes(1));
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

    private String hmac(String source) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HEARTBEAT_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(source.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode tree(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private record LoginSession(Cookie sessionCookie) {
    }

    private record Csrf(Cookie cookie, String headerName, String token) {
    }

    private record Execution(String executionId, String taskId) {
    }
}
