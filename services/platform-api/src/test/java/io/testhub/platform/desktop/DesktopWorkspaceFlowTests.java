package io.testhub.platform.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"testhub.bootstrap.demo-enabled=false", "testhub.registration.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("desktop")
@DirtiesContext
class DesktopWorkspaceFlowTests {
    @TempDir static Path directory;
    private static final String KEY = "b".repeat(64);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("testhub.desktop.data-directory", () -> directory.resolve("workspace").toString());
        registry.add("spring.datasource.password", () -> "local-workspace-unit-only");
        registry.add("testhub.desktop.session-key", () -> KEY);
        try {
            var frontend = java.nio.file.Files.createDirectory(directory.resolve("web"));
            java.nio.file.Files.writeString(frontend.resolve("index.html"), "<div id=\"app\">local UI</div>");
            registry.add("testhub.desktop.frontend-directory", frontend::toString);
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Test void servesLocalEntryAndDeepLinksWithoutExposingBusinessData() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string("<div id=\"app\">local UI</div>"));
        mvc.perform(get("/dashboard")).andExpect(status().isOk()).andExpect(content().string("<div id=\"app\">local UI</div>"));
        mvc.perform(get("/api/v1/access/context").servletPath("/api/v1/access/context")).andExpect(status().isUnauthorized());
    }
    @Test void readinessRequiresNativeAuthorizationAndAValidCurrentRunnerHeartbeat() throws Exception {
        String path = "/internal/desktop/v1/status";
        mvc.perform(get(path).param("startedAfter", java.time.Instant.now().toString())).andExpect(status().isForbidden());
        mvc.perform(get(path).param("startedAfter", "invalid").header("X-TestHub-Desktop-Session", KEY)).andExpect(status().isBadRequest());
        mvc.perform(get(path).param("startedAfter", java.time.Instant.now().toString()).header("X-TestHub-Desktop-Session", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.apiRunnerReady").value(false)).andExpect(jsonPath("$.data.jmeterRunnerReady").value(false)).andExpect(jsonPath("$.data.playwrightRunnerReady").value(false));
    }
    @Test void refusesAnonymousBrowserRemoteAndInvalidNativeRequests() throws Exception {
        mvc.perform(post("/internal/desktop/v1/session")).andExpect(status().isForbidden());
        mvc.perform(post("/internal/desktop/v1/session").header("X-TestHub-Desktop-Session", KEY).header("Origin", "http://localhost"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/desktop/v1/session").header("X-TestHub-Desktop-Session", KEY).with(r -> { r.setRemoteAddr("192.168.1.10"); return r; }))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/desktop/v1/session").header("X-TestHub-Desktop-Session", "c".repeat(64)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/access/context")).andExpect(status().isUnauthorized());
    }
    @Test void reusesTheLocalOwnerAndProjectAndRotatesTheSessionWithoutRegistration() throws Exception {
        var first = mvc.perform(post("/internal/desktop/v1/session").header("X-TestHub-Desktop-Session", KEY))
                .andExpect(status().isOk()).andReturn();
        Cookie original = first.getResponse().getCookie("TESTHUB_SESSION");
        String owner = json.readTree(first.getResponse().getContentAsString()).at("/data/userId").asText();
        mvc.perform(get("/api/v1/auth/me").cookie(original)).andExpect(status().isOk()).andExpect(jsonPath("$.data.displayName").value("本地用户"));
        var before = mvc.perform(get("/api/v1/access/context").cookie(original)).andExpect(status().isOk()).andReturn();
        var second = mvc.perform(post("/internal/desktop/v1/session").header("X-TestHub-Desktop-Session", KEY).cookie(original))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(second.getResponse().getContentAsString()).at("/data/userId").asText()).isEqualTo(owner);
        Cookie rotated = second.getResponse().getCookie("TESTHUB_SESSION");
        assertThat(rotated.getValue()).isNotEqualTo(original.getValue());
        mvc.perform(get("/api/v1/auth/me").cookie(original)).andExpect(status().isUnauthorized());
        var after = mvc.perform(get("/api/v1/access/context").cookie(rotated)).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(after.getResponse().getContentAsString()).path("data")).isEqualTo(json.readTree(before.getResponse().getContentAsString()).path("data"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM user_account").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM project").query(Integer.class).single()).isEqualTo(1);
        mvc.perform(get("/api/v1/system-config").cookie(rotated)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/capabilities")).andExpect(jsonPath("$.data.registrationEnabled").value(false));
    }
}
