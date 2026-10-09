package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.config.SystemConfigRepository;
import io.testhub.platform.uiautomation.UiAutomationRunnerProtocol;
import io.testhub.platform.uiautomation.UiAutomationService;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import io.testhub.platform.shared.runtime.RunnerRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class UiAutomationFlowTests {
    private static final String ALLOWED_ORIGINS = "ui-automation.allowed-origins";
    private static final String RUNNER_ID = "playwright-runner-artifact-test";
    private static final String RUNNER_KEY = "testhub-ui-automation-runner-local-development-key-2026";
    private static final String ARTIFACT_PATH = "/internal/ui-automation-runner/v1/artifacts";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcClient jdbc;
    @Autowired private RunnerRegistry registry;
    @Autowired private io.testhub.platform.shared.runtime.ExpiringStateStore state;
    @Autowired private SystemConfigRepository configs;
    @Autowired private UiAutomationService automation;
    private Optional<String> previous = Optional.empty();
    private String administratorId;
    private String profileId;
    private String objectId;
    private String scenarioId;
    private String executionId;
    private String scheduleId;
    private String functionalPlanId;
    private final List<String> extraExecutionIds = new ArrayList<>();

    @AfterEach
    void clean() {
        registry.delete("testhub:runner:playwright:heartbeat:v1:" + RUNNER_ID);
        registry.remove("testhub:runner:playwright:members:v1", RUNNER_ID);
        state.delete("testhub:runner:playwright:internal-nonce:v1:" + RUNNER_ID + ":nonce_ui_screenshot_987654");
        state.delete("testhub:runner:playwright:internal-nonce:v1:" + RUNNER_ID + ":nonce_ui_trace_987654");
        if (scheduleId != null) jdbc.sql("DELETE FROM ui_automation_schedule WHERE id = :id").param("id", scheduleId).update();
        for (String id : extraExecutionIds) jdbc.sql("DELETE FROM ui_automation_execution WHERE id = :id").param("id", id).update();
        if (executionId != null) jdbc.sql("DELETE FROM ui_automation_execution WHERE id = :id").param("id", executionId).update();
        if (scenarioId != null) jdbc.sql("DELETE FROM ui_automation_scenario WHERE id = :id").param("id", scenarioId).update();
        if (objectId != null) jdbc.sql("DELETE FROM ui_automation_page_object WHERE id = :id").param("id", objectId).update();
        if (profileId != null) jdbc.sql("DELETE FROM ui_automation_browser_profile WHERE id = :id").param("id", profileId).update();
        if (functionalPlanId != null) jdbc.sql("DELETE FROM functional_test_plan WHERE id = :id").param("id", functionalPlanId).update();
        List<String> ownedIds = new ArrayList<>(extraExecutionIds);
        for (String id : new String[] {profileId, objectId, scenarioId, executionId, scheduleId, functionalPlanId}) {
            if (id != null) ownedIds.add(id);
        }
        for (String id : ownedIds) {
            jdbc.sql("DELETE FROM audit_log WHERE action LIKE 'UI_AUTOMATION_%' AND resource_id = :id")
                    .param("id", id).update();
        }
        if (administratorId != null) {
            if (previous.isPresent()) configs.upsert(ALLOWED_ORIGINS, previous.get(), administratorId);
            else configs.deleteNonSecret(ALLOWED_ORIGINS);
        }
    }

    @Test
    void createsProjectScopedBrowserAssetsAndProducesStepReportFromRunnerEvents() throws Exception {
        Login session = login();
        administratorId = jdbc.sql("SELECT id FROM user_account WHERE email = 'admin@testhub.local'").query(String.class).single();
        previous = configs.findNonSecretValue(ALLOWED_ORIGINS);
        configs.upsert(ALLOWED_ORIGINS, "https://ui.example.test", administratorId);
        String projectId = project(session);
        Csrf csrf = csrf(session);
        MvcResult profile = mvc.perform(post("/api/v1/projects/{projectId}/ui-automation/browser-profiles", projectId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Web UI 验收 Chromium","browser":"chromium","baseUrl":"https://ui.example.test","viewportWidth":1280,"viewportHeight":720,"enabled":true}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.baseUrl").value("https://ui.example.test")).andReturn();
        profileId = body(profile).at("/data/id").asText();
        csrf = csrf(session);
        MvcResult object = mvc.perform(post("/api/v1/projects/{projectId}/ui-automation/page-objects", projectId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Web UI 验收登录按钮","selectorValue":"[data-testid='login']","description":"验收对象"}
                                """))
                .andExpect(status().isOk()).andReturn();
        objectId = body(object).at("/data/id").asText();
        csrf = csrf(session);
        MvcResult scenario = mvc.perform(post("/api/v1/projects/{projectId}/ui-automation/scenarios", projectId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"browserProfileId":"%s","name":"Web UI 验收登录","description":"完整闭环","failureStrategy":"STOP","variables":{"account":"demo"},"steps":[{"id":"open","type":"NAVIGATE","name":"打开登录页","url":"/login","timeoutMillis":10000},{"id":"check","type":"ASSERT_VISIBLE","name":"检查登录按钮","pageObjectId":"%s","timeoutMillis":10000}]}
                                """.formatted(profileId, objectId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.steps.length()").value(2)).andReturn();
        scenarioId = body(scenario).at("/data/id").asText();
        csrf = csrf(session);
        mvc.perform(post("/api/v1/projects/{projectId}/ui-automation/scenarios", projectId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"browserProfileId":"%s","name":"Null step","failureStrategy":"STOP","variables":{},"steps":[null]}
                                """.formatted(profileId)))
                .andExpect(status().isBadRequest());

        csrf = csrf(session);
        mvc.perform(delete("/api/v1/projects/{projectId}/ui-automation/page-objects/{pageObjectId}", projectId, objectId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()))
                .andExpect(status().isConflict());
        csrf = csrf(session);
        MvcResult execution = mvc.perform(post("/api/v1/projects/{projectId}/ui-automation/scenarios/{scenarioId}/executions", projectId, scenarioId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON).content("{\"timeoutSeconds\":300}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED")).andReturn();
        executionId = body(execution).at("/data/id").asText();
        String taskId = body(execution).at("/data/taskId").asText();
        Instant now = Instant.now();
        heartbeat();
        automation.applyRunnerEvent(new UiAutomationRunnerProtocol.Event(taskId, executionId, RUNNER_ID, "ACCEPTED", "TASK_ACCEPTED", "accepted", now));
        automation.applyRunnerEvent(new UiAutomationRunnerProtocol.Event(taskId, executionId, RUNNER_ID, "RUNNING", "PLAYWRIGHT_BROWSER_STARTED", "running", now));
        byte[] screenshot = "png-artifact-bytes".getBytes(StandardCharsets.UTF_8);
        MvcResult archivedScreenshot = mvc.perform(post(ARTIFACT_PATH).contentType("image/png").content(screenshot)
                        .headers(artifactHeaders(taskId, executionId, "SCREENSHOT", screenshot, "nonce_ui_screenshot_987654")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.artifactType").value("SCREENSHOT")).andReturn();
        String screenshotId = body(archivedScreenshot).at("/data/id").asText();
        byte[] trace = "trace-artifact-bytes".getBytes(StandardCharsets.UTF_8);
        MvcResult archivedTrace = mvc.perform(post(ARTIFACT_PATH).contentType("application/zip").content(trace)
                        .headers(artifactHeaders(taskId, executionId, "TRACE", trace, "nonce_ui_trace_987654")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.artifactType").value("TRACE")).andReturn();
        String traceId = body(archivedTrace).at("/data/id").asText();
        mvc.perform(get("/api/v1/projects/{projectId}/ui-automation/executions/{executionId}/artifacts/{artifactId}/view",
                        projectId, executionId, screenshotId).cookie(session.cookie()))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("inline")))
                .andExpect(content().contentType("image/png")).andExpect(content().bytes(screenshot));
        mvc.perform(get("/api/v1/projects/{projectId}/ui-automation/executions/{executionId}/artifacts/{artifactId}/view",
                        projectId, executionId, traceId).cookie(session.cookie())).andExpect(status().isNotAcceptable());
        automation.applyStepResult(new UiAutomationRunnerProtocol.StepResult(taskId, executionId, RUNNER_ID, "open", 1,
                "NAVIGATE", "打开登录页", "PASSED", 120, null, now));
        automation.applyStepResult(new UiAutomationRunnerProtocol.StepResult(taskId, executionId, RUNNER_ID, "check", 2,
                "ASSERT_VISIBLE", "检查登录按钮", "PASSED", 80, null, now));
        automation.applyRunnerEvent(new UiAutomationRunnerProtocol.Event(taskId, executionId, RUNNER_ID, "SUCCEEDED", "PLAYWRIGHT_EXECUTION_SUCCEEDED", "done", now));
        mvc.perform(get("/api/v1/projects/{projectId}/ui-automation/executions/{executionId}/report", projectId, executionId).cookie(session.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.execution.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.summary.totalSteps").value(2)).andExpect(jsonPath("$.data.summary.passedSteps").value(2))
                .andExpect(jsonPath("$.data.artifacts.length()").value(2));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM ui_automation_execution_manifest WHERE execution_id = :id").param("id", executionId).query(Integer.class).single());
        jdbc.sql("UPDATE ui_automation_execution SET status = 'TIMED_OUT', finished_at = CURRENT_TIMESTAMP(6) WHERE id = :id")
                .param("id", executionId).update();
        mvc.perform(post(ARTIFACT_PATH).contentType("application/zip").content(trace)
                        .headers(artifactHeaders(taskId, executionId, "TRACE", trace, "nonce_ui_timeout_recent_987654")))
                .andExpect(status().isOk());
        jdbc.sql("UPDATE ui_automation_execution SET finished_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 121 SECOND) WHERE id = :id")
                .param("id", executionId).update();
        mvc.perform(post(ARTIFACT_PATH).contentType("application/zip").content(trace)
                        .headers(artifactHeaders(taskId, executionId, "TRACE", trace, "nonce_ui_timeout_expired_987654")))
                .andExpect(status().isForbidden());

    }

    @Test
    void schedulesRetriesAndCancelsUiAutomationWithFunctionalPlanTraceability() throws Exception {
        Login session = login();
        administratorId = jdbc.sql("SELECT id FROM user_account WHERE email = 'admin@testhub.local'").query(String.class).single();
        previous = configs.findNonSecretValue(ALLOWED_ORIGINS);
        configs.upsert(ALLOWED_ORIGINS, "https://ui.example.test", administratorId);
        String projectId = project(session);
        String organizationId = jdbc.sql("SELECT organization_id FROM project WHERE id = :id").param("id", projectId).query(String.class).single();
        functionalPlanId = UlidCreator.getUlid().toString();
        jdbc.sql("""
                INSERT INTO functional_test_plan (id, organization_id, project_id, plan_code, name, status, description, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, 'WEB-TRACE', 'Web UI 回归计划', 'ACTIVE', '', :userId, :userId)
                """).param("id", functionalPlanId).param("organizationId", organizationId).param("projectId", projectId)
                .param("userId", administratorId).update();
        Csrf csrf = csrf(session);
        MvcResult profile = mvc.perform(post("/api/v1/projects/{projectId}/ui-automation/browser-profiles", projectId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"计划 Chromium\",\"browser\":\"chromium\",\"baseUrl\":\"https://ui.example.test\",\"viewportWidth\":1280,\"viewportHeight\":720,\"enabled\":true}"))
                .andExpect(status().isOk()).andReturn();
        profileId = body(profile).at("/data/id").asText();
        csrf = csrf(session);
        MvcResult scenario = mvc.perform(post("/api/v1/projects/{projectId}/ui-automation/scenarios", projectId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"browserProfileId":"%s","name":"计划登录巡检","description":"","failureStrategy":"STOP","variables":{},"steps":[{"id":"open","type":"NAVIGATE","name":"打开登录页","url":"/login","timeoutMillis":10000}]}
                                """.formatted(profileId)))
                .andExpect(status().isOk()).andReturn();
        scenarioId = body(scenario).at("/data/id").asText();
        csrf = csrf(session);
        MvcResult schedule = mvc.perform(post("/api/v1/projects/{projectId}/ui-automation/schedules", projectId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenarioId":"%s","functionalPlanId":"%s","name":"工作日登录巡检","cronExpression":"0 0 9 ? * MON-FRI","timezone":"Asia/Shanghai","timeoutSeconds":300,"maxRetryAttempts":1,"retryBackoffSeconds":30,"enabled":true}
                                """.formatted(scenarioId, functionalPlanId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.functionalPlanName").value("Web UI 回归计划"))
                .andExpect(jsonPath("$.data.maxRetryAttempts").value(1)).andReturn();
        scheduleId = body(schedule).at("/data/id").asText();
        csrf = csrf(session);
        MvcResult queued = mvc.perform(post("/api/v1/projects/{projectId}/ui-automation/schedules/{scheduleId}/run", projectId, scheduleId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.scheduleId").value(scheduleId))
                .andExpect(jsonPath("$.data.functionalPlanId").value(functionalPlanId)).andReturn();
        executionId = body(queued).at("/data/id").asText();
        String taskId = body(queued).at("/data/taskId").asText();
        Instant now = Instant.now();
        automation.applyRunnerEvent(new UiAutomationRunnerProtocol.Event(taskId, executionId, "playwright-test", "ACCEPTED", "TASK_ACCEPTED", "accepted", now));
        automation.applyRunnerEvent(new UiAutomationRunnerProtocol.Event(taskId, executionId, "playwright-test", "FAILED", "PLAYWRIGHT_EXECUTION_FAILED", "failed", now));
        String retryId = jdbc.sql("SELECT id FROM ui_automation_execution WHERE retry_of_execution_id = :id").param("id", executionId)
                .query(String.class).single();
        extraExecutionIds.add(retryId);
        Integer retryAttempt = jdbc.sql("SELECT retry_attempt FROM ui_automation_execution WHERE id = :id").param("id", retryId).query(Integer.class).single();
        assertEquals(1, retryAttempt);
        csrf = csrf(session);
        mvc.perform(post("/api/v1/projects/{projectId}/ui-automation/executions/{executionId}/cancel", projectId, retryId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        csrf = csrf(session);
        MvcResult manual = mvc.perform(post("/api/v1/projects/{projectId}/ui-automation/scenarios/{scenarioId}/executions", projectId, scenarioId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeoutSeconds\":300,\"functionalPlanId\":\"%s\"}".formatted(functionalPlanId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.functionalPlanName").value("Web UI 回归计划")).andReturn();
        extraExecutionIds.add(body(manual).at("/data/id").asText());
    }

    private Login login() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode data = body(csrf).path("data");
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(data.path("headerName").asText(), data.path("token").asText()).content("{\"email\":\"admin@testhub.local\",\"password\":\"TestHub2026!\"}"))
                .andExpect(status().isOk()).andReturn();
        return new Login(login.getResponse().getCookie("TESTHUB_SESSION"));
    }
    private Csrf csrf(Login session) throws Exception { MvcResult result = mvc.perform(get("/api/v1/auth/csrf").cookie(session.cookie())).andExpect(status().isOk()).andReturn(); JsonNode data = body(result).path("data"); return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"), data.path("headerName").asText(), data.path("token").asText()); }
    private String project(Login session) throws Exception { JsonNode data = body(mvc.perform(get("/api/v1/access/context").cookie(session.cookie())).andExpect(status().isOk()).andReturn()).path("data"); String organization = data.at("/organizations/0/id").asText(); return data.at("/projects/" + organization + "/0/id").asText(); }
    private void heartbeat() throws Exception {
        Instant started = Instant.now().minusSeconds(5), beat = Instant.now();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("runnerId", RUNNER_ID); value.put("runnerType", "playwright"); value.put("protocolVersion", "1");
        value.put("runnerVersion", "0.1.0-test"); value.put("executionEnabled", true); value.put("startedAt", started.toString()); value.put("heartbeatAt", beat.toString());
        value.put("maxConcurrentTasks", 2); value.put("activeTaskCount", 1); value.put("trustVersion", "1");
        value.put("signature", hmac(String.join("\n", RUNNER_ID, "playwright", "1", "0.1.0-test", "true", started.toString(), beat.toString(), "2", "1")));
        registry.publish("testhub:runner:playwright:members:v1", "testhub:runner:playwright:heartbeat:v1:" + RUNNER_ID, RUNNER_ID, json.writeValueAsString(value), java.time.Duration.ofMinutes(1));
    }
    private HttpHeaders artifactHeaders(String taskId, String execution, String type, byte[] content, String nonce) throws Exception {
        String timestamp = Instant.now().toString(), checksum = sha256(content);
        String signature = hmac(String.join("\n", "testhub.ui-automation-runner.internal-auth.v1", "POST", ARTIFACT_PATH, RUNNER_ID,
                timestamp, nonce, taskId, execution, type, String.valueOf(content.length), checksum));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-TestHub-Runner-Id", RUNNER_ID); headers.set("X-TestHub-Runner-Timestamp", timestamp); headers.set("X-TestHub-Runner-Nonce", nonce); headers.set("X-TestHub-Runner-Signature", signature);
        headers.set("X-TestHub-Task-Id", taskId); headers.set("X-TestHub-Execution-Id", execution); headers.set("X-TestHub-Artifact-Type", type);
        headers.set("X-TestHub-Artifact-Size", String.valueOf(content.length)); headers.set("X-TestHub-Artifact-SHA256", checksum);
        return headers;
    }
    private String sha256(byte[] content) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
    private String hmac(String value) throws Exception { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(RUNNER_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private record Login(Cookie cookie) { }
    private record Csrf(Cookie cookie, String header, String token) { }
}
