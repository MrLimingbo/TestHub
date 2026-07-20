package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
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
class MockServiceFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM mock_server WHERE name LIKE '验收 Mock %'").update();
    }

    @Test
    void createsProjectScopedMockAndServesItsManualRuleWithoutAuthentication() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);

        MvcResult created = mvc.perform(post("/api/v1/projects/{projectId}/mock/servers", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"name\":\"验收 Mock 订单服务\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true)).andReturn();
        String serverId = json(created).at("/data/id").asText();
        String publicKey = json(created).at("/data/publicKey").asText();

        Map<String, Object> rule = Map.of("name", "创建订单默认响应", "httpMethod", "POST", "path", "/orders", "priority", 0,
                "responseStatus", 201,
                "responseHeaders", Map.of("Content-Type", "application/json; charset=utf-8", "X-Mock-Source", "acceptance"),
                "responseBody", "{\"id\":\"mock-order-default\",\"status\":\"created\"}");
        mvc.perform(post("/api/v1/projects/{projectId}/mock/servers/{serverId}/rules", projectId, serverId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content(objectMapper.writeValueAsString(rule)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.httpMethod").value("POST"))
                .andExpect(jsonPath("$.data.path").value("/orders")).andExpect(jsonPath("$.data.priority").value(0));

        Map<String, Object> conditionalRule = Map.of("name", "预览订单", "httpMethod", "POST", "path", "/orders", "priority", 100,
                "requestQuery", Map.of("mode", "preview"), "requestHeaders", Map.of("X-Client", "browser"),
                "requestBodyMatch", "JSON_SUBSET", "requestBody", "{\"status\":\"draft\",\"payload\":{\"kind\":\"order\"}}",
                "responseStatus", 202, "responseBody", "{\"id\":\"mock-order-preview\",\"status\":\"queued\"}");
        mvc.perform(post("/api/v1/projects/{projectId}/mock/servers/{serverId}/rules", projectId, serverId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content(objectMapper.writeValueAsString(conditionalRule)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.priority").value(100))
                .andExpect(jsonPath("$.data.requestQuery.mode").value("preview"))
                .andExpect(jsonPath("$.data.requestHeaders.x-client").value("browser"))
                .andExpect(jsonPath("$.data.requestBodyMatch").value("JSON_SUBSET"));

        Map<String, Object> exactBodyRule = Map.of("name", "严格订单", "httpMethod", "POST", "path", "/orders", "priority", 200,
                "requestBodyMatch", "EXACT", "requestBody", "order=strict", "responseStatus", 200,
                "responseBody", "{\"id\":\"mock-order-strict\",\"status\":\"accepted\"}");
        mvc.perform(post("/api/v1/projects/{projectId}/mock/servers/{serverId}/rules", projectId, serverId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content(objectMapper.writeValueAsString(exactBodyRule)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.priority").value(200))
                .andExpect(jsonPath("$.data.requestBodyMatch").value("EXACT"));

        mvc.perform(post("/api/v1/mock/{publicKey}/orders?mode=preview", publicKey).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Client", "browser").content("{\"status\":\"draft\",\"payload\":{\"kind\":\"order\",\"source\":\"web\"}}"))
                .andExpect(status().isAccepted()).andExpect(content().json("{\"id\":\"mock-order-preview\",\"status\":\"queued\"}"));
        mvc.perform(post("/api/v1/mock/{publicKey}/orders", publicKey).contentType(MediaType.TEXT_PLAIN).content("order=strict"))
                .andExpect(status().isOk()).andExpect(content().json("{\"id\":\"mock-order-strict\",\"status\":\"accepted\"}"));
        mvc.perform(post("/api/v1/mock/{publicKey}/orders", publicKey).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andExpect(header().string("X-Mock-Source", "acceptance"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"id\":\"mock-order-default\",\"status\":\"created\"}"));
        mvc.perform(get("/api/v1/mock/{publicKey}/orders", publicKey))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MOCK_RULE_NOT_FOUND"));

        mvc.perform(post("/api/v1/projects/{projectId}/mock/servers/{serverId}/enabled", projectId, serverId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        mvc.perform(post("/api/v1/mock/{publicKey}/orders", publicKey).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MOCK_RULE_NOT_FOUND"));
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

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record LoginSession(Cookie sessionCookie) { }
    private record Csrf(Cookie cookie, String headerName, String token) { }
}
