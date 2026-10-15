package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
class ApiAutomationScenarioReuseFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM api_automation_execution WHERE scenario_name LIKE '自动化复用验收 %'").update();
        jdbc.sql("DELETE FROM api_automation_scenario WHERE name LIKE '自动化复用验收 %'").update();
        jdbc.sql("DELETE FROM api_automation_reusable_step WHERE name LIKE '自动化复用验收 %'").update();
    }

    @Test
    void freezesProjectLocalSourcesAndExpandsOnlyPrimitiveStepsForAnExecution() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);

        MvcResult reusable = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/reusable-steps", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化复用验收 公共健康检查","description":"共享基础请求","steps":[
                                {"id":"shared-health","type":"REQUEST","name":"共享健康检查","config":{"method":"GET","url":"https://api.example.test/health"}}]}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1)).andReturn();
        String reusableStepId = json(reusable).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult child = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化复用验收 子场景","environmentCode":"test","failureStrategy":"STOP","variables":{},"steps":[
                                {"id":"child-request","type":"REQUEST","name":"子场景请求","config":{"method":"GET","url":"https://api.example.test/child"}}]}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1)).andReturn();
        String childId = json(child).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult parent = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化复用验收 父场景","environmentCode":"test","failureStrategy":"STOP","variables":{},"steps":[
                                {"id":"reuse-health","type":"REUSABLE_STEP","name":"公共健康检查","config":{"reusableStepId":"%s","reusableStepVersion":1}},
                                {"id":"call-child","type":"SUB_SCENARIO","name":"调用子场景","config":{"scenarioId":"%s","scenarioVersion":1}}]}
                                """.formatted(reusableStepId, childId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.steps[0].config.reusableStepVersion").value(1))
                .andExpect(jsonPath("$.data.steps[1].config.scenarioVersion").value(1)).andReturn();
        String parentId = json(parent).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult execution = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}/executions", projectId, parentId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"timeoutSeconds\":300}"))
                .andExpect(status().isOk()).andReturn();
        String executionId = json(execution).at("/data/id").asText();

        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/manifest", projectId, executionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.stepCount").value(2))
                .andExpect(jsonPath("$.data.snapshot.steps[0].type").value("REQUEST"))
                .andExpect(jsonPath("$.data.snapshot.steps[1].type").value("REQUEST"))
                .andExpect(jsonPath("$.data.snapshot.steps[0].id").value(org.hamcrest.Matchers.startsWith("ref-")))
                .andExpect(jsonPath("$.data.snapshot.steps[*].type", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("REUSABLE_STEP"))))
                .andExpect(jsonPath("$.data.snapshot.steps[*].type", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("SUB_SCENARIO"))));
    }

    @Test
    void rejectsAChildScenarioThatContainsAnotherReference() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MvcResult reusable = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/reusable-steps", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化复用验收 递归来源","steps":[
                                {"id":"source-request","type":"REQUEST","name":"来源请求","config":{"method":"GET","url":"https://api.example.test/source"}}]}
                                """))
                .andExpect(status().isOk()).andReturn();
        String reusableId = json(reusable).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult nested = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化复用验收 含引用子场景","variables":{},"steps":[
                                {"id":"nested-reuse","type":"REUSABLE_STEP","name":"嵌套引用","config":{"reusableStepId":"%s","reusableStepVersion":1}}]}
                                """.formatted(reusableId)))
                .andExpect(status().isOk()).andReturn();
        String nestedId = json(nested).at("/data/id").asText();

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化复用验收 非法父场景","variables":{},"steps":[
                                {"id":"parent-call","type":"SUB_SCENARIO","name":"调用嵌套子场景","config":{"scenarioId":"%s","scenarioVersion":1}}]}
                                """.formatted(nestedId)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不能嵌套引用")));
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
