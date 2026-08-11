package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.apidebug.ApiDebugTransport;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiDebugFlowTests.DebugTransportConfiguration.class)
class ApiDebugFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;
    @Autowired private RecordingDebugTransport transport;

    @AfterEach
    void cleanDebugHistory() {
        jdbc.sql("DELETE FROM api_debug_history WHERE request_url LIKE 'https://8.8.8.8/%'").update();
        jdbc.sql("DELETE FROM project_parameter WHERE parameter_key IN ('DEBUG_ACCEPTANCE_BASE_URL', 'DEBUG_ACCEPTANCE_TOKEN')").update();
        transport.clear();
    }

    @Test
    void executesPublicHttpRequestStoresRedactedHistoryAndRejectsLoopback() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        Map<String, Object> request = Map.of(
                "method", "POST", "url", "https://8.8.8.8/api/v1/debug-acceptance?token=should-not-persist&view=full",
                "headers", List.of(Map.of("name", "X-Trace", "value", "debug-acceptance")),
                "body", "{\"source\":\"acceptance\"}", "timeoutMs", 1000);

        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/execute", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.statusCode").value(201))
                .andExpect(jsonPath("$.data.body").value("{\"ok\":true}"))
                .andExpect(jsonPath("$.data.responseHeaders[0].name").value("Content-Type"));

        mvc.perform(get("/api/v1/projects/{projectId}/api-debug/history", projectId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].method").value("POST"))
                .andExpect(jsonPath("$.data[0].requestUrl").value("https://8.8.8.8/api/v1/debug-acceptance?token=***&view=full"))
                .andExpect(jsonPath("$.data[0].responseStatus").value(201));

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/execute", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("{\"method\":\"GET\",\"url\":\"http://127.0.0.1:8080/actuator/health\",\"headers\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void savesEncryptedQuickRequestDraftWithoutExecutingTransport() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        Map<String, Object> request = Map.of(
                "name", "订单查询快捷请求",
                "method", "POST", "url", "https://8.8.8.8/api/v1/draft-acceptance?token=should-not-persist",
                "headers", List.of(Map.of("name", "Content-Type", "value", "application/json")),
                "body", "{\"source\":\"saved-draft\"}", "timeoutMs", 1000);

        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/drafts", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("DRAFT"))
                .andExpect(jsonPath("$.data.requestUrl").value("https://8.8.8.8/api/v1/draft-acceptance?token=***"))
                .andExpect(jsonPath("$.data.replayDraft").value(containsString("saved-draft")))
                .andExpect(jsonPath("$.data.replayDraft").value(containsString("订单查询快捷请求")));

        assertNull(transport.latest());
        mvc.perform(get("/api/v1/projects/{projectId}/api-debug/history", projectId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].result").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].responseStatus").doesNotExist());
    }

    @Test
    void resolvesEnvironmentSecretsDynamicValuesAndBearerAuthenticationWithoutPersistingSecrets() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        upsert(administrator, projectId, "parameters", Map.of("environmentCode", "test", "key", "DEBUG_ACCEPTANCE_BASE_URL", "value", "https://8.8.8.8"));
        upsert(administrator, projectId, "secrets", Map.of("environmentCode", "test", "key", "DEBUG_ACCEPTANCE_TOKEN", "value", "server-secret-value"));

        Csrf csrf = csrf(administrator);
        Map<String, Object> request = Map.of(
                "method", "POST", "url", "{{DEBUG_ACCEPTANCE_BASE_URL}}/api/v1/debug-dynamic?access={{DEBUG_ACCEPTANCE_TOKEN}}&at={{$timestamp}}",
                "headers", List.of(Map.of("name", "X-Request-Id", "value", "{{$randomUUID}}")),
                "body", "{\"token\":\"{{DEBUG_ACCEPTANCE_TOKEN}}\"}", "timeoutMs", 1000, "environmentCode", "test",
                "auth", Map.of("type", "BEARER", "token", "{{DEBUG_ACCEPTANCE_TOKEN}}", "placement", "HEADER"));

        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/execute", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.success").value(true));

        RecordedRequest sent = transport.latest();
        assertTrue(sent.url().toString().contains("access=server-secret-value"));
        assertTrue(sent.url().toString().matches(".*at=[0-9]{13}"));
        assertTrue(sent.body().contains("server-secret-value"));
        assertTrue(sent.headers().stream().anyMatch(header -> header.name().equals("Authorization") && header.value().equals("Bearer server-secret-value")));
        assertTrue(sent.headers().stream().anyMatch(header -> header.name().equals("X-Request-Id") && header.value().matches("[0-9a-f-]{36}")));

        mvc.perform(get("/api/v1/projects/{projectId}/api-debug/history", projectId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].requestUrl").value(org.hamcrest.Matchers.containsString("access=***")))
                .andExpect(jsonPath("$.data[0].requestUrl").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("server-secret-value"))));
    }

    @Test
    void constructsGraphqlAndSoapProtocolBodiesWithRequiredHeaders() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        Map<String, Object> graphQl = Map.of(
                "method", "POST", "url", "https://8.8.8.8/api/v1/graphql-acceptance", "headers", List.of(), "body", "", "timeoutMs", 1000,
                "protocol", Map.of("type", "GRAPHQL", "query", "query FindOrder($id: ID!) { order(id: $id) { id } }",
                        "variables", "{\"id\":\"order-42\"}", "operationName", "FindOrder"));
        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/execute", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content(objectMapper.writeValueAsString(graphQl)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.success").value(true));
        RecordedRequest graphQlRequest = transport.latest();
        assertEquals("POST", graphQlRequest.method());
        assertEquals("FindOrder", objectMapper.readTree(graphQlRequest.body()).path("operationName").asText());
        assertEquals("order-42", objectMapper.readTree(graphQlRequest.body()).at("/variables/id").asText());
        assertTrue(graphQlRequest.headers().stream().anyMatch(header -> header.name().equalsIgnoreCase("Content-Type") && header.value().equals("application/json")));

        csrf = csrf(administrator);
        Map<String, Object> soap = Map.of(
                "method", "POST", "url", "https://8.8.8.8/api/v1/soap-acceptance", "headers", List.of(Map.of("name", "Content-Type", "value", "application/json")), "body", "", "timeoutMs", 1000,
                "protocol", Map.of("type", "SOAP", "envelope", "<soapenv:Envelope><soapenv:Body><Ping/></soapenv:Body></soapenv:Envelope>", "soapAction", "urn:Ping"));
        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/execute", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content(objectMapper.writeValueAsString(soap)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.success").value(true));
        RecordedRequest soapRequest = transport.latest();
        assertTrue(soapRequest.body().contains("<soapenv:Envelope>"));
        assertTrue(soapRequest.headers().stream().anyMatch(header -> header.name().equalsIgnoreCase("Content-Type") && header.value().contains("text/xml")));
        assertTrue(soapRequest.headers().stream().anyMatch(header -> header.name().equals("SOAPAction") && header.value().equals("urn:Ping")));
    }

    private void upsert(LoginSession session, String projectId, String type, Map<String, String> value) throws Exception {
        Csrf csrf = csrf(session);
        mvc.perform(post("/api/v1/projects/{projectId}/{type}", projectId, type).contentType(MediaType.APPLICATION_JSON)
                        .cookie(session.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(value)))
                .andExpect(status().isNoContent());
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

    @TestConfiguration
    static class DebugTransportConfiguration {
        @Bean
        @Primary
        RecordingDebugTransport acceptanceTransport() {
            return new RecordingDebugTransport();
        }
    }

    static final class RecordingDebugTransport implements ApiDebugTransport {
        private final AtomicReference<RecordedRequest> latest = new AtomicReference<>();

        @Override
        public TransportResponse execute(String method, java.net.URI url, List<Header> headers, String body, int timeoutMs) {
            latest.set(new RecordedRequest(method, url, List.copyOf(headers), body));
            return new TransportResponse(201, "Created", List.of(new Header("Content-Type", "application/json")), "{\"ok\":true}", false);
        }

        RecordedRequest latest() {
            return latest.get();
        }

        void clear() {
            latest.set(null);
        }
    }

    private record RecordedRequest(String method, java.net.URI url, List<ApiDebugTransport.Header> headers, String body) {
    }
}
