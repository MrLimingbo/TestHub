package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
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
class ProjectDashboardFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    private String catalogId;
    private String caseId;
    private String planId;
    private String runId;
    private String runCaseId;
    private String apiCatalogId;
    private String apiId;
    private String scenarioId;
    private String automationExecutionId;
    private String performanceExecutionId;
    private String defectId;
    private String auditId;

    @AfterEach
    void clean() {
        delete("audit_log", auditId);
        delete("performance_test_execution", performanceExecutionId);
        delete("api_automation_execution", automationExecutionId);
        delete("api_automation_scenario", scenarioId);
        delete("api_definition", apiId);
        delete("api_catalog", apiCatalogId);
        delete("functional_defect", defectId);
        delete("functional_test_run_case", runCaseId);
        delete("functional_test_run", runId);
        delete("functional_test_plan", planId);
        delete("functional_case", caseId);
        delete("functional_catalog", catalogId);
    }

    @Test
    void aggregatesProjectScopedAssetsOutcomesAndAuditActivity() throws Exception {
        LoginSession administrator = login();
        Context context = context(administrator);
        JsonNode before = dashboard(administrator, context.projectId());
        createDashboardFixtures(context);
        JsonNode result = dashboard(administrator, context.projectId());
        assertEquals(7, result.path("periodDays").asInt());
        assertEquals(before.at("/summary/functionalCaseCount").asLong() + 1, result.at("/summary/functionalCaseCount").asLong());
        assertEquals(before.at("/summary/apiDefinitionCount").asLong() + 1, result.at("/summary/apiDefinitionCount").asLong());
        assertEquals(before.at("/summary/automationScenarioCount").asLong() + 1, result.at("/summary/automationScenarioCount").asLong());
        assertEquals(before.at("/summary/openDefectCount").asLong() + 1, result.at("/summary/openDefectCount").asLong());
        assertEquals(before.at("/summary/criticalDefectCount").asLong() + 1, result.at("/summary/criticalDefectCount").asLong());
        assertEquals(before.at("/summary/completedExecutions").asLong() + 3, result.at("/summary/completedExecutions").asLong());
        assertEquals(before.at("/summary/passedExecutions").asLong() + 2, result.at("/summary/passedExecutions").asLong());
        assertEquals(before.at("/summary/failedExecutions").asLong() + 1, result.at("/summary/failedExecutions").asLong());
        assertEquals("AT_RISK", result.at("/summary/healthStatus").asText());
        assertEquals(7, result.path("trend").size());
        assertTrue(result.path("distribution").get(0).path("completedExecutions").asLong() >= 1);
        assertTrue(result.path("distribution").get(1).path("completedExecutions").asLong() >= 1);
        assertTrue(result.path("distribution").get(2).path("completedExecutions").asLong() >= 1);
        assertTrue(result.path("activities").findValuesAsText("action").contains("DASHBOARD_FIXTURE_CREATED"));
    }

    @Test
    void rejectsUnsupportedDashboardPeriods() throws Exception {
        LoginSession administrator = login();
        String projectId = context(administrator).projectId();
        mvc.perform(get("/api/v1/projects/{projectId}/dashboard", projectId).param("days", "6")
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
    }

    private void createDashboardFixtures(Context context) {
        String userId = jdbc.sql("SELECT id FROM user_account WHERE email = 'admin@testhub.local'").query(String.class).single();
        Instant now = Instant.now();
        catalogId = id(); caseId = id(); planId = id(); runId = id(); runCaseId = id();
        apiCatalogId = id(); apiId = id(); scenarioId = id(); automationExecutionId = id(); performanceExecutionId = id();
        defectId = id(); auditId = id();
        jdbc.sql("""
                INSERT INTO functional_catalog (id, organization_id, project_id, parent_id, name, sort_order, created_by)
                VALUES (:id, :organizationId, :projectId, NULL, '看板验收目录', 0, :userId)
                """).param("id", catalogId).param("organizationId", context.organizationId()).param("projectId", context.projectId())
                .param("userId", userId).update();
        jdbc.sql("""
                INSERT INTO functional_case (id, organization_id, project_id, catalog_id, requirement_id, case_code, title, priority,
                                             status, precondition, steps, expected_result, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :catalogId, NULL, 'DASH-CASE-1', '看板验收用例', 'P1',
                        'READY', '', '[]', '通过', :userId, :userId)
                """).param("id", caseId).param("organizationId", context.organizationId()).param("projectId", context.projectId())
                .param("catalogId", catalogId).param("userId", userId).update();
        jdbc.sql("""
                INSERT INTO functional_test_plan (id, organization_id, project_id, plan_code, name, status, description, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, 'DASH-PLAN-1', '看板验收计划', 'ACTIVE', '', :userId, :userId)
                """).param("id", planId).param("organizationId", context.organizationId()).param("projectId", context.projectId())
                .param("userId", userId).update();
        jdbc.sql("""
                INSERT INTO functional_test_run (id, organization_id, project_id, plan_id, name, status, created_by, started_at, finished_at)
                VALUES (:id, :organizationId, :projectId, :planId, '看板验收执行', 'COMPLETED', :userId, :occurredAt, :occurredAt)
                """).param("id", runId).param("organizationId", context.organizationId()).param("projectId", context.projectId())
                .param("planId", planId).param("userId", userId).param("occurredAt", now).update();
        jdbc.sql("""
                INSERT INTO functional_test_run_case (id, run_id, case_id, status, note, executed_by, executed_at)
                VALUES (:id, :runId, :caseId, 'PASSED', '', :userId, :occurredAt)
                """).param("id", runCaseId).param("runId", runId).param("caseId", caseId).param("userId", userId)
                .param("occurredAt", now).update();
        jdbc.sql("""
                INSERT INTO api_catalog (id, organization_id, project_id, parent_id, name, sort_order, created_by)
                VALUES (:id, :organizationId, :projectId, NULL, '看板验收接口目录', 0, :userId)
                """).param("id", apiCatalogId).param("organizationId", context.organizationId()).param("projectId", context.projectId())
                .param("userId", userId).update();
        jdbc.sql("""
                INSERT INTO api_definition (id, organization_id, project_id, catalog_id, method, path, name, status, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :catalogId, 'GET', '/dashboard-fixture', '看板验收接口', 'DRAFT', :userId, :userId)
                """).param("id", apiId).param("organizationId", context.organizationId()).param("projectId", context.projectId())
                .param("catalogId", apiCatalogId).param("userId", userId).update();
        jdbc.sql("""
                INSERT INTO api_automation_scenario (id, organization_id, project_id, name, description, environment_code,
                                                      failure_strategy, version, steps, variables, created_by)
                VALUES (:id, :organizationId, :projectId, '看板验收自动化', '', 'default', 'STOP', 1,
                        CAST('[]' AS JSON), CAST('{}' AS JSON), :userId)
                """).param("id", scenarioId).param("organizationId", context.organizationId()).param("projectId", context.projectId())
                .param("userId", userId).update();
        jdbc.sql("""
                INSERT INTO api_automation_execution (id, task_id, organization_id, project_id, scenario_id, scenario_name,
                                                       scenario_version, status, manifest_ref, requested_by, requested_at, deadline_at, finished_at)
                VALUES (:id, :taskId, :organizationId, :projectId, :scenarioId, '看板验收自动化', 1, 'FAILED', 'dashboard-fixture',
                        :userId, :occurredAt, :deadlineAt, :occurredAt)
                """).param("id", automationExecutionId).param("taskId", id()).param("organizationId", context.organizationId())
                .param("projectId", context.projectId()).param("scenarioId", scenarioId).param("userId", userId)
                .param("occurredAt", now).param("deadlineAt", now.plusSeconds(300)).update();
        jdbc.sql("""
                INSERT INTO performance_test_execution (id, task_id, organization_id, project_id, plan_id, plan_name, plan_version,
                                                        status, status_code, status_message, manifest_ref, requested_by, requested_at, deadline_at, finished_at)
                VALUES (:id, :taskId, :organizationId, :projectId, :planId, '看板验收压测', 1, 'SUCCEEDED', 'SUCCEEDED', '完成',
                        'dashboard-fixture', :userId, :occurredAt, :deadlineAt, :occurredAt)
                """).param("id", performanceExecutionId).param("taskId", id()).param("organizationId", context.organizationId())
                .param("projectId", context.projectId()).param("planId", id()).param("userId", userId)
                .param("occurredAt", now).param("deadlineAt", now.plusSeconds(300)).update();
        jdbc.sql("""
                INSERT INTO functional_defect (id, organization_id, project_id, defect_code, title, severity, status, source,
                                                description, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, 'DASH-BUG-1', '看板验收严重缺陷', 'CRITICAL', 'OPEN', 'TESTHUB', '', :userId, :userId)
                """).param("id", defectId).param("organizationId", context.organizationId()).param("projectId", context.projectId())
                .param("userId", userId).update();
        jdbc.sql("""
                INSERT INTO audit_log (id, user_id, organization_id, project_id, action, resource_type, outcome, request_id, detail)
                VALUES (:id, :userId, :organizationId, :projectId, 'DASHBOARD_FIXTURE_CREATED', 'DASHBOARD_FIXTURE', 'SUCCESS',
                        'dashboard-test', CAST('{}' AS JSON))
                """).param("id", auditId).param("userId", userId).param("organizationId", context.organizationId())
                .param("projectId", context.projectId()).update();
    }

    private void delete(String table, String id) {
        if (id != null) jdbc.sql("DELETE FROM " + table + " WHERE id = :id").param("id", id).update();
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

    private Context context(LoginSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/access/context").cookie(session.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = json(result).path("data");
        String organizationId = data.at("/organizations/0/id").asText();
        return new Context(organizationId, data.at("/projects/" + organizationId + "/0/id").asText());
    }

    private JsonNode dashboard(LoginSession session, String projectId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/projects/{projectId}/dashboard", projectId).param("days", "7")
                        .cookie(session.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        return json(result).path("data");
    }

    private JsonNode json(MvcResult result) throws Exception { return objectMapper.readTree(result.getResponse().getContentAsString()); }
    private String id() { return UlidCreator.getUlid().toString(); }
    private record LoginSession(Cookie sessionCookie) { }
    private record Context(String organizationId, String projectId) { }
}
