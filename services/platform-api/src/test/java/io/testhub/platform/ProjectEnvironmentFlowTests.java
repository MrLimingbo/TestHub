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
class ProjectEnvironmentFlowTests {
    private static final String ENVIRONMENT_CODE = "environment-acceptance";
    private static final String SCENARIO_NAME = "环境管理验收 引用保护";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM api_automation_scenario WHERE name = :name")
                .param("name", SCENARIO_NAME).update();
        jdbc.sql("DELETE FROM project_parameter WHERE environment_code = :code")
                .param("code", ENVIRONMENT_CODE).update();
        jdbc.sql("DELETE FROM project_environment WHERE environment_code = :code")
                .param("code", ENVIRONMENT_CODE).update();
    }

    @Test
    void managesProjectScopedEnvironmentAndProtectsExecutionReferences() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);

        Csrf csrf = csrf(administrator);
        MvcResult created = mvc.perform(post("/api/v1/projects/{projectId}/environments", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("""
                                {"name":"环境管理验收","environmentCode":"environment-acceptance",
                                 "baseUrl":"https://staging.example.test/","description":"预发布环境",
                                 "enabled":true,"defaultEnvironment":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.environmentCode").value(ENVIRONMENT_CODE))
                .andExpect(jsonPath("$.data.baseUrl").value("https://staging.example.test"))
                .andExpect(jsonPath("$.data.defaultEnvironment").value(false))
                .andReturn();
        String environmentId = json(created).at("/data/id").asText();

        mvc.perform(get("/api/v1/projects/{projectId}/environments", projectId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '%s')].name".formatted(environmentId))
                        .value(org.hamcrest.Matchers.contains("环境管理验收")));
        mvc.perform(get("/api/v1/projects/{projectId}/parameters", projectId)
                        .param("environment", ENVIRONMENT_CODE).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parameters[0].key").value("BASE_URL"))
                .andExpect(jsonPath("$.data.parameters[0].value").value("https://staging.example.test"));

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/environments/{environmentId}", projectId, environmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("""
                                {"name":"环境管理验收（更新）","baseUrl":"https://uat.example.test",
                                 "description":"更新后的预发布环境","enabled":true,"expectedVersion":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.baseUrl").value("https://uat.example.test"));

        csrf = csrf(administrator);
        MvcResult scenario = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("""
                                {"name":"环境管理验收 引用保护","environmentCode":"environment-acceptance",
                                 "variables":{},"steps":[{"id":"request-1","type":"REQUEST","name":"健康检查",
                                 "config":{"method":"GET","url":"{{BASE_URL}}/health"}}]}
                                """))
                .andExpect(status().isOk()).andReturn();
        String scenarioId = json(scenario).at("/data/id").asText();

        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/environments/{environmentId}", projectId, environmentId)
                        .cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("正在被")));

        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}", projectId, scenarioId)
                        .cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());
        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/environments/{environmentId}", projectId, environmentId)
                        .cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM project_parameter
                WHERE project_id = :projectId AND environment_code = :environmentCode
                """).param("projectId", projectId).param("environmentCode", ENVIRONMENT_CODE)
                .query(Integer.class).single()).isZero();
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

    private Csrf csrf(LoginSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").cookie(session.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = json(result).path("data");
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"), data.path("headerName").asText(),
                data.path("token").asText());
    }

    private String projectId(LoginSession session) throws Exception {
        MvcResult context = mvc.perform(get("/api/v1/access/context").cookie(session.sessionCookie()))
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
