package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.performance.PerformanceRunnerProtocol;
import io.testhub.platform.performance.PerformanceRunnerRuntimeConfiguration;
import io.testhub.platform.performance.PerformanceRunnerRuntimeConfigurationService;
import io.testhub.platform.performance.PerformanceTestExecutionService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import io.testhub.platform.shared.runtime.RunnerRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class PerformanceTestExecutionFlowTests {
    private static final String RUNNER_ID = "jmeter-runner-test";
    private static final String HEARTBEAT_KEY = "testhub-performance-runner-local-development-key-2026";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;
    @Autowired private PerformanceTestExecutionService executions;
    @Autowired private PerformanceRunnerRuntimeConfigurationService runtime;
    @Autowired private RunnerRegistry registry;

    @AfterEach
    void clean() {
        registry.delete("testhub:runner:jmeter:heartbeat:v1:" + RUNNER_ID);
        registry.remove("testhub:runner:jmeter:members:v1", RUNNER_ID);
        jdbc.sql("DELETE FROM performance_test_execution WHERE plan_name LIKE '压测执行验收 %'").update();
        jdbc.sql("DELETE FROM performance_test_thread_group WHERE name LIKE '压测执行验收 %'").update();
        jdbc.sql("DELETE FROM performance_test_plan WHERE name LIKE '压测执行验收 %'").update();
    }

    @Test
    void queuesImmutablePlanSnapshotAndAcceptsCancellationEvents() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MvcResult plan = mvc.perform(post("/api/v1/projects/{projectId}/performance/plans", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"压测执行验收 订单基线","description":"执行队列验收","status":"READY","threadGroups":[
                                {"name":"压测执行验收 订单线程组","targetUrl":"https://perf.example.test/orders/${orderId}","loadModel":"RAMP_UP","threadCount":50,"rampUpSeconds":30,"durationSeconds":120,"targetTps":100,"p95ThresholdMillis":500,"errorThresholdPercent":1.0,"requestMethod":"POST","requestHeaders":{"Content-Type":"application/json","X-Request-Source":"${source}"},"queryParameters":{"source":"${source}","page":"1"},"requestBody":"{\\"sku\\":\\"${orderId}\\"}","expectedStatusCode":201,"responseContains":"orderId","dataSetCsv":"orderId,source\\nA-100,performance","rendezvousUsers":20}]}
                                """))
                .andExpect(status().isOk()).andReturn();
        String planId = json(plan).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult created = mvc.perform(post("/api/v1/projects/{projectId}/performance/plans/{planId}/executions", projectId, planId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{" + "\"timeoutSeconds\":120}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.manifestRef", org.hamcrest.Matchers.matchesPattern(
                        "performance-test/manifests/[0-9A-HJKMNP-TV-Z]{26}/v1\\.json")))
                .andReturn();
        String executionId = json(created).at("/data/id").asText();
        String taskId = json(created).at("/data/taskId").asText();

        heartbeat(RUNNER_ID);
        PerformanceRunnerRuntimeConfiguration configuration = runtime.issue(RUNNER_ID, taskId, executionId);
        org.junit.jupiter.api.Assertions.assertEquals(6, configuration.artifactVersion());
        PerformanceRunnerRuntimeConfiguration.ThreadGroup group = configuration.threadGroups().getFirst();
        org.junit.jupiter.api.Assertions.assertEquals("POST", group.requestMethod());
        org.junit.jupiter.api.Assertions.assertEquals("${source}", group.requestHeaders().get("X-Request-Source"));
        org.junit.jupiter.api.Assertions.assertEquals("${source}", group.queryParameters().get("source"));
        org.junit.jupiter.api.Assertions.assertEquals("{\"sku\":\"${orderId}\"}", group.requestBody());
        org.junit.jupiter.api.Assertions.assertEquals(201, group.expectedStatusCode());
        org.junit.jupiter.api.Assertions.assertEquals("orderId", group.responseContains());
        org.junit.jupiter.api.Assertions.assertEquals("orderId,source\nA-100,performance", group.dataSetCsv());
        org.junit.jupiter.api.Assertions.assertEquals(20, group.rendezvousUsers());
        org.junit.jupiter.api.Assertions.assertNull(group.stepCount());
        org.junit.jupiter.api.Assertions.assertNull(group.stepIntervalSeconds());

        mvc.perform(get("/api/v1/projects/{projectId}/performance/executions/{executionId}/logs", projectId, executionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].code").value("MANIFEST_FROZEN"))
                .andExpect(jsonPath("$.data[1].code").value("QUEUED"));

        executions.applyRunnerEvent(new PerformanceRunnerProtocol.PerformanceRunnerEvent(taskId, executionId,
                "jmeter-runner-test", "ACCEPTED", "TASK_ACCEPTED", "Task accepted.", Instant.now()));
        mvc.perform(get("/api/v1/projects/{projectId}/performance/executions/{executionId}", projectId, executionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACCEPTED"));

        executions.applyRunnerEvent(new PerformanceRunnerProtocol.PerformanceRunnerEvent(taskId, executionId,
                "jmeter-runner-test", "RUNNING", "JMETER_PROCESS_STARTED", "Task started.", Instant.now()));
        executions.applyRunnerMetric(new PerformanceRunnerProtocol.PerformanceRunnerMetric(taskId, executionId,
                "jmeter-runner-test", "__TOTAL__", 20, 19, 1, 3, new BigDecimal("6.2500"), 120, 180, 30, 240,
                Instant.now()));
        executions.applyRunnerResource(new PerformanceRunnerProtocol.PerformanceRunnerResource(taskId, executionId,
                "jmeter-runner-test", new BigDecimal("12.5000"), 4_194_304, Instant.now()));
        executions.applyRunnerError(new PerformanceRunnerProtocol.PerformanceRunnerError(taskId, executionId,
                "jmeter-runner-test", "压测执行验收 订单线程组", "503", 1, Instant.now()));
        executions.applyRunnerError(new PerformanceRunnerProtocol.PerformanceRunnerError(taskId, executionId,
                "jmeter-runner-test", "压测执行验收 订单线程组", "503", 1, Instant.now()));
        mvc.perform(get("/api/v1/projects/{projectId}/performance/executions/{executionId}/report", projectId, executionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.sampleCount").value(20))
                .andExpect(jsonPath("$.data.summary.errorCount").value(1))
                .andExpect(jsonPath("$.data.summary.p95LatencyMillis").value(180))
                .andExpect(jsonPath("$.data.resources.length()").value(1))
                .andExpect(jsonPath("$.data.resources[0].cpuPercent").value(12.5))
                .andExpect(jsonPath("$.data.resources[0].residentMemoryBytes").value(4194304))
                .andExpect(jsonPath("$.data.errors.length()").value(1))
                .andExpect(jsonPath("$.data.errors[0].responseCode").value("503"))
                .andExpect(jsonPath("$.data.errors[0].errorCount").value(1));
        mvc.perform(get("/api/v1/projects/{projectId}/performance/executions/{executionId}/resources", projectId, executionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].runnerId").value("jmeter-runner-test"));

        MvcResult exported = mvc.perform(get("/api/v1/projects/{projectId}/performance/executions/{executionId}/report/export", projectId, executionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        String csv = new String(exported.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(exported.getResponse().getContentType().startsWith("text/csv"));
        assertTrue(exported.getResponse().getHeader("Content-Disposition").contains("testhub-performance-report-" + executionId + ".csv"));
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("\"摘要\""));
        assertTrue(csv.contains("\"实时指标\""));
        assertTrue(csv.contains("\"错误归因\""));
        assertTrue(csv.contains("\"503\""));
        assertTrue(!csv.contains("Task accepted."));

        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/performance/executions/{executionId}", projectId, executionId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLING"));
        executions.applyRunnerEvent(new PerformanceRunnerProtocol.PerformanceRunnerEvent(taskId, executionId,
                "jmeter-runner-test", "CANCELLED", "CANCEL_COMMAND_ACCEPTED", "Task cancelled.", Instant.now()));
        mvc.perform(get("/api/v1/projects/{projectId}/performance/executions/{executionId}", projectId, executionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void evaluatesFrozenThresholdsAndComparesWithThePreviousSuccessfulExecution() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MvcResult plan = mvc.perform(post("/api/v1/projects/{projectId}/performance/plans", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"压测执行验收 阈值对比","description":"阈值和历史对比验收","status":"READY","threadGroups":[
                                {"name":"压测执行验收 阈值线程组","targetUrl":"https://perf.example.test/orders","loadModel":"STEADY","threadCount":2,"rampUpSeconds":0,"durationSeconds":120,"targetTps":null,"p95ThresholdMillis":100,"errorThresholdPercent":2.0}]}
                                """))
                .andExpect(status().isOk()).andReturn();
        String planId = json(plan).at("/data/id").asText();

        Execution baseline = createExecution(administrator, projectId, planId);
        complete(baseline, "jmeter-runner-threshold-test", 20, 20, 0, 90);

        Execution current = createExecution(administrator, projectId, planId);
        complete(current, "jmeter-runner-threshold-test", 24, 22, 2, 130);

        mvc.perform(get("/api/v1/projects/{projectId}/performance/executions/{executionId}/report", projectId, current.executionId())
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quality.status").value("FAILED"))
                .andExpect(jsonPath("$.data.quality.failedChecks").value(2))
                .andExpect(jsonPath("$.data.quality.threadGroups[0].breaches[0]").value(org.hamcrest.Matchers.containsString("P95")))
                .andExpect(jsonPath("$.data.comparison.baseline.id").value(baseline.executionId()))
                .andExpect(jsonPath("$.data.comparison.delta.sampleCount").value(4))
                .andExpect(jsonPath("$.data.comparison.delta.p95LatencyMillis").value(40));
    }

    @Test
    void acceptsOnlyBoundedRunnerErrorAttributionFields() {
        PerformanceRunnerProtocol.PerformanceRunnerError error = PerformanceRunnerProtocol.error(Map.of(
                "contract", PerformanceRunnerProtocol.ERROR_CONTRACT, "protocolVersion", "1",
                "taskId", "01J00000000000000000000001", "executionId", "01J00000000000000000000002",
                "runnerId", "jmeter-runner-test", "threadGroupName", "订单主链路", "responseCode", "502",
                "errorCount", "3", "occurredAt", "2026-07-19T08:00:00Z"));
        org.junit.jupiter.api.Assertions.assertEquals(3, error.errorCount());
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                PerformanceRunnerProtocol.error(Map.of("contract", PerformanceRunnerProtocol.ERROR_CONTRACT, "protocolVersion", "1",
                        "taskId", "01J00000000000000000000001", "executionId", "01J00000000000000000000002",
                        "runnerId", "jmeter-runner-test", "threadGroupName", "订单主链路", "responseCode", "error detail",
                        "errorCount", "3", "occurredAt", "2026-07-19T08:00:00Z")));
    }

    private Execution createExecution(LoginSession administrator, String projectId, String planId) throws Exception {
        Csrf csrf = csrf(administrator);
        MvcResult result = mvc.perform(post("/api/v1/projects/{projectId}/performance/plans/{planId}/executions", projectId, planId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"timeoutSeconds\":120}"))
                .andExpect(status().isOk()).andReturn();
        return new Execution(json(result).at("/data/id").asText(), json(result).at("/data/taskId").asText());
    }

    private void heartbeat(String runnerId) throws Exception {
        Instant startedAt = Instant.now().minusSeconds(5);
        Instant heartbeatAt = Instant.now();
        Map<String, Object> heartbeat = new LinkedHashMap<>();
        heartbeat.put("runnerId", runnerId); heartbeat.put("runnerType", "jmeter"); heartbeat.put("protocolVersion", "1");
        heartbeat.put("runnerVersion", "0.1.0-test"); heartbeat.put("executionEnabled", true);
        heartbeat.put("startedAt", startedAt.toString()); heartbeat.put("heartbeatAt", heartbeatAt.toString());
        heartbeat.put("trustVersion", "1");
        heartbeat.put("signature", hmac(String.join("\n", runnerId, "jmeter", "1", "0.1.0-test", "true",
                startedAt.toString(), heartbeatAt.toString())));
        registry.publish("testhub:runner:jmeter:members:v1", "testhub:runner:jmeter:heartbeat:v1:" + runnerId, runnerId, objectMapper.writeValueAsString(heartbeat), Duration.ofMinutes(1));
    }

    private String hmac(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HEARTBEAT_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private void complete(Execution execution, String runnerId, long samples, long successes, long errors, int p95) {
        Instant now = Instant.now();
        executions.applyRunnerEvent(new PerformanceRunnerProtocol.PerformanceRunnerEvent(execution.taskId(), execution.executionId(),
                runnerId, "ACCEPTED", "TASK_ACCEPTED", "Task accepted.", now));
        executions.applyRunnerEvent(new PerformanceRunnerProtocol.PerformanceRunnerEvent(execution.taskId(), execution.executionId(),
                runnerId, "RUNNING", "JMETER_PROCESS_STARTED", "Task started.", now.plusMillis(1)));
        executions.applyRunnerMetric(new PerformanceRunnerProtocol.PerformanceRunnerMetric(execution.taskId(), execution.executionId(),
                runnerId, "压测执行验收 阈值线程组", samples, successes, errors, 2, new BigDecimal("8.0000"), 80, p95, 30, p95,
                now.plusMillis(2)));
        executions.applyRunnerMetric(new PerformanceRunnerProtocol.PerformanceRunnerMetric(execution.taskId(), execution.executionId(),
                runnerId, "__TOTAL__", samples, successes, errors, 2, new BigDecimal("8.0000"), 80, p95, 30, p95,
                now.plusMillis(2)));
        executions.applyRunnerEvent(new PerformanceRunnerProtocol.PerformanceRunnerEvent(execution.taskId(), execution.executionId(),
                runnerId, "SUCCEEDED", "JMETER_PROCESS_SUCCEEDED", "Task completed.", now.plusMillis(3)));
    }

    private LoginSession login() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode data = json(csrf).path("data");
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(data.path("headerName").asText(), data.path("token").asText())
                        .content("{\"email\":\"admin@testhub.local\",\"password\":\"TestHub2026!\"}"))
                .andExpect(status().isOk()).andReturn();
        return new LoginSession(login.getResponse().getCookie("TESTHUB_SESSION"));
    }

    private Csrf csrf(LoginSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").cookie(session.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = json(result).path("data");
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"), data.path("headerName").asText(), data.path("token").asText());
    }

    private String projectId(LoginSession session) throws Exception {
        MvcResult context = mvc.perform(get("/api/v1/access/context").cookie(session.sessionCookie())).andExpect(status().isOk()).andReturn();
        String organizationId = json(context).at("/data/organizations/0/id").asText();
        return json(context).at("/data/projects/" + organizationId + "/0/id").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record LoginSession(Cookie sessionCookie) {
    }

    private record Csrf(Cookie cookie, String headerName, String token) {
    }

    private record Execution(String executionId, String taskId) {
    }
}
