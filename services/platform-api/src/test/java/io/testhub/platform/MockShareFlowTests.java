package io.testhub.platform;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
class MockShareFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM mock_server WHERE name LIKE '分享 Mock 验收 %'").update();
    }

    @Test
    void sharesOnlySafeMockCallingGuideAndRevocationImmediatelyHidesIt() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);

        MvcResult server = mvc.perform(post("/api/v1/projects/{projectId}/mock/servers", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"name\":\"分享 Mock 验收 订单服务\"}"))
                .andExpect(status().isOk()).andReturn();
        String serverId = json(server).at("/data/id").asText();
        String publicKey = json(server).at("/data/publicKey").asText();

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/mock/servers/{serverId}/rules", projectId, serverId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"订单查找","httpMethod":"GET","path":"/orders/{orderId}","priority":50,
                                "requestQuery":{"mode":"preview"},"requestHeaders":{"X-Client":"partner"},
                                "requestBodyMatch":"ANY","responseStatus":200,
                                "responseHeaders":{"X-Internal-Only":"should-not-share"},
                                "responseBody":"{\\"internal-response-only\\":true}"}
                                """))
                .andExpect(status().isOk());

        csrf = csrf(administrator);
        MvcResult share = mvc.perform(post("/api/v1/projects/{projectId}/mock/servers/{serverId}/shares", projectId, serverId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"name\":\"合作方联调说明\",\"expiresInDays\":7}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("合作方联调说明"))
                .andExpect(jsonPath("$.data.expiresAt").exists()).andReturn();
        String shareId = json(share).at("/data/id").asText();
        String shareKey = json(share).at("/data/shareKey").asText();

        mvc.perform(get("/api/v1/projects/{projectId}/mock/servers/{serverId}/shares", projectId, serverId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(shareId));

        mvc.perform(get("/api/v1/mock-shares/{shareKey}", shareKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.shareName").value("合作方联调说明"))
                .andExpect(jsonPath("$.data.serviceName").value("分享 Mock 验收 订单服务"))
                .andExpect(jsonPath("$.data.publicKey").value(publicKey))
                .andExpect(jsonPath("$.data.rules[0].httpMethod").value("GET"))
                .andExpect(jsonPath("$.data.rules[0].path").value("/orders/{orderId}"))
                .andExpect(jsonPath("$.data.rules[0].responseStatus").value(200))
                .andExpect(jsonPath("$.data.rules[0].matchSummary").value(containsString("1 个参数")))
                .andExpect(content().string(not(containsString("internal-response-only"))))
                .andExpect(content().string(not(containsString("X-Internal-Only"))));

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/mock/servers/{serverId}/shares/{shareId}/revoke", projectId, serverId, shareId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revokedAt").exists());
        mvc.perform(get("/api/v1/mock-shares/{shareKey}", shareKey))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("分享链接不存在、已撤销或已过期"));
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
