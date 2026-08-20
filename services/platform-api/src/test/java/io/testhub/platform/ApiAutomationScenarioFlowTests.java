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
class ApiAutomationScenarioFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM api_automation_scenario WHERE name LIKE '自动化验收 %'").update();
    }

    @Test
    void createsVersionedScenarioAndRejectsStaleOrUnsupportedSteps() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        String scenario = """
                {"name":"自动化验收 订单回归","description":"订单主链路","environmentCode":"test",
                "failureStrategy":"STOP","variables":{"baseUrl":"https://api.example.test"},"steps":[
                {"id":"request-login","type":"REQUEST","name":"获取订单","config":{"method":"GET","url":"{{baseUrl}}/orders/OD-1"}},
                {"id":"extract-id","type":"EXTRACT","name":"提取订单状态","config":{"source":"LAST_STATUS_CODE","variable":"orderStatus"}},
                {"id":"wait-stable","type":"WAIT","name":"等待稳定","config":{"delayMillis":150}}]}
                """;
        MvcResult created = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content(scenario))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.steps.length()").value(3)).andReturn();
        String scenarioId = json(created).at("/data/id").asText();

        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/scenarios", projectId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(scenarioId))
                .andExpect(jsonPath("$.data[0].stepCount").value(3));
        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}", projectId, scenarioId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.variables.baseUrl").value("https://api.example.test"));

        csrf = csrf(administrator);
        String updated = """
                {"expectedVersion":1,"scenario":{"name":"自动化验收 订单回归","description":"订单主链路 v2",
                "environmentCode":"test","failureStrategy":"CONTINUE","variables":{"baseUrl":"https://api.example.test"},"steps":[
                {"id":"request-login","type":"REQUEST","name":"获取订单","config":{"method":"GET","url":"{{baseUrl}}/orders/OD-1"}},
                {"id":"assert-status","type":"ASSERT","name":"断言订单状态","config":{"source":"LAST_STATUS_CODE","expectedStatus":200}}]}}
                """;
        mvc.perform(put("/api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}", projectId, scenarioId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content(updated))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.failureStrategy").value("CONTINUE"));
        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}/revisions", projectId, scenarioId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].version").value(2)).andExpect(jsonPath("$.data[1].version").value(1));

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}", projectId, scenarioId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content(updated))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("当前版本 v2")));

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化验收 非法步骤","steps":[{"id":"script-1","type":"SCRIPT","name":"脚本","config":{"source":"return true"}}],"variables":{}}
                                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不支持的步骤类型")));

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化验收 小数断言","steps":[
                                {"id":"request-1","type":"REQUEST","name":"请求","config":{"method":"GET","url":"https://api.example.test/orders"}},
                                {"id":"assert-1","type":"ASSERT","name":"断言","config":{"source":"LAST_STATUS_CODE","expectedStatus":200.5}}],"variables":{}}
                                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("断言期望状态码不合法")));

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化验收 非法条件循环","steps":[
                                {"id":"condition-1","type":"CONDITION","name":"条件","config":{"source":"LAST_STATUS_CODE","operator":"EQUALS","expectedStatus":200}},
                                {"id":"request-1","type":"REQUEST","name":"请求","config":{"method":"GET","url":"https://api.example.test/orders"}},
                                {"id":"loop-1","type":"LOOP","name":"循环","config":{"totalRuns":2}}],"variables":{}}
                                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("条件步骤必须位于已有请求之后")));

        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}", projectId, scenarioId)
                        .cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}", projectId, scenarioId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("SELECT COUNT(*) FROM api_automation_scenario_revision WHERE scenario_id = :scenarioId")
                .param("scenarioId", scenarioId).query(Integer.class).single()).isZero();
    }

    @Test
    void acceptsOnlyTheBoundedFlowControlGrammar() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化验收 受限流程控制","variables":{"baseUrl":"https://api.example.test"},"steps":[
                                {"id":"request-first","type":"REQUEST","name":"首个请求","config":{"method":"GET","url":"{{baseUrl}}/first"}},
                                {"id":"condition-next","type":"CONDITION","name":"状态条件","config":{"source":"LAST_STATUS_CODE","operator":"EQUALS","expectedStatus":200}},
                                {"id":"request-conditional","type":"REQUEST","name":"条件请求","config":{"method":"GET","url":"{{baseUrl}}/conditional"}},
                                {"id":"request-repeat","type":"REQUEST","name":"循环请求","config":{"method":"GET","url":"{{baseUrl}}/repeat"}},
                                {"id":"loop-repeat","type":"LOOP","name":"有界循环","config":{"totalRuns":3}},
                                {"id":"wait-stable","type":"WAIT","name":"等待稳定","config":{"delayMillis":100}}]}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.steps.length()").value(6))
                .andExpect(jsonPath("$.data.steps[1].config.operator").value("EQUALS"))
                .andExpect(jsonPath("$.data.steps[4].config.totalRuns").value(3));
    }

    @Test
    void acceptsOnlyIndependentBoundedParallelRequests() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化验收 并行请求组","variables":{"baseUrl":"https://api.example.test"},"steps":[
                                {"id":"parallel-status","type":"PARALLEL","name":"并发读取状态","config":{"requests":[
                                {"method":"GET","url":"{{baseUrl}}/health","maxAttempts":2},
                                {"method":"GET","url":"{{baseUrl}}/version"}]}},
                                {"id":"request-after","type":"REQUEST","name":"后续单请求","config":{"method":"GET","url":"{{baseUrl}}/after"}}]}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.steps[0].type").value("PARALLEL"))
                .andExpect(jsonPath("$.data.steps[0].config.requests.length()").value(2))
                .andExpect(jsonPath("$.data.steps[1].type").value("REQUEST"));

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化验收 并行请求脚本冲突","preRequestScript":"variables.trace = 'unsafe';","steps":[
                                {"id":"parallel-status","type":"PARALLEL","name":"并发读取状态","config":{"requests":[
                                {"method":"GET","url":"https://api.example.test/health"},
                                {"method":"GET","url":"https://api.example.test/version"}]}}]}
                                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("并行请求组不支持场景前后置脚本")));

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化验收 并行请求断言冲突","steps":[
                                {"id":"parallel-status","type":"PARALLEL","name":"并发读取状态","config":{"requests":[
                                {"method":"GET","url":"https://api.example.test/health"},
                                {"method":"GET","url":"https://api.example.test/version"}]}},
                                {"id":"assert-status","type":"ASSERT","name":"不允许的断言","config":{"source":"LAST_STATUS_CODE","expectedStatus":200}}]}
                                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("不能直接作用于并行请求组")));
    }

    @Test
    void storesScriptSourcesWithoutExecutingThemInTheControlPlane() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化验收 隔离脚本","variables":{"baseUrl":"https://api.example.test"},
                                "preRequestScript":"variables.trace = 'safe-pre';",
                                "postResponseScript":"if (response.status !== 200) { throw new Error('status'); }",
                                "steps":[{"id":"request-1","type":"REQUEST","name":"请求","config":{"method":"GET","url":"{{baseUrl}}/orders"}}]}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.preRequestScript").value("variables.trace = 'safe-pre';"))
                .andExpect(jsonPath("$.data.postResponseScript").value(org.hamcrest.Matchers.containsString("response.status")));

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化验收 非法脚本","preRequestScript":"process.exit(1)",
                                "steps":[{"id":"request-1","type":"REQUEST","name":"请求","config":{"method":"GET","url":"https://api.example.test/orders"}}]}
                                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不允许的运行时能力")));

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化验收 pm 兼容脚本","preRequestScript":"pm.environment.set('trace', pm.variables.get('trace') || 'first');",
                                "postResponseScript":"pm.test('status', function () { pm.response.to.have.status(200); });",
                                "steps":[{"id":"request-1","type":"REQUEST","name":"请求","config":{"method":"GET","url":"https://api.example.test/orders"}}]}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.preRequestScript").value(org.hamcrest.Matchers.containsString("pm.environment")));

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"自动化验收 非法 pm 脚本","preRequestScript":"pm.sendRequest('https://api.example.test/orders')",
                                "steps":[{"id":"request-1","type":"REQUEST","name":"请求","config":{"method":"GET","url":"https://api.example.test/orders"}}]}
                                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不支持的 pm API")));
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
