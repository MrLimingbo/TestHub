package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
class ApiAutomationExecutionProfileFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;
    private final List<String> createdEnvironmentIds = new ArrayList<>();

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM api_automation_execution WHERE scenario_name LIKE '自动化配置集验收 %'").update();
        jdbc.sql("DELETE FROM api_automation_scenario WHERE name LIKE '自动化配置集验收 %'").update();
        jdbc.sql("DELETE FROM api_automation_execution_profile WHERE name LIKE '自动化配置集验收 %'").update();
        for (String environmentId : createdEnvironmentIds) {
            jdbc.sql("DELETE FROM project_parameter WHERE environment_code = 'staging' AND project_id = "
                    + "(SELECT project_id FROM project_environment WHERE id = :environmentId)")
                    .param("environmentId", environmentId).update();
            jdbc.sql("DELETE FROM project_environment WHERE id = :environmentId")
                    .param("environmentId", environmentId).update();
        }
        createdEnvironmentIds.clear();
    }

    @Test
    void freezesProfileEnvironmentAndExecutionOverridesWithoutReturningValues() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        ensureStagingEnvironment(administrator, projectId);
        Csrf csrf = csrf(administrator);
        MvcResult profile = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/execution-profiles", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化配置集验收 预发","environmentCode":"staging",
                                "variables":{"baseUrl":"https://staging.example.test","region":"east"}}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.environmentCode").value("staging")).andReturn();
        String profileId = json(profile).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult scenario = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化配置集验收 场景","environmentCode":"test","variables":{"baseUrl":"https://scenario.example.test"},"steps":[
                                {"id":"profile-request","type":"REQUEST","name":"配置请求","config":{"method":"GET","url":"{{baseUrl}}/health/{{region}}"}}]}
                                """))
                .andExpect(status().isOk()).andReturn();
        String scenarioId = json(scenario).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult execution = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}/executions", projectId, scenarioId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"timeoutSeconds":300,"executionProfileId":"%s","overrides":{"region":"west"}}
                                """.formatted(profileId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.executionProfileId").value(profileId))
                .andExpect(jsonPath("$.data.executionProfileVersion").value(1)).andReturn();
        String executionId = json(execution).at("/data/id").asText();

        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/manifest", projectId, executionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.snapshot.environmentCode").value("staging"))
                .andExpect(jsonPath("$.data.snapshot.variableReferences[?(@.name == 'baseUrl')].source").value("EXECUTION_PROFILE"))
                .andExpect(jsonPath("$.data.snapshot.variableReferences[?(@.name == 'region')].source").value("EXECUTION_OVERRIDE"))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("staging.example.test"))));
        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/runtime-snapshot", projectId, executionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.snapshot.environmentCode").value("staging"))
                .andExpect(jsonPath("$.data.snapshot.variables[?(@.name == 'baseUrl')].source").value("EXECUTION_PROFILE"))
                .andExpect(jsonPath("$.data.snapshot.variables[?(@.name == 'region')].source").value("EXECUTION_OVERRIDE"))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("west"))));
    }

    @Test
    void previewsFrozenConfigurationAndEditsProfilesWithoutCreatingAnExecution() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        ensureStagingEnvironment(administrator, projectId);
        Csrf csrf = csrf(administrator);
        MvcResult profile = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/execution-profiles", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化配置集验收 预览","environmentCode":"staging",
                                "variables":{"baseUrl":"https://staging.example.test","region":"east"}}
                                """))
                .andExpect(status().isOk()).andReturn();
        String profileId = json(profile).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult scenario = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化配置集验收 预览场景","environmentCode":"test","variables":{"baseUrl":"https://scenario.example.test"},"steps":[
                                {"id":"preview-request","type":"REQUEST","name":"配置预览请求","config":{"method":"GET","url":"{{baseUrl}}/health/{{region}}"}}]}
                                """))
                .andExpect(status().isOk()).andReturn();
        String scenarioId = json(scenario).at("/data/id").asText();

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}/execution-configuration-preview", projectId, scenarioId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"executionProfileId":"%s","overrides":{"region":"west"}}
                                """.formatted(profileId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.environmentCode").value("staging"))
                .andExpect(jsonPath("$.data.executionProfileId").value(profileId))
                .andExpect(jsonPath("$.data.executionProfileVersion").value(1))
                .andExpect(jsonPath("$.data.variableReferences[?(@.name == 'baseUrl')].source").value("EXECUTION_PROFILE"))
                .andExpect(jsonPath("$.data.variableReferences[?(@.name == 'region')].source").value("EXECUTION_OVERRIDE"))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("west"))));
        Integer executionCount = jdbc.sql("SELECT COUNT(*) FROM api_automation_execution WHERE scenario_id = :scenarioId")
                .param("scenarioId", scenarioId).query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(0, executionCount);

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/api-automation/execution-profiles/{profileId}", projectId, profileId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"expectedVersion":1,"profile":{"name":"自动化配置集验收 预览（更新）","environmentCode":"staging","variables":{"baseUrl":"https://next.example.test"}}}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.name").value("自动化配置集验收 预览（更新）"));
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
    private void ensureStagingEnvironment(LoginSession session, String projectId) throws Exception {
        MvcResult listed = mvc.perform(get("/api/v1/projects/{projectId}/environments", projectId)
                        .cookie(session.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        for (JsonNode environment : json(listed).path("data")) {
            if ("staging".equals(environment.path("environmentCode").asText())) return;
        }
        Csrf csrf = csrf(session);
        MvcResult created = mvc.perform(post("/api/v1/projects/{projectId}/environments", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(session.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化配置集验收环境","environmentCode":"staging",
                                 "baseUrl":"https://staging.example.test","enabled":true,
                                 "defaultEnvironment":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        createdEnvironmentIds.add(json(created).at("/data/id").asText());
    }
    private Csrf csrf(LoginSession session) throws Exception { MvcResult result = mvc.perform(get("/api/v1/auth/csrf").cookie(session.sessionCookie())).andExpect(status().isOk()).andReturn(); JsonNode data = json(result).path("data"); return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"), data.path("headerName").asText(), data.path("token").asText()); }
    private String projectId(LoginSession session) throws Exception { MvcResult context = mvc.perform(get("/api/v1/access/context").cookie(session.sessionCookie())).andExpect(status().isOk()).andReturn(); String organizationId = json(context).at("/data/organizations/0/id").asText(); return json(context).at("/data/projects/" + organizationId + "/0/id").asText(); }
    private JsonNode json(MvcResult result) throws Exception { return objectMapper.readTree(result.getResponse().getContentAsString()); }
    private record LoginSession(Cookie sessionCookie) { }
    private record Csrf(Cookie cookie, String headerName, String token) { }
}
