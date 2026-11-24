package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.UserAccountRepository;
import io.testhub.platform.security.SecretCipher;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "testhub.bootstrap.demo-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("desktop")
@DirtiesContext
class ModelProviderFlowTests {
    private static final String ROOT = "/api/v1/model-providers";
    private static final String KEY = "fixture-" + UUID.randomUUID();
    private static final AtomicInteger CATALOG_REQUESTS = new AtomicInteger();
    private static final AtomicInteger FAILING_REQUESTS = new AtomicInteger();
    private static final AtomicInteger REDIRECTED_REQUESTS = new AtomicInteger();
    private static final AtomicReference<String> LAST_INFERENCE_BODY = new AtomicReference<>();
    private static HttpServer upstream;

    @TempDir static Path directory;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository users;
    @Autowired JdbcClient jdbc;
    @Autowired SecretCipher cipher;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/models", exchange -> {
            if (!authorized(exchange)) return;
            if (CATALOG_REQUESTS.incrementAndGet() == 1) respond(exchange, 503, "{}");
            else respond(exchange, 200, "{\"data\":[{\"id\":\"qa-model\"},{\"id\":\"qa-model\"}]}");
        });
        upstream.createContext("/v1/chat/completions", exchange -> {
            if (!authorized(exchange)) return;
            LAST_INFERENCE_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"OK " + KEY + "\"}}]}");
        });
        upstream.createContext("/v1/responses", exchange -> {
            if (!authorized(exchange)) return;
            LAST_INFERENCE_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"output":[{"type":"reasoning"},{"type":"message","content":[{"type":"output_text","text":"Responses OK"}]}]}
                    """);
        });
        upstream.createContext("/error/models", exchange -> {
            FAILING_REQUESTS.incrementAndGet();
            respond(exchange, 503, "{}");
        });
        upstream.createContext("/redirect/models", exchange -> {
            exchange.getResponseHeaders().set("Location", origin() + "/unexpected");
            respond(exchange, 302, "{}");
        });
        upstream.createContext("/unexpected", exchange -> {
            REDIRECTED_REQUESTS.incrementAndGet();
            respond(exchange, 200, "{\"data\":[]}");
        });
        upstream.start();
        properties.add("testhub.desktop.allowed-origins", ModelProviderFlowTests::origin);
        properties.add("testhub.desktop.data-directory", directory::toString);
        properties.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
        properties.add("spring.datasource.password", () -> "isolated-model-fixture");
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) upstream.stop(0);
    }

    @Test
    void managesEncryptedConfigurationAndSendsRealProtocolRequests() throws Exception {
        var admin = principal("admin");
        var tester = principal("tester");
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT).with(user(tester))).andExpect(status().isForbidden());
        String input = json.writeValueAsString(input("CHAT_COMPLETIONS", KEY, false, 1, origin() + "/v1/"));
        mvc.perform(post(ROOT).with(user(tester)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isForbidden());
        mvc.perform(post(ROOT).with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isForbidden());
        JsonNode created = data(mvc.perform(post(ROOT).with(user(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isOk()));
        String id = created.path("id").asText();
        assertTrue(created.path("apiKeyConfigured").asBoolean());
        assertEquals(origin() + "/v1", created.path("apiBaseUrl").asText());
        JsonNode model = created.path("models").path(0);
        assertTrue(model.path("acceptsImages").asBoolean());
        assertEquals(65_536, model.path("contextWindow").asInt());
        assertEquals("custom-low", model.path("reasoningOptions").path(0).path("apiValue").asText());
        String stored = jdbc.sql("SELECT api_key_ciphertext FROM model_provider WHERE id = :id").param("id", id)
                .query(String.class).single();
        assertNotEquals(KEY, stored);
        assertEquals(KEY, cipher.decrypt(stored));
        assertTrue(data(mvc.perform(get(ROOT).with(user(admin))).andExpect(status().isOk())).isArray());

        String discovery = json.writeValueAsString(Map.of("providerId", id, "version", 1,
                "apiBaseUrl", origin() + "/v1", "retryPolicy", "TRANSIENT_ERRORS"));
        JsonNode models = data(mvc.perform(post(ROOT + "/discover").with(user(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(discovery)).andExpect(status().isOk()));
        assertEquals(1, models.size());
        assertEquals("qa-model", models.path(0).path("id").asText());
        assertEquals(2, CATALOG_REQUESTS.get());

        mvc.perform(post(ROOT + "/" + id + "/test").with(user(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"modelId\":\"unlisted\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ROOT + "/" + id + "/test").with(user(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"modelId\":\"qa-model\",\"reasoningLevel\":\"high\"}"))
                .andExpect(status().isBadRequest());
        assertEquals("OK [已隐藏]", infer(admin, id).path("reply").asText());
        JsonNode chat = json.readTree(LAST_INFERENCE_BODY.get());
        assertEquals("qa-model", chat.path("model").asText());
        assertEquals("user", chat.path("messages").path(0).path("role").asText());
        assertEquals(128, chat.path("max_tokens").asInt());
        assertEquals("custom-low", chat.path("reasoning_effort").asText());

        String relocated = json.writeValueAsString(input("RESPONSES", "", false, 1, origin() + "/other"));
        mvc.perform(put(ROOT + "/" + id).with(user(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(relocated)).andExpect(status().isBadRequest());
        String update = json.writeValueAsString(input("RESPONSES", "", false, 1, origin() + "/v1"));
        var request = put(ROOT + "/" + id).with(user(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON);
        JsonNode updated = data(mvc.perform(request.content(update)).andExpect(status().isOk()));
        assertEquals(2, updated.path("version").asInt());
        assertTrue(updated.path("apiKeyConfigured").asBoolean());
        mvc.perform(request.content(update)).andExpect(status().isConflict());
        assertEquals("Responses OK", infer(admin, id).path("reply").asText());
        JsonNode responses = json.readTree(LAST_INFERENCE_BODY.get());
        assertTrue(responses.path("input").isTextual());
        assertTrue(responses.path("store").isBoolean());
        assertFalse(responses.path("store").asBoolean());
        assertEquals("custom-low", responses.path("reasoning").path("effort").asText());

        String cleared = json.writeValueAsString(input("RESPONSES", "", true, 2, origin() + "/v1"));
        assertFalse(data(mvc.perform(request.content(cleared)).andExpect(status().isOk())).path("apiKeyConfigured").asBoolean());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM model_provider WHERE id = :id AND api_key_ciphertext IS NOT NULL")
                .param("id", id).query(Integer.class).single());
        mvc.perform(delete(ROOT + "/" + id).with(user(admin)).with(csrf())).andExpect(status().isNoContent());
        assertEquals(0, data(mvc.perform(get(ROOT).with(user(admin))).andExpect(status().isOk())).size());
        var logs = jdbc.sql("SELECT detail FROM audit_log WHERE resource_id = :id").param("id", id).query(String.class).list();
        assertEquals(4, logs.size());
        assertTrue(logs.stream().noneMatch(detail -> detail.contains(KEY)));
    }

    @Test
    void rejectsUnsafeTargetsAndNeverFollowsProviderRedirects() throws Exception {
        var admin = principal("admin");
        discover(admin, "http://example.test/v1", "DISABLED").andExpect(status().isBadRequest());
        discover(admin, origin() + "/v1?secret=value", "DISABLED").andExpect(status().isBadRequest());
        discover(admin, "https://169.254.169.254/v1", "DISABLED").andExpect(status().isBadRequest());
        discover(admin, origin() + "/redirect", "TRANSIENT_ERRORS").andExpect(status().isBadGateway());
        assertEquals(0, REDIRECTED_REQUESTS.get());
        discover(admin, origin() + "/error", "DISABLED").andExpect(status().isBadGateway());
        assertEquals(1, FAILING_REQUESTS.get());
    }

    private ResultActions discover(PlatformUserPrincipal actor, String url, String retryPolicy) throws Exception {
        return mvc.perform(post(ROOT + "/discover").with(user(actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("apiBaseUrl", url, "retryPolicy", retryPolicy, "apiKey", KEY))));
    }

    private JsonNode infer(PlatformUserPrincipal actor, String id) throws Exception {
        return data(mvc.perform(post(ROOT + "/" + id + "/test").with(user(actor)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"modelId\":\"qa-model\",\"reasoningLevel\":\"low\"}"))
                .andExpect(status().isOk()));
    }

    private JsonNode data(ResultActions result) throws Exception {
        var response = result.andReturn().getResponse();
        assertFalse(response.getContentAsString().contains(KEY));
        return json.readTree(response.getContentAsByteArray()).path("data");
    }

    private Map<String, Object> input(String protocol, String key, boolean clearKey, long version, String url) {
        return Map.of("displayName", "QA Model Gateway", "apiBaseUrl", url, "protocol", protocol,
                "retryPolicy", "TRANSIENT_ERRORS", "apiKey", key, "clearApiKey", clearKey, "version", version,
                "models", List.of(Map.of("id", "qa-model", "displayName", "QA 模型", "acceptsImages", true,
                        "contextWindow", 65_536, "maxOutputTokens", 128,
                        "reasoningOptions", List.of(Map.of("level", "low", "apiValue", "custom-low")))));
    }

    private PlatformUserPrincipal principal(String name) {
        return new PlatformUserPrincipal(users.findByAccountOrEmail(name).orElseThrow());
    }

    private static String origin() {
        return "http://127.0.0.1:" + upstream.getAddress().getPort();
    }

    private static boolean authorized(HttpExchange exchange) throws IOException {
        if (("Bearer " + KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"))) return true;
        respond(exchange, 401, "{}");
        return false;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, data.length);
            exchange.getResponseBody().write(data);
        }
    }
}
