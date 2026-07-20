package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
class ApiAutomationExternalTriggerFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM api_automation_webhook_trigger WHERE name LIKE 'Webhook 与CLI验收 %'").update();
        jdbc.sql("DELETE FROM api_automation_execution WHERE scenario_name LIKE 'Webhook 与CLI验收 %'").update();
        jdbc.sql("DELETE FROM api_automation_scenario WHERE name LIKE 'Webhook 与CLI验收 %'").update();
        jdbc.sql("DELETE FROM personal_access_token WHERE name LIKE 'Webhook 与CLI验收 %'").update();
        jdbc.sql("DELETE FROM audit_log WHERE detail LIKE '%Webhook 与CLI验收%'").update();
    }

    @Test
    void scopedPatCanTriggerAndReadOnlyPatCannotTriggerWhileReportStaysRedacted() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String scenarioId = createScenario(administrator, projectId, "Webhook 与CLI验收 PAT 执行报告");
        String executeToken = createToken(administrator, projectId, "Webhook 与CLI验收 execute", "[\"run:execute\",\"project:read\"]");

        MvcResult execution = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}/executions", projectId, scenarioId)
                        .header("Authorization", "Bearer " + executeToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeoutSeconds\":300}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED")).andReturn();
        String executionId = json(execution).at("/data/id").asText();

        MvcResult report = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/report", projectId, executionId)
                        .header("Authorization", "Bearer " + executeToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.contract").value("testhub.api-automation.execution-report"))
                .andExpect(jsonPath("$.data.summary.httpResultCount").value(0)).andReturn();
        String renderedReport = report.getResponse().getContentAsString();
        assertFalse(renderedReport.contains("api.example.test"));
        assertFalse(renderedReport.contains("baseUrl"));
        assertFalse(renderedReport.contains("responseBody"));

        String readOnlyToken = createToken(administrator, projectId, "Webhook 与CLI验收 read-only", "[\"project:read\"]");
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}/executions", projectId, scenarioId)
                        .header("Authorization", "Bearer " + readOnlyToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeoutSeconds\":300}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void signedWebhookIsOneTimeEncryptedAtRestAndCanBeDisabled() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String scenarioId = createScenario(administrator, projectId, "Webhook 与CLI验收 入站签名");
        Csrf csrf = csrf(administrator);
        MvcResult created = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/webhook-triggers", projectId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Webhook 与CLI验收 发布触发\",\"scenarioId\":\"" + scenarioId + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.webhook.enabled").value(true)).andReturn();
        String webhookId = json(created).at("/data/webhook/id").asText();
        String secret = json(created).at("/data/secret").asText();
        assertTrue(secret.startsWith("thw_"));
        String stored = jdbc.sql("SELECT secret_ciphertext FROM api_automation_webhook_trigger WHERE id = :id")
                .param("id", webhookId).query(String.class).single();
        assertTrue(stored.startsWith("v1:"));
        assertFalse(stored.contains(secret));
        MvcResult listed = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/webhook-triggers", projectId)
                        .cookie(administrator.sessionCookie())).andExpect(status().isOk()).andReturn();
        assertFalse(listed.getResponse().getContentAsString().contains(secret));

        String body = "{\"timeoutSeconds\":300}";
        String timestamp = Instant.now().toString();
        String nonce = "webhook_nonce_0123456789";
        String signature = signature(secret, timestamp, nonce, body);
        mvc.perform(post("/api/v1/integrations/api-automation/webhooks/{webhookId}/trigger", webhookId)
                        .contentType(MediaType.APPLICATION_JSON).header("X-TestHub-Webhook-Timestamp", timestamp)
                        .header("X-TestHub-Webhook-Nonce", nonce).header("X-TestHub-Webhook-Signature", signature).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED"));
        mvc.perform(post("/api/v1/integrations/api-automation/webhooks/{webhookId}/trigger", webhookId)
                        .contentType(MediaType.APPLICATION_JSON).header("X-TestHub-Webhook-Timestamp", timestamp)
                        .header("X-TestHub-Webhook-Nonce", nonce).header("X-TestHub-Webhook-Signature", signature).content(body))
                .andExpect(status().isUnauthorized());

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/api-automation/webhook-triggers/{webhookId}", projectId, webhookId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        String disabledTimestamp = Instant.now().toString();
        String disabledNonce = "webhook_nonce_disabled_123456";
        mvc.perform(post("/api/v1/integrations/api-automation/webhooks/{webhookId}/trigger", webhookId)
                        .contentType(MediaType.APPLICATION_JSON).header("X-TestHub-Webhook-Timestamp", disabledTimestamp)
                        .header("X-TestHub-Webhook-Nonce", disabledNonce)
                        .header("X-TestHub-Webhook-Signature", signature(secret, disabledTimestamp, disabledNonce, body)).content(body))
                .andExpect(status().isNotFound());
    }

    private String createScenario(LoginSession session, String projectId, String name) throws Exception {
        Csrf csrf = csrf(session);
        MvcResult response = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .cookie(session.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"%s","environmentCode":"test","failureStrategy":"STOP",
                                "variables":{"baseUrl":"https://api.example.test"},"steps":[
                                {"id":"request-order","type":"REQUEST","name":"读取订单","config":{"method":"GET","url":"{{baseUrl}}/orders/1"}}]}
                                """.formatted(name)))
                .andExpect(status().isOk()).andReturn();
        return json(response).at("/data/id").asText();
    }

    private String createToken(LoginSession session, String projectId, String name, String scopes) throws Exception {
        Csrf csrf = csrf(session);
        MvcResult response = mvc.perform(post("/api/v1/projects/{projectId}/access-tokens", projectId)
                        .cookie(session.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"scopes\":" + scopes + ",\"expiresInDays\":30}"))
                .andExpect(status().isOk()).andReturn();
        return json(response).at("/data/token").asText();
    }

    private String signature(String secret, String timestamp, String nonce, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((timestamp + "\n" + nonce + "\n" + body).getBytes(StandardCharsets.UTF_8)));
    }

    private LoginSession login() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode data = json(csrf).path("data");
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN")).header(data.path("headerName").asText(), data.path("token").asText())
                        .content("{\"email\":\"admin@testhub.local\",\"password\":\"TestHub2026!\"}"))
                .andExpect(status().isOk()).andReturn();
        return new LoginSession(login.getResponse().getCookie("TESTHUB_SESSION"));
    }

    private Csrf csrf(LoginSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").cookie(session.sessionCookie())).andExpect(status().isOk()).andReturn();
        JsonNode data = json(result).path("data");
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"), data.path("headerName").asText(), data.path("token").asText());
    }

    private String projectId(LoginSession session) throws Exception {
        MvcResult context = mvc.perform(get("/api/v1/access/context").cookie(session.sessionCookie())).andExpect(status().isOk()).andReturn();
        String organizationId = json(context).at("/data/organizations/0/id").asText();
        return json(context).at("/data/projects/" + organizationId + "/0/id").asText();
    }

    private JsonNode json(MvcResult result) throws Exception { return objectMapper.readTree(result.getResponse().getContentAsString()); }
    private record LoginSession(Cookie sessionCookie) { }
    private record Csrf(Cookie cookie, String headerName, String token) { }
}
