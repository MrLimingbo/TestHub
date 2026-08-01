package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class ProjectVersionFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void cleanVersions() {
        jdbc.sql("DELETE FROM project_version WHERE version_number LIKE 'ACCEPT_VERSION_%'").update();
    }

    @Test
    void projectMemberCanMaintainMultipleProjectVersions() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String projectName = jdbc.sql("SELECT name FROM project WHERE id = :projectId")
                .param("projectId", projectId).query(String.class).single();
        Csrf csrf = csrf(administrator);

        MvcResult created = mvc.perform(post("/api/v1/projects/{projectId}/versions", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("""
                                {
                                  "versionNumber": "ACCEPT_VERSION_1.0.0",
                                  "buildNumbers": ["build-101", "build-102"],
                                  "status": "UNSTARTED",
                                  "plannedReleaseDate": "2026-08-15",
                                  "description": "首个验收版本"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayId").isNumber())
                .andExpect(jsonPath("$.data.projectId").value(projectId))
                .andExpect(jsonPath("$.data.projectName").value(projectName))
                .andExpect(jsonPath("$.data.versionNumber").value("ACCEPT_VERSION_1.0.0"))
                .andExpect(jsonPath("$.data.buildNumbers[0]").value("build-101"))
                .andExpect(jsonPath("$.data.status").value("UNSTARTED"))
                .andExpect(jsonPath("$.data.actualReleaseDate").doesNotExist())
                .andReturn();
        String versionId = json(created).at("/data/id").asText();

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/versions", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("{\"versionNumber\":\"ACCEPT_VERSION_1.0.0\",\"description\":\"重复版本\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("当前项目已存在相同版本号"));

        csrf = csrf(administrator);
        MvcResult second = mvc.perform(post("/api/v1/projects/{projectId}/versions", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("{\"versionNumber\":\"ACCEPT_VERSION_2.0.0\",\"description\":\"同项目第二个版本\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId").value(projectId))
                .andReturn();
        String secondVersionId = json(second).at("/data/id").asText();

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/versions/{versionId}", projectId, versionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("""
                                {
                                  "versionNumber": "ACCEPT_VERSION_1.0.1",
                                  "buildNumbers": ["build-103"],
                                  "status": "UNSTARTED",
                                  "plannedReleaseDate": "2026-08-20",
                                  "description": "修订后的验收版本"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNumber").value("ACCEPT_VERSION_1.0.1"))
                .andExpect(jsonPath("$.data.buildNumbers.length()").value(1))
                .andExpect(jsonPath("$.data.description").value("修订后的验收版本"));

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/versions/{versionId}/status", projectId, versionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("{\"status\":\"RELEASED\",\"actualReleaseDate\":\"2026-08-19\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RELEASED"))
                .andExpect(jsonPath("$.data.actualReleaseDate").value("2026-08-19"));

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/versions/{versionId}/status", projectId, versionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("{\"status\":\"STOPPED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("STOPPED"))
                .andExpect(jsonPath("$.data.actualReleaseDate").value("2026-08-19"));

        mvc.perform(get("/api/v1/projects/{projectId}/versions", projectId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + versionId + "')].projectName").value(projectName))
                .andExpect(jsonPath("$.data[?(@.id == '" + versionId + "')].status").value("STOPPED"))
                .andExpect(jsonPath("$.data[?(@.id == '" + secondVersionId + "')].versionNumber")
                        .value("ACCEPT_VERSION_2.0.0"));

        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/versions/{versionId}", projectId, versionId)
                        .cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/projects/{projectId}/versions", projectId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + versionId + "')]").isEmpty());
    }

    private LoginSession login() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode data = json(csrf).path("data");
        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(data.path("headerName").asText(), data.path("token").asText())
                        .content("{\"email\":\"admin@testhub.local\",\"password\":\"TestHub2026!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return new LoginSession(login.getResponse().getCookie("TESTHUB_SESSION"));
    }

    private Csrf csrf(LoginSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").cookie(session.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = json(result).path("data");
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"),
                data.path("headerName").asText(), data.path("token").asText());
    }

    private String projectId(LoginSession session) throws Exception {
        MvcResult context = mvc.perform(get("/api/v1/access/context")
                        .cookie(session.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        String organizationId = json(context).at("/data/organizations/0/id").asText();
        return json(context).at("/data/projects/" + organizationId + "/0/id").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record LoginSession(Cookie sessionCookie) { }
    private record Csrf(Cookie cookie, String headerName, String token) { }
}
