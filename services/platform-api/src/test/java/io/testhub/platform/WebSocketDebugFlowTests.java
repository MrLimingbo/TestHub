package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.apidebug.ApiDebugTransport;
import io.testhub.platform.apidebug.WebSocketDebugClient;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(WebSocketDebugFlowTests.WebSocketClientConfiguration.class)
class WebSocketDebugFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RecordingWebSocketClient client;

    @Test
    void connectsSendsPollsMessagesClosesAndRejectsLoopbackTargets() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MvcResult connection = mvc.perform(post("/api/v1/projects/{projectId}/api-debug/websocket/connect", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"url":"wss://8.8.8.8/socket","headers":[{"name":"X-TestHub-Debug","value":"acceptance"}]}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.target").value("wss://8.8.8.8/socket")).andReturn();
        String sessionId = json(connection).at("/data/id").asText();
        assertEquals(URI.create("wss://8.8.8.8/socket"), client.latestUrl());

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/websocket/sessions/{sessionId}/messages", projectId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"message\":\"hello websocket\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.messageCount").value(3));

        mvc.perform(get("/api/v1/projects/{projectId}/api-debug/websocket/sessions/{sessionId}/messages", projectId, sessionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].direction").value("IN"))
                .andExpect(jsonPath("$.data[0].payload").value("connected"))
                .andExpect(jsonPath("$.data[1].direction").value("OUT"))
                .andExpect(jsonPath("$.data[1].payload").value("hello websocket"))
                .andExpect(jsonPath("$.data[2].direction").value("IN"))
                .andExpect(jsonPath("$.data[2].payload").value("echo:hello websocket"));

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/websocket/sessions/{sessionId}/close", projectId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"));

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/websocket/connect", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"url\":\"ws://127.0.0.1:8080/socket\",\"headers\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void appliesHandshakeAuthSendsSavedSubscriptionsAndReconnectsAfterRemoteClose() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MvcResult connection = mvc.perform(post("/api/v1/projects/{projectId}/api-debug/websocket/connect", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"url":"wss://8.8.8.8/socket","headers":[{"name":"X-Trace","value":"handshake"}],
                                "auth":{"type":"BEARER","token":"session-token"},"subprotocols":["graphql-transport-ws"],
                                "subscriptions":["{\\"type\\":\\"subscribe\\",\\"topic\\":\\"orders\\"}"],
                                "reconnect":{"enabled":true,"maxAttempts":1,"delayMs":100}}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.autoReconnect").value(true))
                .andExpect(jsonPath("$.data.maxReconnectAttempts").value(1)).andReturn();
        String sessionId = json(connection).at("/data/id").asText();
        assertEquals(List.of("graphql-transport-ws"), client.latestSubprotocols());
        assertTrue(client.latestHeaders().stream().anyMatch(header -> header.name().equals("Authorization") && header.value().equals("Bearer session-token")));
        assertTrue(client.latestSent().contains("{\"type\":\"subscribe\",\"topic\":\"orders\"}"));

        int connectionsBeforeClose = client.connectCount();
        client.closeLatestFromTarget();
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (client.connectCount() <= connectionsBeforeClose && System.nanoTime() < deadline) Thread.sleep(25);
        assertEquals(connectionsBeforeClose + 1, client.connectCount());

        mvc.perform(get("/api/v1/projects/{projectId}/api-debug/websocket/sessions/{sessionId}", projectId, sessionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.reconnectAttempts").value(1));
        assertTrue(client.latestSent().contains("{\"type\":\"subscribe\",\"topic\":\"orders\"}"));
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

    private record LoginSession(Cookie sessionCookie) {
    }

    private record Csrf(Cookie cookie, String headerName, String token) {
    }

    @TestConfiguration
    static class WebSocketClientConfiguration {
        @Bean
        @Primary
        RecordingWebSocketClient acceptanceWebSocketClient() {
            return new RecordingWebSocketClient();
        }
    }

    static final class RecordingWebSocketClient implements WebSocketDebugClient {
        private final AtomicReference<URI> latestUrl = new AtomicReference<>();
        private final AtomicReference<List<ApiDebugTransport.Header>> latestHeaders = new AtomicReference<>(List.of());
        private final AtomicReference<List<String>> latestSubprotocols = new AtomicReference<>(List.of());
        private final AtomicReference<Listener> latestListener = new AtomicReference<>();
        private final AtomicReference<List<String>> latestSent = new AtomicReference<>(List.of());
        private final AtomicInteger connectCount = new AtomicInteger();

        @Override
        public Connection connect(URI url, List<ApiDebugTransport.Header> headers, List<String> subprotocols, Listener listener) {
            latestUrl.set(url);
            latestHeaders.set(List.copyOf(headers));
            latestSubprotocols.set(List.copyOf(subprotocols));
            latestListener.set(listener);
            latestSent.set(new java.util.concurrent.CopyOnWriteArrayList<>());
            connectCount.incrementAndGet();
            listener.onText("connected");
            return new Connection() {
                @Override
                public void sendText(String payload) {
                    latestSent.get().add(payload);
                    listener.onText("echo:" + payload);
                }

                @Override
                public void close(int statusCode, String reason) {
                    listener.onClosed(statusCode, reason);
                }
            };
        }

        URI latestUrl() {
            return latestUrl.get();
        }

        List<ApiDebugTransport.Header> latestHeaders() { return latestHeaders.get(); }

        List<String> latestSubprotocols() { return latestSubprotocols.get(); }

        List<String> latestSent() { return List.copyOf(latestSent.get()); }

        int connectCount() { return connectCount.get(); }

        void closeLatestFromTarget() {
            latestListener.get().onClosed(1011, "simulated failure");
        }
    }
}
