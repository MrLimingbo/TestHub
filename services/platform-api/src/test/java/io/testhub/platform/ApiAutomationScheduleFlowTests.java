package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.apiautomation.ApiAutomationScheduleService;
import jakarta.servlet.http.Cookie;
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
class ApiAutomationScheduleFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;
    @Autowired private ApiAutomationScheduleService schedules;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM api_automation_schedule WHERE name LIKE '定时计划验收 %'").update();
        jdbc.sql("DELETE FROM api_automation_execution WHERE scenario_name LIKE '定时计划验收 %'").update();
        jdbc.sql("DELETE FROM api_automation_scenario WHERE name LIKE '定时计划验收 %'").update();
        jdbc.sql("DELETE FROM api_automation_execution_profile WHERE name LIKE '定时计划验收 %'").update();
        jdbc.sql("DELETE FROM audit_log WHERE detail LIKE '%定时计划验收%'").update();
    }

    @Test
    void preservesDisabledStateWhenCreatingSchedule() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String scenarioId = createScenario(administrator, projectId, "定时计划验收 停用创建");
        Csrf csrf = csrf(administrator);
        MvcResult created = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/schedules", projectId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(schedulePayload(scenarioId, false, 3)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false)).andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asText();
        assertFalse(jdbc.sql("SELECT enabled FROM api_automation_schedule WHERE id = :id")
                .param("id", id).query(Boolean.class).single());
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/schedules/{id}/run", projectId, id)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isConflict());
    }

    @Test
    void persistsProjectScopedCronScheduleAndAutoDisablesAfterConfiguredFailures() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String scenarioId = createScenario(administrator, projectId, "定时计划验收 订单回归");
        Csrf csrf = csrf(administrator);
        MvcResult created = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/schedules", projectId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(schedulePayload(scenarioId, true, 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.maxConsecutiveFailures").value(1)).andReturn();
        String scheduleId = json(created).at("/data/id").asText();
        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/schedules", projectId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(scheduleId));

        schedules.executeQuartzJob(scheduleId);
        String executionId = jdbc.sql("SELECT last_execution_id FROM api_automation_schedule WHERE id = :id")
                .param("id", scheduleId).query(String.class).single();
        assertFalse(executionId.isBlank());
        jdbc.sql("""
                UPDATE api_automation_execution SET status = 'REJECTED', status_code = 'EXECUTION_DISABLED',
                    status_message = 'safe runner disabled', finished_at = CURRENT_TIMESTAMP(3)
                WHERE id = :executionId
                """).param("executionId", executionId).update();
        schedules.reconcileOutcomes();
        Boolean enabled = jdbc.sql("SELECT enabled FROM api_automation_schedule WHERE id = :id")
                .param("id", scheduleId).query(Boolean.class).single();
        Integer failures = jdbc.sql("SELECT consecutive_failures FROM api_automation_schedule WHERE id = :id")
                .param("id", scheduleId).query(Integer.class).single();
        assertFalse(enabled);
        assertEquals(1, failures);

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/api-automation/schedules/{scheduleId}", projectId, scheduleId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(schedulePayload(scenarioId, true, 3)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.maxConsecutiveFailures").value(3))
                .andExpect(jsonPath("$.data.consecutiveFailures").value(0));
    }

    @Test
    void rejectsInvalidQuartzCronBeforePersistingSchedule() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String scenarioId = createScenario(administrator, projectId, "定时计划验收 Cron 校验");
        Csrf csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/schedules", projectId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"定时计划验收 非法 Cron","scenarioId":"%s","cronExpression":"every five minutes",
                                "timezone":"Asia/Shanghai","timeoutSeconds":300,"maxConsecutiveFailures":3,"enabled":true}
                                """.formatted(scenarioId)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
        Integer count = jdbc.sql("SELECT COUNT(*) FROM api_automation_schedule WHERE name LIKE '定时计划验收 %'")
                .query(Integer.class).single();
        assertEquals(0, count);
    }

    @Test
    void manualAndQuartzTriggersUseTheSelectedExecutionProfile() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String scenarioId = createScenario(administrator, projectId, "定时计划验收 配置执行");
        Csrf csrf = csrf(administrator);
        MvcResult profile = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/execution-profiles", projectId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"定时计划验收 配置集","environmentCode":"test","variables":{"baseUrl":"https://profile.example.test"}}
                                """))
                .andExpect(status().isOk()).andReturn();
        String profileId = json(profile).at("/data/id").asText();
        MvcResult created = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/schedules", projectId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"定时计划验收 关联配置","scenarioId":"%s","executionProfileId":"%s",
                                 "cronExpression":"0 0 0 1 1 ? 2099","timezone":"Asia/Shanghai",
                                 "timeoutSeconds":300,"maxConsecutiveFailures":3,"enabled":true}
                                """.formatted(scenarioId, profileId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.executionProfileId").value(profileId)).andReturn();
        String scheduleId = json(created).at("/data/id").asText();
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/schedules/{scheduleId}/run", projectId, scheduleId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.executionProfileId").value(profileId));
        schedules.executeQuartzJob(scheduleId);
        String automaticExecution = jdbc.sql("SELECT last_execution_id FROM api_automation_schedule WHERE id = :id")
                .param("id", scheduleId).query(String.class).single();
        assertEquals(profileId, jdbc.sql("SELECT execution_profile_id FROM api_automation_execution WHERE id = :id")
                .param("id", automaticExecution).query(String.class).single());

        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/schedules", projectId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"定时计划验收 无效配置","scenarioId":"%s","executionProfileId":"01AAAAAAAAAAAAAAAAAAAAAAAA",
                                 "cronExpression":"0 0 0 1 1 ? 2099","timezone":"Asia/Shanghai",
                                 "timeoutSeconds":300,"maxConsecutiveFailures":3,"enabled":false}
                                """.formatted(scenarioId)))
                .andExpect(status().isBadRequest());
    }

    private String schedulePayload(String scenarioId, boolean enabled, int threshold) {
        return """
                {"name":"定时计划验收 每日回归","scenarioId":"%s","cronExpression":"0 0 0 1 1 ? 2099",
                "timezone":"Asia/Shanghai","timeoutSeconds":300,"maxConsecutiveFailures":%d,"enabled":%s}
                """.formatted(scenarioId, threshold, enabled);
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
