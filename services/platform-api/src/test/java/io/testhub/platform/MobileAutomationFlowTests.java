package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.mobileautomation.MobileAutomationService;
import io.testhub.platform.mobileautomation.MobileAutomationExecutionArtifactService;
import io.testhub.platform.mobileautomation.MobileAutomationExecutionArtifact;
import io.testhub.platform.mobileautomation.MobileRunnerProtocol;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class MobileAutomationFlowTests {
    private static final String RUNNER_ID = "appium-runner-flow-test";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcClient jdbc;
    @Autowired private MobileAutomationService automation;
    @Autowired private MobileAutomationExecutionArtifactService artifacts;
    private String deviceId;
    private String applicationId;
    private String elementId;
    private String scenarioId;
    private String executionId;
    private String cancelledExecutionId;

    @AfterEach
    void clean() {
        if (cancelledExecutionId != null) jdbc.sql("DELETE FROM mobile_automation_execution WHERE id=:id").param("id", cancelledExecutionId).update();
        if (executionId != null) jdbc.sql("DELETE FROM mobile_automation_execution WHERE id=:id").param("id", executionId).update();
        if (scenarioId != null) jdbc.sql("DELETE FROM mobile_automation_scenario WHERE id=:id").param("id", scenarioId).update();
        if (elementId != null) jdbc.sql("DELETE FROM mobile_automation_element WHERE id=:id").param("id", elementId).update();
        if (applicationId != null) jdbc.sql("DELETE FROM mobile_automation_application WHERE id=:id").param("id", applicationId).update();
        if (deviceId != null) jdbc.sql("DELETE FROM mobile_automation_device WHERE id=:id").param("id", deviceId).update();
        jdbc.sql("DELETE FROM audit_log WHERE action LIKE 'MOBILE_AUTOMATION_%'").update();
    }

    @Test
    void createsProjectScopedMobileAssetsAndProducesReportFromAppiumEvents() throws Exception {
        Login session = login();
        String projectId = project(session);
        Csrf csrf = csrf(session);
        MvcResult device = mvc.perform(post("/api/v1/projects/{projectId}/mobile-automation/devices", projectId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Android 15 验收设备","platform":"ANDROID","udid":"emulator-5554-flow","automationName":"UiAutomator2","capabilities":{"appium:platformVersion":"15"},"enabled":true}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.platform").value("ANDROID")).andReturn();
        deviceId = body(device).at("/data/id").asText();
        csrf = csrf(session);
        MvcResult application = mvc.perform(post("/api/v1/projects/{projectId}/mobile-automation/applications", projectId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"TestHub Android 验收包","platform":"ANDROID","appIdentifier":"io.testhub.demo","versionName":"1.0.0","enabled":true}
                                """))
                .andExpect(status().isOk()).andReturn();
        applicationId = body(application).at("/data/id").asText();
        csrf = csrf(session);
        MvcResult element = mvc.perform(post("/api/v1/projects/{projectId}/mobile-automation/elements", projectId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"登录按钮","platform":"ANDROID","locatorStrategy":"ACCESSIBILITY_ID","locatorValue":"login-button","description":"移动端验收对象"}
                                """))
                .andExpect(status().isOk()).andReturn();
        elementId = body(element).at("/data/id").asText();
        csrf = csrf(session);
        MvcResult scenario = mvc.perform(post("/api/v1/projects/{projectId}/mobile-automation/scenarios", projectId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"%s","applicationId":"%s","name":"Android 登录验收","description":"隔离 Appium Runner 闭环","failureStrategy":"STOP","variables":{"account":"demo"},"steps":[{"id":"launch","type":"LAUNCH","name":"启动应用","timeoutMillis":10000},{"id":"tap-login","type":"TAP","name":"点击登录","elementId":"%s","timeoutMillis":10000}]}
                                """.formatted(deviceId, applicationId, elementId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.steps.length()").value(2)).andReturn();
        scenarioId = body(scenario).at("/data/id").asText();
        csrf = csrf(session);
        MvcResult queued = mvc.perform(post("/api/v1/projects/{projectId}/mobile-automation/scenarios/{scenarioId}/executions", projectId, scenarioId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeoutSeconds\":300}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED")).andReturn();
        executionId = body(queued).at("/data/id").asText();
        String taskId = body(queued).at("/data/taskId").asText();
        Instant now = Instant.now();
        automation.applyRunnerEvent(new MobileRunnerProtocol.Event(taskId, executionId, RUNNER_ID, "ACCEPTED", "TASK_ACCEPTED", "accepted", now));
        automation.applyRunnerEvent(new MobileRunnerProtocol.Event(taskId, executionId, RUNNER_ID, "RUNNING", "APPIUM_SESSION_STARTED", "running", now));
        byte[] screenshot = "png-screenshot-artifact".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(screenshot));
        MobileAutomationExecutionArtifact archived = artifacts.archive(RUNNER_ID, new MobileAutomationExecutionArtifactService.Upload(taskId, executionId, "SCREENSHOT", screenshot.length, checksum), new ByteArrayInputStream(screenshot));
        mvc.perform(get("/api/v1/projects/{projectId}/mobile-automation/executions/{executionId}/artifacts/{artifactId}/view", projectId, executionId, archived.id()).cookie(session.cookie()))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(content().bytes(screenshot));
        automation.applyStepResult(new MobileRunnerProtocol.StepResult(taskId, executionId, RUNNER_ID, "launch", 1, "LAUNCH", "启动应用", "PASSED", 210, null, now));
        automation.applyStepResult(new MobileRunnerProtocol.StepResult(taskId, executionId, RUNNER_ID, "tap-login", 2, "TAP", "点击登录", "PASSED", 80, null, now));
        automation.applyRunnerEvent(new MobileRunnerProtocol.Event(taskId, executionId, RUNNER_ID, "SUCCEEDED", "APPIUM_EXECUTION_SUCCEEDED", "done", now));
        mvc.perform(get("/api/v1/projects/{projectId}/mobile-automation/executions/{executionId}/report", projectId, executionId).cookie(session.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.execution.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.summary.totalSteps").value(2)).andExpect(jsonPath("$.data.summary.passedSteps").value(2));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM mobile_automation_execution_manifest WHERE execution_id=:id").param("id", executionId).query(Integer.class).single());
        csrf = csrf(session);
        MvcResult cancellable = mvc.perform(post("/api/v1/projects/{projectId}/mobile-automation/scenarios/{scenarioId}/executions", projectId, scenarioId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON).content("{\"timeoutSeconds\":300}"))
                .andExpect(status().isOk()).andReturn();
        cancelledExecutionId = body(cancellable).at("/data/id").asText();
        csrf = csrf(session);
        mvc.perform(post("/api/v1/projects/{projectId}/mobile-automation/executions/{executionId}/cancel", projectId, cancelledExecutionId)
                        .cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLING"));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM mobile_automation_runner_outbox WHERE execution_id=:id AND stream_key='testhub:runner:appium:cancellations:v1'").param("id", cancelledExecutionId).query(Integer.class).single());
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
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private record Login(Cookie cookie) { }
    private record Csrf(Cookie cookie, String header, String token) { }
}
