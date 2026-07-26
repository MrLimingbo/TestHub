package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
class PerformanceTestPlanFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM performance_test_thread_group WHERE name LIKE '压测计划验收 %'").update();
        jdbc.sql("DELETE FROM performance_test_plan WHERE name LIKE '压测计划验收 %'").update();
    }

    @Test
    void managesProjectScopedPlanAndThreadGroups() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MvcResult created = mvc.perform(post("/api/v1/projects/{projectId}/performance/plans", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"压测计划验收 订单基准","description":"订单核心接口的容量基线","status":"READY","threadGroups":[
                                {"name":"压测计划验收 下单主链路","targetUrl":"https://perf.example.test/orders/${orderId}","loadModel":"RAMP_UP","threadCount":120,"rampUpSeconds":60,"durationSeconds":600,"targetTps":300,"p95ThresholdMillis":500,"errorThresholdPercent":1.25,"requestMethod":"POST","requestHeaders":{"Content-Type":"application/json","X-Trace":"${traceId}"},"queryParameters":{"source":"${traceId}","page":"1"},"requestBody":"{\\"sku\\":\\"${orderId}\\",\\"quantity\\":${quantity}}","expectedStatusCode":201,"responseContains":"order-${orderId}","dataSetCsv":"orderId,quantity,traceId\\nA-100,1,load-plan","rendezvousUsers":60},
                                {"name":"压测计划验收 查询链路","targetUrl":"https://perf.example.test/orders/query","loadModel":"STEPPED_RAMP","threadCount":80,"rampUpSeconds":0,"stepCount":4,"stepIntervalSeconds":15,"durationSeconds":300,"targetTps":200,"p95ThresholdMillis":300,"errorThresholdPercent":0.5}]}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.threadGroups[0].requestMethod").value("POST"))
                .andExpect(jsonPath("$.data.threadGroups[0].requestHeaders.Content-Type").value("application/json"))
                .andExpect(jsonPath("$.data.threadGroups[0].queryParameters.source").value("${traceId}"))
                .andExpect(jsonPath("$.data.threadGroups[0].requestBody").value("{\"sku\":\"${orderId}\",\"quantity\":${quantity}}"))
                .andExpect(jsonPath("$.data.threadGroups[0].expectedStatusCode").value(201))
                .andExpect(jsonPath("$.data.threadGroups[0].dataSetCsv").value("orderId,quantity,traceId\nA-100,1,load-plan"))
                .andExpect(jsonPath("$.data.threadGroups[0].rendezvousUsers").value(60))
                .andExpect(jsonPath("$.data.threadGroups[1].loadModel").value("STEPPED_RAMP"))
                .andExpect(jsonPath("$.data.threadGroups[1].stepCount").value(4))
                .andExpect(jsonPath("$.data.threadGroups[1].stepIntervalSeconds").value(15))
                .andExpect(jsonPath("$.data.threadGroups.length()").value(2)).andReturn();
        String planId = json(created).at("/data/id").asText();

        mvc.perform(get("/api/v1/projects/{projectId}/performance/plans", projectId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(planId))
                .andExpect(jsonPath("$.data[0].threadGroupCount").value(2));

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/performance/plans/{planId}", projectId, planId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"expectedVersion":1,"plan":{"name":"压测计划验收 订单基准（更新）","description":"更新后的容量基线","status":"DRAFT","threadGroups":[
                                {"name":"压测计划验收 下单主链路（更新）","targetUrl":"https://perf.example.test/orders","loadModel":"RAMP_UP","threadCount":200,"rampUpSeconds":90,"durationSeconds":900,"targetTps":450,"p95ThresholdMillis":550,"errorThresholdPercent":1.5,"requestMethod":"PUT","requestHeaders":{"Content-Type":"application/json"},"requestBody":"{\\"mode\\":\\"updated\\"}","expectedStatusCode":204,"responseContains":null}]}}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.threadGroups.length()").value(1))
                .andExpect(jsonPath("$.data.threadGroups[0].threadCount").value(200))
                .andExpect(jsonPath("$.data.threadGroups[0].requestMethod").value("PUT"));

        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/performance/plans/{planId}", projectId, planId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());
        MvcResult remaining = mvc.perform(get("/api/v1/projects/{projectId}/performance/plans", projectId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        boolean deletedPlanRemains = false;
        for (JsonNode plan : json(remaining).path("data")) {
            if (planId.equals(plan.path("id").asText())) {
                deletedPlanRemains = true;
                break;
            }
        }
        assertFalse(deletedPlanRemains);
    }

    @Test
    void rejectsSensitiveHeadersAndBodiesForSafeMethods() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/performance/plans", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"压测计划验收 安全边界","status":"DRAFT","threadGroups":[
                                {"name":"压测计划验收 安全线程组","targetUrl":"https://perf.example.test/health","loadModel":"STEADY","threadCount":1,"rampUpSeconds":0,"durationSeconds":60,"requestMethod":"GET","requestHeaders":{"Authorization":"Bearer should-not-persist"},"requestBody":"blocked"}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUndeclaredDataSetVariables() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/performance/plans", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"压测计划验收 参数安全边界","status":"DRAFT","threadGroups":[
                                {"name":"压测计划验收 参数线程组","targetUrl":"https://perf.example.test/orders/${orderId}","loadModel":"STEADY","threadCount":1,"rampUpSeconds":0,"durationSeconds":60,"requestMethod":"GET","dataSetCsv":"otherId\\nA-100"}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnsafeQueryParameterConfiguration() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/performance/plans", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"压测计划验收 查询参数边界","status":"DRAFT","threadGroups":[
                                {"name":"压测计划验收 查询参数线程组","targetUrl":"https://perf.example.test/health","loadModel":"STEADY","threadCount":1,"rampUpSeconds":0,"durationSeconds":60,"queryParameters":{"bad name":"value"}}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsRendezvousAboveThreadCount() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/performance/plans", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"压测计划验收 集合点边界","status":"DRAFT","threadGroups":[
                                {"name":"压测计划验收 集合点线程组","targetUrl":"https://perf.example.test/health","loadModel":"STEADY","threadCount":10,"rampUpSeconds":0,"durationSeconds":60,"rendezvousUsers":11}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnsafeSteppedRampConfiguration() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/performance/plans", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"压测计划验收 阶梯边界","status":"DRAFT","threadGroups":[
                                {"name":"压测计划验收 阶梯线程组","targetUrl":"https://perf.example.test/health","loadModel":"STEPPED_RAMP","threadCount":10,"rampUpSeconds":0,"stepCount":4,"stepIntervalSeconds":10,"durationSeconds":60,"targetTps":2}]}
                                """))
                .andExpect(status().isBadRequest());
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

    private record LoginSession(Cookie sessionCookie) {
    }

    private record Csrf(Cookie cookie, String headerName, String token) {
    }
}
