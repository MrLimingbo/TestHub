package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import jakarta.servlet.http.Cookie;
import java.util.List;
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
class FunctionalExecutionFlowTests {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void cleanExecutionAssets() {
        jdbc.sql("DELETE FROM functional_test_run WHERE name LIKE '执行验收批次%'").update();
        jdbc.sql("DELETE FROM functional_test_plan WHERE plan_code LIKE 'EXEC_%'").update();
        jdbc.sql("DELETE FROM functional_case WHERE case_code LIKE 'EXEC_%'").update();
        jdbc.sql("DELETE FROM functional_catalog WHERE name = '执行验收目录'").update();
    }

    @Test
    void planRunAndStepReportFormTraceableExecutionChain() throws Exception {
        LoginSession administrator = login("admin@testhub.local");
        String projectId = projectId(administrator);
        String organizationId = jdbc.sql("SELECT organization_id FROM project WHERE id = :id").param("id", projectId).query(String.class).single();
        String userId = jdbc.sql("SELECT id FROM user_account WHERE email = 'admin@testhub.local'").query(String.class).single();
        String catalogId = UlidCreator.getUlid().toString();
        String caseId = UlidCreator.getUlid().toString();
        jdbc.sql("INSERT INTO functional_catalog (id, organization_id, project_id, name, created_by) VALUES (:id, :org, :project, :name, :user)")
                .param("id", catalogId).param("org", organizationId).param("project", projectId).param("name", "执行验收目录").param("user", userId).update();
        jdbc.sql("""
                INSERT INTO functional_case
                    (id, organization_id, project_id, catalog_id, case_code, title, priority, status,
                     precondition, steps, expected_result, created_by, updated_by)
                VALUES (:id, :org, :project, :catalog, 'EXEC_CASE_001', '执行链路验收用例', 'P1', 'READY',
                        '', '打开页面\\n提交表单', '展示成功结果', :user, :user)
                """).param("id", caseId).param("org", organizationId).param("project", projectId).param("catalog", catalogId)
                .param("user", userId).update();

        Csrf csrf = csrf(administrator);
        MvcResult plan = mvc.perform(post("/api/v1/projects/{projectId}/functional/plans", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("{\"planCode\":\"EXEC_PLAN_001\",\"name\":\"执行验收计划\",\"status\":\"READY\",\"description\":\"验证执行闭环\",\"caseIds\":[\"" + caseId + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.caseCount").value(1)).andReturn();
        String planId = json(plan).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult run = mvc.perform(post("/api/v1/projects/{projectId}/functional/plans/{planId}/runs", projectId, planId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"name\":\"执行验收批次-001\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(1)).andReturn();
        String runId = json(run).at("/data/id").asText();
        MvcResult runCases = mvc.perform(get("/api/v1/projects/{projectId}/functional/runs/{runId}/cases", projectId, runId)
                        .cookie(administrator.sessionCookie())).andExpect(status().isOk()).andReturn();
        String runCaseId = json(runCases).at("/data/0/id").asText();
        MvcResult steps = mvc.perform(get("/api/v1/projects/{projectId}/functional/run-cases/{runCaseId}/steps", projectId, runCaseId)
                        .cookie(administrator.sessionCookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2)).andReturn();
        String stepId = json(steps).at("/data/0/id").asText();

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/functional/run-steps/{stepId}", projectId, stepId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"status\":\"PASSED\",\"actualResult\":\"页面打开正常\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PASSED"));
        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/functional/run-cases/{runCaseId}", projectId, runCaseId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"status\":\"PASSED\",\"note\":\"验收通过\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PASSED"));
        mvc.perform(get("/api/v1/projects/{projectId}/functional/runs/{runId}", projectId, runId)
                        .cookie(administrator.sessionCookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.passedCount").value(1))
                .andExpect(jsonPath("$.data.notRunCount").value(0));

        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/functional/plans/{planId}", projectId, planId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/projects/{projectId}/functional/plans/{planId}", projectId, planId)
                        .cookie(administrator.sessionCookie())).andExpect(status().isNotFound());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM functional_test_run WHERE id = :id")
                .param("id", runId).query(Integer.class).single());
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
