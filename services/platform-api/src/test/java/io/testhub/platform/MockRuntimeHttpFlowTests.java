package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class MockRuntimeHttpFlowTests {
    @LocalServerPort private int port;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcClient jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Cookie session;
    private Cookie csrfCookie;
    private String csrfHeader;
    private String csrfToken;
    private String projectId;
    private String serverId;
    private String publicKey;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM mock_server WHERE name LIKE 'HTTP Mock 验收 %'").update();
    }

    @Test
    void matchesRawFormBodiesAndKeepsFormFieldsOutOfQueryConditions() throws Exception {
        initialize("表单正文");
        for (String method : new String[] { "POST", "PUT", "PATCH", "DELETE" }) {
            createRule(Map.of("httpMethod", method, "path", "/exact", "requestBodyMatch", "EXACT", "requestBody", "mode=preview"));
            assertEquals(200, runtime(method, "/exact", "application/x-www-form-urlencoded", "mode=preview").statusCode(), method);
        }
        createRule(Map.of("httpMethod", "POST", "path", "/query", "requestQuery", Map.of("mode", "preview")));
        assertEquals(404, runtime("POST", "/query", "application/x-www-form-urlencoded", "mode=preview").statusCode());
        assertEquals(200, runtime("POST", "/query?mode=other&mode=preview", "application/x-www-form-urlencoded", "x=1").statusCode());
    }

    @Test
    void enforcesRequestBodyLimitInBytesBeforeDecodingUtf8() throws Exception {
        initialize("字节上限");
        createRule(Map.of("httpMethod", "POST", "path", "/any"));
        assertEquals(200, runtime("POST", "/any", "text/plain", "a".repeat(512 * 1024)).statusCode());
        assertEquals(404, runtime("POST", "/any", "text/plain", "a".repeat(512 * 1024 + 1)).statusCode());
        assertEquals(404, runtime("POST", "/any", "text/plain", "中".repeat(512 * 1024 / 3 + 1)).statusCode());
    }

    @Test
    void matchesUtf8PathsAndPreservesPlusInPath() throws Exception {
        initialize("中文路径");
        createRule(Map.of("httpMethod", "GET", "path", "/订单"));
        createRule(Map.of("httpMethod", "GET", "path", "/a+b"));
        createRule(Map.of("httpMethod", "GET", "path", "/orders"));
        assertEquals(200, runtime("GET", "/%E8%AE%A2%E5%8D%95", "text/plain", "").statusCode());
        assertEquals(200, runtime("GET", "/a+b", "text/plain", "").statusCode());
        assertEquals(404, runtime("GET", "/Orders", "text/plain", "").statusCode());
    }

    @Test
    void requiresExplicitEnabledForBothSwitchesAndPreservesExistingState() throws Exception {
        initialize("开关字段");
        for (String suffix : new String[] { "/enabled", "/smart-example/enabled" }) {
            mvc.perform(post(management() + suffix).cookie(session, csrfCookie).header(csrfHeader, csrfToken)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post(management() + suffix).cookie(session, csrfCookie).header(csrfHeader, csrfToken)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":null}"))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(Boolean.TRUE, jdbc.sql("SELECT enabled FROM mock_server WHERE id = :id").param("id", serverId).query(Boolean.class).single());
    }

    @Test
    void hidesExpiredSharesWithoutChangingTheRuntimeService() throws Exception {
        initialize("过期分享");
        createRule(Map.of("httpMethod", "GET", "path", "/public"));
        JsonNode share = create(management() + "/shares", Map.of("name", "HTTP Mock 验收 过期链接", "expiresInDays", 1));
        jdbc.sql("UPDATE mock_share SET expires_at = CURRENT_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE id = :id")
                .param("id", share.path("id").asText()).update();
        mvc.perform(get("/api/v1/mock-shares/{key}", share.path("shareKey").asText()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
        assertEquals(200, runtime("GET", "/public", "text/plain", "").statusCode());
        assertFalse(share.path("expiresAt").isNull());
    }

    private void initialize(String label) throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode data = json.readTree(csrf.getResponse().getContentAsString()).path("data");
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN")).header(data.path("headerName").asText(), data.path("token").asText())
                        .content("{\"email\":\"admin@testhub.local\",\"password\":\"TestHub2026!\"}"))
                .andExpect(status().isOk()).andReturn();
        session = login.getResponse().getCookie("TESTHUB_SESSION");
        MvcResult freshCsrf = mvc.perform(get("/api/v1/auth/csrf").cookie(session)).andExpect(status().isOk()).andReturn();
        data = json.readTree(freshCsrf.getResponse().getContentAsString()).path("data");
        csrfCookie = freshCsrf.getResponse().getCookie("XSRF-TOKEN");
        csrfHeader = data.path("headerName").asText(); csrfToken = data.path("token").asText();
        MvcResult context = mvc.perform(get("/api/v1/access/context").cookie(session)).andExpect(status().isOk()).andReturn();
        JsonNode access = json.readTree(context.getResponse().getContentAsString()).path("data");
        String organizationId = access.at("/organizations/0/id").asText();
        projectId = access.at("/projects/" + organizationId + "/0/id").asText();
        JsonNode server = create("/api/v1/projects/" + projectId + "/mock/servers", Map.of("name", "HTTP Mock 验收 " + label));
        serverId = server.path("id").asText(); publicKey = server.path("publicKey").asText();
    }

    private String management() { return "/api/v1/projects/" + projectId + "/mock/servers/" + serverId; }
    private JsonNode create(String path, Map<String, ?> payload) throws Exception {
        MvcResult result = mvc.perform(post(path).cookie(session, csrfCookie).header(csrfHeader, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }
    private void createRule(Map<String, ?> extra) throws Exception {
        Map<String, Object> rule = new LinkedHashMap<>(Map.of("name", "HTTP Rule", "httpMethod", "POST", "path", "/",
                "responseBody", "{\"ok\":true}"));
        rule.putAll(extra); create(management() + "/rules", rule);
    }
    private HttpResponse<String> runtime(String method, String path, String contentType, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/mock/" + publicKey + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
