package io.testhub.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.UserAccountRepository;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "testhub.bootstrap.demo-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("desktop")
@DirtiesContext
class AiAgentFlowTests {
    private static HttpServer upstream;
    private static final AtomicReference<String> CHAT = new AtomicReference<>();
    private static final AtomicReference<String> RESPONSES = new AtomicReference<>();
    private static final String KEY = "fixture-" + UUID.randomUUID();
    @TempDir static Path directory;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository users;
    @Autowired JdbcClient jdbc;
    @LocalServerPort int port;
    private HttpClient testerClient;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            CHAT.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String first = "{\"choices\":[{\"delta\":{\"content\":\"第一轮 " + KEY.substring(0, 8) + "\"}}]}";
            String second = "{\"choices\":[{\"delta\":{\"content\":\"" + KEY.substring(8) + "\"},\"finish_reason\":\"stop\"}]}";
            byte[] body = ("data: " + first + "\n\ndata: " + second + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        upstream.createContext("/v1/responses", exchange -> {
            RESPONSES.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    event: response.output_text.delta
                    data: {"type":"response.output_text.delta","delta":"第二轮"}

                    event: response.completed
                    data: {"type":"response.completed"}

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        upstream.start();
        properties.add("testhub.desktop.allowed-origins", AiAgentFlowTests::origin);
        properties.add("testhub.desktop.data-directory", directory::toString);
        properties.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
        properties.add("spring.datasource.password", () -> "isolated-ai-fixture");
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) upstream.stop(0);
    }

    @AfterEach
    void closeHttpClient() {
        if (testerClient != null) testerClient.close();
    }

    @Test
    void testerCanChatWithReferencesAndImportReviewedDraftsWithoutSharingHistoryOrKey() throws Exception {
        var tester = principal("tester");
        var admin = principal("admin");
        String project = jdbc.sql("SELECT id FROM project WHERE code = 'trade-v3'").query(String.class).single();
        String base = "/api/v1/projects/" + project;
        String ai = base + "/ai-testing";
        var chat = provider(admin, "CHAT_COMPLETIONS");
        var response = provider(admin, "RESPONSES");
        String overview = mvc.perform(get(ai).with(user(tester))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canManage").value(true)).andReturn().getResponse().getContentAsString();
        assertFalse(overview.contains(KEY));
        assertFalse(overview.contains("apiBaseUrl"));
        var workspace = data(postJson(ai + "/workspaces", tester, Map.of("name", "用例生成")));
        var agent = data(postJson(ai + "/agents", tester, Map.of("workspaceId", workspace.path("id").asText(),
                "name", "测试智能体", "description", "阅读需求", "systemPrompt", "系统提示",
                "model", chat)));
        var conversation = data(postJson(ai + "/agents/" + agent.path("id").asText() + "/conversations", tester, Map.of()));
        String chatPath = ai + "/conversations/" + conversation.path("id").asText();
        var listed = data(mvc.perform(get(ai + "/conversations").with(user(tester)))).valueStream()
                .filter(row -> row.path("id").equals(conversation.path("id"))).findFirst().orElseThrow();
        assertEquals(conversation.path("id"), listed.path("id"));
        assertEquals(workspace.path("id"), listed.path("workspaceId"));
        mvc.perform(get(chatPath).with(user(admin))).andExpect(status().isNotFound());
        String requirementCatalog = data(postJson(base + "/functional/catalogs", tester,
                Map.of("scope", "REQUIREMENTS", "name", "验收需求"))).path("id").asText();
        String requirement = data(postJson(base + "/functional/requirements", tester,
                Map.of("catalogId", requirementCatalog, "requirementCode", "REQ-AI-1", "title", "订单券",
                        "description", "只能使用一次", "priority", "P1"))).path("id").asText();

        var attachment = data(mvc.perform(multipart(ai + "/attachments/preview")
                .file(new MockMultipartFile("file", "要求.md", "text/plain", "支持中文附件".getBytes(StandardCharsets.UTF_8)))
                .with(user(tester)).with(csrf())));
        String first = send(chatPath, Map.of("model", chat, "text", "分析需求",
                "attachments", List.of(attachment), "assets", List.of(Map.of("kind", "REQUIREMENT", "id", requirement))));
        assertTrue(first.contains("event:delta"), first);
        assertFalse(first.contains(KEY));
        assertTrue(CHAT.get().contains("只能使用一次"));
        assertTrue(CHAT.get().contains("支持中文附件"));
        JsonNode detail = data(mvc.perform(get(chatPath).with(user(tester))));
        assertEquals("分析需求", detail.path("conversation").path("title").asText());
        var firstTurn = detail.path("turns").get(0);
        assertEquals("COMPLETED", firstTurn.path("status").asText());
        assertEquals("第一轮 [已隐藏]", firstTurn.path("reply").asText());
        assertFalse(firstTurn.has("promptContext"));
        assertTrue(firstTurn.path("sourceSummary").asText().contains("要求.md"));

        var target = data(postJson(ai + "/workspaces", tester, Map.of("name", "归类后的会话")));
        var moved = data(updateJson(chatPath, tester, Map.of("workspaceId", target.path("id").asText(), "version", 1)));
        assertEquals(conversation.path("id"), moved.path("id"));
        assertEquals(target.path("id"), moved.path("workspaceId"));
        String followup = send(chatPath, Map.of("model", response, "text", "继续",
                "previousTurnId", firstTurn.path("id").asText()));
        assertTrue(followup.contains("第二轮"));
        JsonNode payload = json.readTree(RESPONSES.get());
        assertEquals("系统提示", payload.path("input").get(0).path("content").asText());
        assertEquals("assistant", payload.path("input").get(2).path("role").asText());
        assertEquals("第一轮 [已隐藏]", payload.path("input").get(2).path("content").asText());
        assertFalse(payload.path("store").asBoolean());
        assertEquals(2, data(mvc.perform(get(chatPath).with(user(tester)))).path("turns").size());

        String casesFolder = data(postJson(base + "/functional/catalogs", tester,
                Map.of("scope", "CASES", "name", "写入目的目录"))).path("id").asText();
        var batch = Map.of("requestId", UUID.randomUUID().toString(), "catalogId", casesFolder, "requirementId", requirement,
                "cases", List.of(Map.of("title", "使用优惠券", "priority", "P1", "steps", "使用两次", "expectedResult", "第二次被拒绝")));
        var saved = data(postJson(ai + "/case-drafts", tester, batch)).get(0);
        assertEquals("DRAFT", saved.path("status").asText());
        assertEquals(requirement, saved.path("requirementId").asText());
        assertEquals(saved.path("id"), data(postJson(ai + "/case-drafts", tester, batch)).get(0).path("id"));
    }

    @Test
    void createsAndManagesPrivateConversationsDirectlyInAWorkspace() throws Exception {
        var tester = principal("tester");
        var admin = principal("admin");
        String project = jdbc.sql("SELECT id FROM project WHERE code = 'trade-v3'").query(String.class).single();
        String ai = "/api/v1/projects/" + project + "/ai-testing";
        String workspaceId = data(postJson(ai + "/workspaces", tester, Map.of("name", "直接建会话"))).path("id").asText();
        String url = ai + "/workspaces/" + workspaceId + "/conversations";
        var first = data(postJson(url, tester, Map.of()));
        var second = data(postJson(url, admin, Map.of()));
        assertEquals(workspaceId, first.path("workspaceId").asText());
        assertTrue(first.path("agentId").isNull());
        var ids = data(mvc.perform(get(ai + "/conversations").with(user(tester)))).valueStream().map(row -> row.path("id")).toList();
        assertTrue(ids.contains(first.path("id")));
        assertFalse(ids.contains(second.path("id")));

        String conversation = ai + "/conversations/" + first.path("id").asText();
        var rename = Map.of("workspaceId", workspaceId, "title", "独立会话", "version", 1);
        updateJson(conversation, admin, rename).andExpect(status().isNotFound());
        data(updateJson(conversation, tester, rename));
        updateJson(conversation, tester, rename).andExpect(status().isConflict());

        var model = provider(admin, "RESPONSES");
        assertTrue(send(conversation, Map.of("model", model, "text", "第一条消息")).contains("第二轮"));
        var detail = data(mvc.perform(get(conversation).with(user(tester))));
        assertEquals("独立会话", detail.path("conversation").path("title").asText());
        assertEquals("user", json.readTree(RESPONSES.get()).path("input").get(0).path("role").asText());

        var archived = data(updateJson(conversation, tester, Map.of("workspaceId", workspaceId, "archived", true, "version", 2)));
        assertTrue(archived.path("archived").asBoolean());
        var followup = Map.of("model", model, "text", "继续", "previousTurnId", detail.path("turns").get(0).path("id").asText());
        assertTrue(send(conversation, followup).contains("会话已归档"));
        var restored = data(updateJson(conversation, tester, Map.of("workspaceId", workspaceId, "archived", false, "version", 3)));
        assertFalse(restored.path("archived").asBoolean());
        assertTrue(send(conversation, followup).contains("第二轮"));
        assertEquals(2, data(mvc.perform(get(conversation).with(user(tester)))).path("turns").size());
    }

    private Map<String, String> provider(PlatformUserPrincipal admin, String protocol) throws Exception {
        var row = data(postJson("/api/v1/model-providers", admin, Map.of("apiKey", KEY, "retryPolicy", "DISABLED",
                "apiBaseUrl", origin() + "/v1", "displayName", protocol + " " + UUID.randomUUID(), "protocol", protocol,
                "models", List.of(Map.of("id", "qa-model")))));
        return Map.of("providerId", row.path("id").asText(), "modelId", "qa-model");
    }

    private String send(String path, Object body) throws Exception {
        if (testerClient == null) {
            // Use the live servlet container for SSE, including its async completion and disconnect handling.
            testerClient = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            httpPost("/api/v1/auth/login", Map.of("account", "tester", "password", "TestHub2026!"), "application/json");
        }
        return httpPost(path + "/turns", body, "text/event-stream");
    }

    private String httpPost(String path, Object body, String accept) throws Exception {
        var csrfResponse = testerClient.send(requestTo("/api/v1/auth/csrf").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, csrfResponse.statusCode());
        var token = json.readTree(csrfResponse.body()).path("data");
        var response = testerClient.send(requestTo(path).header(token.path("headerName").asText(), token.path("token").asText())
                .header("Content-Type", "application/json").header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path);
        return response.body();
    }

    private HttpRequest.Builder requestTo(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
    }

    private ResultActions postJson(String path, PlatformUserPrincipal principal, Object body) throws Exception {
        return mvc.perform(post(path).with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)));
    }

    private ResultActions updateJson(String path, PlatformUserPrincipal principal, Object body) throws Exception {
        return mvc.perform(put(path).with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    }

    private PlatformUserPrincipal principal(String account) {
        return new PlatformUserPrincipal(users.findByEmail(account + "@testhub.local").orElseThrow());
    }

    private static String origin() { return "http://127.0.0.1:" + upstream.getAddress().getPort(); }
}
