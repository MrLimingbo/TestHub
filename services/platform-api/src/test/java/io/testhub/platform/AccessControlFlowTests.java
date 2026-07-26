package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AccessControlFlowTests {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void removeTestTokens() {
        jdbc.sql("DELETE FROM personal_access_token WHERE name = :name")
                .param("name", "test-access-token").update();
        jdbc.sql("DELETE FROM project_parameter WHERE parameter_key IN ('TEST_PARAMETER_URL', 'TEST_SECRET_VALUE')").update();
        jdbc.sql("DELETE FROM system_config WHERE config_key = 'test.config.value'").update();
        jdbc.sql("DELETE FROM notification_template WHERE name = 'test-notification-template'").update();
        jdbc.sql("DELETE FROM execution_task WHERE name = 'test-execution-task'").update();
        jdbc.sql("""
                DELETE FROM audit_log
                WHERE resource_id = 'test.config.value'
                   OR resource_id = 'test:TEST_PARAMETER_URL'
                   OR resource_id = 'test:TEST_SECRET_VALUE'
                   OR detail LIKE '%test-execution-task%'
                   OR detail LIKE '%test-access-token%'
                """).update();
    }

    @Test
    void projectAdministratorCanCreateRevocableProjectToken() throws Exception {
        LoginSession administrator = login("admin@testhub.local");
        MvcResult context = mvc.perform(get("/api/v1/access/context").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.organizations[0].name").value("星云科技"))
                .andReturn();
        String organizationId = json(context).at("/data/organizations/0/id").asText();
        String projectId = json(context).at("/data/projects/" + organizationId + "/0/id").asText();

        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        MvcResult created = mvc.perform(post("/api/v1/projects/{projectId}/access-tokens", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(json(csrf).at("/data/headerName").asText(), json(csrf).at("/data/token").asText())
                        .content("{\"name\":\"test-access-token\",\"scopes\":[\"project:read\"],\"expiresInDays\":30}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").value(org.hamcrest.Matchers.startsWith("thp_")))
                .andReturn();
        String rawToken = json(created).at("/data/token").asText();

        mvc.perform(get("/api/v1/access/context").header("Authorization", "Bearer " + rawToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.projects." + organizationId + "[0].id").value(projectId));
    }

    @Test
    void testerCannotChangeProjectMembers() throws Exception {
        LoginSession tester = login("tester@testhub.local");
        MvcResult context = mvc.perform(get("/api/v1/access/context").cookie(tester.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        String organizationId = json(context).at("/data/organizations/0/id").asText();
        String projectId = json(context).at("/data/projects/" + organizationId + "/0/id").asText();
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf").cookie(tester.sessionCookie()))
                .andExpect(status().isOk()).andReturn();

        mvc.perform(post("/api/v1/projects/{projectId}/members", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(tester.sessionCookie(), csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(json(csrf).at("/data/headerName").asText(), json(csrf).at("/data/token").asText())
                        .content("{\"email\":\"lead@testhub.local\",\"roleCode\":\"VIEWER\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void projectAdministratorCanMaintainNonSecretProjectParameters() throws Exception {
        LoginSession administrator = login("admin@testhub.local");
        String projectId = projectId(administrator);
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();

        mvc.perform(post("/api/v1/projects/{projectId}/parameters", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(json(csrf).at("/data/headerName").asText(), json(csrf).at("/data/token").asText())
                        .content("{\"environmentCode\":\"test\",\"key\":\"TEST_PARAMETER_URL\",\"value\":\"https://test.example.local\"}"))
                .andExpect(status().isNoContent());

        MvcResult listed = mvc.perform(get("/api/v1/projects/{projectId}/parameters", projectId)
                        .param("environment", "test").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.parameters[0].key").value("TEST_PARAMETER_URL"))
                .andExpect(jsonPath("$.data.parameters[0].secret").value(false)).andReturn();
        String parameterId = json(listed).at("/data/parameters/0/id").asText();

        MvcResult deleteCsrf = mvc.perform(get("/api/v1/auth/csrf").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/projects/{projectId}/parameters/{parameterId}", projectId, parameterId)
                        .cookie(administrator.sessionCookie(), deleteCsrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(json(deleteCsrf).at("/data/headerName").asText(), json(deleteCsrf).at("/data/token").asText()))
                .andExpect(status().isNoContent());
    }

    @Test
    void testerCannotModifyProjectParameters() throws Exception {
        LoginSession tester = login("tester@testhub.local");
        String projectId = projectId(tester);
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf").cookie(tester.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(post("/api/v1/projects/{projectId}/parameters", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(tester.sessionCookie(), csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(json(csrf).at("/data/headerName").asText(), json(csrf).at("/data/token").asText())
                        .content("{\"environmentCode\":\"test\",\"key\":\"TEST_PARAMETER_URL\",\"value\":\"https://denied.example.local\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void projectAdministratorStoresEncryptedEnvironmentSecretAndReceivesOnlyMetadata() throws Exception {
        LoginSession administrator = login("admin@testhub.local");
        String projectId = projectId(administrator);
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        String rawSecret = "super-sensitive-test-value";

        mvc.perform(post("/api/v1/projects/{projectId}/secrets", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(json(csrf).at("/data/headerName").asText(), json(csrf).at("/data/token").asText())
                        .content("{\"environmentCode\":\"test\",\"key\":\"TEST_SECRET_VALUE\",\"value\":\"" + rawSecret + "\"}"))
                .andExpect(status().isNoContent());

        MvcResult listed = mvc.perform(get("/api/v1/projects/{projectId}/secrets", projectId)
                        .param("environment", "test").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.secrets[0].key").value("TEST_SECRET_VALUE"))
                .andExpect(jsonPath("$.data.secrets[0].value").doesNotExist()).andReturn();
        String secretId = json(listed).at("/data/secrets/0/id").asText();
        String storedValue = jdbc.sql("SELECT parameter_value FROM project_parameter WHERE id = :id")
                .param("id", secretId).query(String.class).single();
        assertTrue(storedValue.startsWith("v2:"));
        assertFalse(storedValue.contains(rawSecret));

        MvcResult deleteCsrf = mvc.perform(get("/api/v1/auth/csrf").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/projects/{projectId}/secrets/{secretId}", projectId, secretId)
                        .cookie(administrator.sessionCookie(), deleteCsrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(json(deleteCsrf).at("/data/headerName").asText(), json(deleteCsrf).at("/data/token").asText()))
                .andExpect(status().isNoContent());
    }

    @Test
    void systemAdministratorCanMaintainConfigurationAndReviewAuditTrail() throws Exception {
        LoginSession administrator = login("admin@testhub.local");
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(post("/api/v1/system-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(json(csrf).at("/data/headerName").asText(), json(csrf).at("/data/token").asText())
                        .content("{\"key\":\"test.config.value\",\"value\":\"enabled\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/system-config").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].key").value("test.config.value"))
                .andExpect(jsonPath("$.data[0].value").value("enabled"));
        mvc.perform(get("/api/v1/audit-logs").param("action", "SYSTEM_CONFIG_UPSERTED").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].action").value("SYSTEM_CONFIG_UPSERTED"))
                .andExpect(jsonPath("$.data[0].resourceId").value("test.config.value"));

        MvcResult deleteCsrf = mvc.perform(get("/api/v1/auth/csrf").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/system-config/{key}", "test.config.value")
                        .cookie(administrator.sessionCookie(), deleteCsrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(json(deleteCsrf).at("/data/headerName").asText(), json(deleteCsrf).at("/data/token").asText()))
                .andExpect(status().isNoContent());
    }

    @Test
    void testerCannotReadSystemConfiguration() throws Exception {
        LoginSession tester = login("tester@testhub.local");
        mvc.perform(get("/api/v1/system-config").cookie(tester.sessionCookie()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void projectAdministratorCanManageNotificationTemplate() throws Exception {
        LoginSession administrator = login("admin@testhub.local");
        String projectId = projectId(administrator);
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        String payload = "{\"name\":\"test-notification-template\",\"eventCode\":\"test.run.failed\",\"channel\":\"WEBHOOK\",\"subjectTemplate\":\"Test failed\",\"bodyTemplate\":\"Run {{runId}} failed\",\"enabled\":true}";
        MvcResult created = mvc.perform(post("/api/v1/projects/{projectId}/notification-templates", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(json(csrf).at("/data/headerName").asText(), json(csrf).at("/data/token").asText())
                        .content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.channel").value("WEBHOOK"))
                .andReturn();
        String templateId = json(created).at("/data/id").asText();
        mvc.perform(get("/api/v1/projects/{projectId}/notification-templates", projectId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("test-notification-template"));

        MvcResult updateCsrf = mvc.perform(get("/api/v1/auth/csrf").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/projects/{projectId}/notification-templates/{templateId}", projectId, templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), updateCsrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(json(updateCsrf).at("/data/headerName").asText(), json(updateCsrf).at("/data/token").asText())
                        .content(payload.replace("true}", "false}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));

        MvcResult deleteCsrf = mvc.perform(get("/api/v1/auth/csrf").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/projects/{projectId}/notification-templates/{templateId}", projectId, templateId)
                        .cookie(administrator.sessionCookie(), deleteCsrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(json(deleteCsrf).at("/data/headerName").asText(), json(deleteCsrf).at("/data/token").asText()))
                .andExpect(status().isNoContent());
    }

    @Test
    void projectMemberCanCreateUpdateAndCancelExecutionTask() throws Exception {
        LoginSession administrator = login("admin@testhub.local");
        String projectId = projectId(administrator);
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        MvcResult created = mvc.perform(post("/api/v1/projects/{projectId}/tasks", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(json(csrf).at("/data/headerName").asText(), json(csrf).at("/data/token").asText())
                        .content("{\"taskType\":\"API\",\"name\":\"test-execution-task\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.progress").value(0)).andReturn();
        String taskId = json(created).at("/data/id").asText();

        mvc.perform(get("/api/v1/projects/{projectId}/tasks", projectId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("test-execution-task"));
        MvcResult updateCsrf = mvc.perform(get("/api/v1/auth/csrf").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/projects/{projectId}/tasks/{taskId}", projectId, taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), updateCsrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(json(updateCsrf).at("/data/headerName").asText(), json(updateCsrf).at("/data/token").asText())
                        .content("{\"name\":\"test-execution-task\",\"status\":\"RUNNING\",\"progress\":45}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.progress").value(45));

        MvcResult cancelCsrf = mvc.perform(get("/api/v1/auth/csrf").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(post("/api/v1/projects/{projectId}/tasks/{taskId}/cancel", projectId, taskId)
                        .cookie(administrator.sessionCookie(), cancelCsrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(json(cancelCsrf).at("/data/headerName").asText(), json(cancelCsrf).at("/data/token").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    private LoginSession login(String email) throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode csrfData = json(csrf).path("data");
        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(csrfData.path("headerName").asText(), csrfData.path("token").asText())
                        .content("{\"email\":\"" + email + "\",\"password\":\"TestHub2026!\"}"))
                .andExpect(status().isOk()).andReturn();
        return new LoginSession(login.getResponse().getCookie("TESTHUB_SESSION"));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String projectId(LoginSession session) throws Exception {
        MvcResult context = mvc.perform(get("/api/v1/access/context").cookie(session.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        String organizationId = json(context).at("/data/organizations/0/id").asText();
        return json(context).at("/data/projects/" + organizationId + "/0/id").asText();
    }

    private record LoginSession(Cookie sessionCookie) {
    }
}
