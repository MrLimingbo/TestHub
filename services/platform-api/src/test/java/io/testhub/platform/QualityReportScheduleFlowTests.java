package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.config.SystemConfigRepository;
import io.testhub.platform.reporting.QualityReportScheduleService;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
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
class QualityReportScheduleFlowTests {
    private static final String ALLOWED_HOSTS = "notifications.webhook.allowed-hosts";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;
    @Autowired private QualityReportScheduleService schedules;
    @Autowired private SystemConfigRepository configs;

    private Optional<String> previousAllowedHosts = Optional.empty();
    private String administratorId;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM quality_report_schedule WHERE name LIKE '定时质量报告验收 %'").update();
        jdbc.sql("DELETE FROM notification_template WHERE name LIKE '定时质量报告验收 %'").update();
        jdbc.sql("DELETE FROM audit_log WHERE detail LIKE '%定时质量报告验收%'").update();
        if (administratorId != null) {
            if (previousAllowedHosts.isPresent()) configs.upsert(ALLOWED_HOSTS, previousAllowedHosts.get(), administratorId);
            else configs.deleteNonSecret(ALLOWED_HOSTS);
        }
    }

    @Test
    void runsPersistedQualityScheduleAndQueuesOneIdempotentWebhookDelivery() throws Exception {
        LoginSession administrator = login();
        administratorId = jdbc.sql("SELECT id FROM user_account WHERE email = 'admin@testhub.local'").query(String.class).single();
        previousAllowedHosts = configs.findNonSecretValue(ALLOWED_HOSTS);
        configs.upsert(ALLOWED_HOSTS, "hooks.testhub.invalid", administratorId);
        String projectId = projectId(administrator);
        String templateId = createWebhookTemplate(administrator, projectId);

        Csrf csrf = csrf(administrator);
        MvcResult created = mvc.perform(post("/api/v1/reports/quality/schedules")
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"定时质量报告验收 每周质量","projectIds":["%s"],
                                "cronExpression":"0 0 0 1 1 ? 2099","timezone":"Asia/Shanghai","periodDays":7,"enabled":true}
                                """.formatted(projectId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true)).andReturn();
        String scheduleId = json(created).at("/data/id").asText();

        schedules.executeQuartzJob(scheduleId);
        mvc.perform(get("/api/v1/reports/quality/schedules/{scheduleId}/runs", scheduleId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].deliveryCount").value(1));
        MvcResult deliveries = mvc.perform(get("/api/v1/projects/{projectId}/notification-deliveries", projectId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].templateName").value("定时质量报告验收 Webhook"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING")).andReturn();
        String deliveryId = json(deliveries).at("/data/0/id").asText();
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM notification_delivery WHERE schedule_run_id = "
                        + "(SELECT id FROM quality_report_schedule_run WHERE schedule_id = :scheduleId)")
                .param("scheduleId", scheduleId).query(Integer.class).single());

        jdbc.sql("UPDATE notification_delivery SET status = 'FAILED', attempt_count = 5 WHERE id = :id")
                .param("id", deliveryId).update();
        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/notification-deliveries/{deliveryId}/retry", projectId, deliveryId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.attemptCount").value(0));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM notification_delivery WHERE template_id = :templateId")
                .param("templateId", templateId).query(Integer.class).single());
    }

    private String createWebhookTemplate(LoginSession session, String projectId) throws Exception {
        Csrf csrf = csrf(session);
        MvcResult result = mvc.perform(post("/api/v1/projects/{projectId}/notification-templates", projectId)
                        .cookie(session.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"定时质量报告验收 Webhook","eventCode":"quality.report.ready","channel":"WEBHOOK",
                                "subjectTemplate":"{{project}} {{status}}","bodyTemplate":"{{event}} {{task}}",
                                "targetUrl":"https://hooks.testhub.invalid/quality","signingSecret":"test-signing-secret","enabled":true}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.signingSecretConfigured").value(true)).andReturn();
        return json(result).at("/data/id").asText();
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
