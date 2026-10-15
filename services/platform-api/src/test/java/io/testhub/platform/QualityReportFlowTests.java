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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class QualityReportFlowTests {
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    private String projectId;
    private String defectId;
    private final List<String> executionIds = new ArrayList<>();

    @AfterEach
    void clean() {
        if (projectId == null) return;
        jdbc.sql("DELETE FROM audit_log WHERE project_id = :projectId AND action = 'QUALITY_REPORT_EXPORTED'")
                .param("projectId", projectId).update();
        executionIds.forEach(id -> jdbc.sql("DELETE FROM performance_test_execution WHERE id = :id").param("id", id).update());
        if (defectId != null) jdbc.sql("DELETE FROM functional_defect WHERE id = :id").param("id", defectId).update();
        jdbc.sql("DELETE FROM project WHERE id = :id").param("id", projectId).update();
    }

    @Test
    void reportsSelectedProjectsAndExportsFormulaSafeCsv() throws Exception {
        LoginSession administrator = login("admin@testhub.local");
        Context context = context(administrator);
        createFixtures(context.organizationId());
        LocalDate today = LocalDate.now(DISPLAY_ZONE);

        JsonNode report = report(administrator, projectId, today, today);
        assertEquals(1, report.path("projectCount").asInt());
        assertEquals(2, report.at("/summary/completedExecutions").asInt());
        assertEquals(1, report.at("/summary/passedExecutions").asInt());
        assertEquals(1, report.at("/summary/failedExecutions").asInt());
        assertEquals(50, report.at("/summary/passRatePercent").asInt());
        assertEquals(1, report.at("/summary/criticalDefectCount").asInt());
        assertEquals("AT_RISK", report.at("/projects/0/healthStatus").asText());
        assertEquals("=质量报表导出", report.at("/projects/0/projectName").asText());
        assertEquals(1, report.path("trend").size());

        MvcResult exported = mvc.perform(get("/api/v1/reports/quality/export")
                        .param("projectIds", projectId).param("from", today.toString()).param("to", today.toString())
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        assertTrue(exported.getResponse().getContentType().startsWith("text/csv"));
        assertTrue(exported.getResponse().getHeader("Content-Disposition").contains("testhub-quality-report-"));
        assertTrue(exported.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("\"'=质量报表导出\""));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE project_id = :projectId AND action = 'QUALITY_REPORT_EXPORTED'")
                .param("projectId", projectId).query(Integer.class).single());
    }

    @Test
    void enforcesDateRangeAndProjectAccess() throws Exception {
        LoginSession administrator = login("admin@testhub.local");
        Context context = context(administrator);
        createFixtures(context.organizationId());
        LocalDate today = LocalDate.now(DISPLAY_ZONE);
        LoginSession tester = login("tester@testhub.local");

        mvc.perform(get("/api/v1/reports/quality").param("projectIds", projectId)
                        .param("from", today.toString()).param("to", today.toString()).cookie(tester.sessionCookie()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reports/quality").param("from", today.plusDays(1).toString())
                        .param("to", today.toString()).cookie(administrator.sessionCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
    }

    private void createFixtures(String organizationId) {
        String userId = jdbc.sql("SELECT id FROM user_account WHERE email = 'admin@testhub.local'").query(String.class).single();
        Instant now = Instant.now();
        projectId = id();
        jdbc.sql("INSERT INTO project (id, organization_id, name, code) VALUES (:id, :organizationId, :name, :code)")
                .param("id", projectId).param("organizationId", organizationId).param("name", "=质量报表导出")
                .param("code", "quality-report-" + projectId.toLowerCase()).update();
        executionIds.add(id());
        executionIds.add(id());
        insertExecution(executionIds.get(0), organizationId, userId, now, "SUCCEEDED");
        insertExecution(executionIds.get(1), organizationId, userId, now, "FAILED");
        defectId = id();
        jdbc.sql("""
                INSERT INTO functional_defect (id, organization_id, project_id, defect_code, title, severity, status, source,
                                                description, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, 'QUALITY-REPORT-1', '导出安全验收缺陷', 'CRITICAL', 'OPEN',
                        'TESTHUB', '', :userId, :userId)
                """).param("id", defectId).param("organizationId", organizationId).param("projectId", projectId)
                .param("userId", userId).update();
    }

    private void insertExecution(String id, String organizationId, String userId, Instant occurredAt, String status) {
        jdbc.sql("""
                INSERT INTO performance_test_execution (id, task_id, organization_id, project_id, plan_id, plan_name, plan_version,
                                                        status, status_code, status_message, manifest_ref, requested_by, requested_at,
                                                        deadline_at, finished_at)
                VALUES (:id, :taskId, :organizationId, :projectId, :planId, '质量报表验收压测', 1, :status, :status,
                        '验收', 'quality-report-fixture', :userId, :occurredAt, :deadlineAt, :occurredAt)
                """).param("id", id).param("taskId", id()).param("organizationId", organizationId).param("projectId", projectId)
                .param("planId", id()).param("status", status).param("userId", userId).param("occurredAt", occurredAt)
                .param("deadlineAt", occurredAt.plusSeconds(300)).update();
    }

    private LoginSession login(String email) throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode data = json(csrf).path("data");
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(data.path("headerName").asText(), data.path("token").asText())
                        .content("{\"email\":\"" + email + "\",\"password\":\"TestHub2026!\"}"))
                .andExpect(status().isOk()).andReturn();
        return new LoginSession(login.getResponse().getCookie("TESTHUB_SESSION"));
    }

    private Context context(LoginSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/access/context").cookie(session.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = json(result).path("data");
        return new Context(data.at("/organizations/0/id").asText());
    }

    private JsonNode report(LoginSession session, String selectedProjectId, LocalDate from, LocalDate to) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/reports/quality").param("projectIds", selectedProjectId)
                        .param("from", from.toString()).param("to", to.toString()).cookie(session.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        return json(result).path("data");
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String id() {
        return UlidCreator.getUlid().toString();
    }

    private record LoginSession(Cookie sessionCookie) {
    }

    private record Context(String organizationId) {
    }
}
