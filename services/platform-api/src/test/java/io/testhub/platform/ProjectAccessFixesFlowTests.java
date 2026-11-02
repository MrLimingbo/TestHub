package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ApiTokenPrincipal;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {"testhub.registration.enabled=true", "testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver = FunctionalDatabaseProfile.class)
@DirtiesContext
class ProjectAccessFixesFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry, directory, "project-access-fixture");
        else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "project-access-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired SessionRepository<?> sessions;

    record Fixture(PlatformUserPrincipal actor, String project) { }

    Fixture fixture() {
        String account = "project-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test",
                "项目修复验收", UUID.randomUUID() + "Aa1!"));
        var context = access.context(actor);
        return new Fixture(actor, context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }

    String url(Fixture fixture) { return "/api/v1/projects/" + fixture.project(); }

    record SavedSession(String id, Cookie cookie) { }

    SavedSession browserSession(Fixture fixture) {
        return savedSession(sessions, UsernamePasswordAuthenticationToken.authenticated(
                fixture.actor(), null, fixture.actor().getAuthorities()));
    }

    SavedSession legacyTokenSession(Fixture fixture) {
        var principal = new ApiTokenPrincipal(fixture.actor().account(), fixture.project(), List.of("project:read"));
        return savedSession(sessions, UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
    }

    private <S extends Session> SavedSession savedSession(SessionRepository<S> repository, Authentication authentication) {
        var session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(authentication));
        repository.save(session);
        String encoded = Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
        return new SavedSession(session.getId(), new Cookie("TESTHUB_SESSION", encoded));
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoke", "expire", "disable"})
    void bearerIsRequestScopedAndInvalidatedEvenWithLegacySession(String change) throws Exception {
        var fixture = fixture();
        var token = access.createToken(fixture.actor(), fixture.project(), "令牌生命周期", List.of("project:read"), 1);
        var first = mvc.perform(get(url(fixture)).header("Authorization", "Bearer " + token.token()))
                .andExpect(status().isOk()).andReturn();
        assertNull(first.getRequest().getSession(false), "Bearer must not create a reusable authentication session");
        assertNull(first.getResponse().getCookie("TESTHUB_SESSION"), "Bearer must not issue a session cookie");
        var legacy = legacyTokenSession(fixture);
        switch (change) {
            case "revoke" -> access.revokeToken(fixture.actor(), fixture.project(), token.id());
            case "expire" -> jdbc.sql("UPDATE personal_access_token SET expires_at=:past WHERE id=:id")
                    .param("past", Timestamp.from(Instant.now().minusSeconds(60))).param("id", token.id()).update();
            case "disable" -> jdbc.sql("UPDATE user_account SET enabled=FALSE WHERE id=:id")
                    .param("id", fixture.actor().userId()).update();
            default -> throw new AssertionError(change);
        }
        mvc.perform(get(url(fixture)).cookie(legacy.cookie()).header("Authorization", "Bearer " + token.token()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(url(fixture)).header("Authorization", "Bearer " + token.token()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(url(fixture)).cookie(legacyTokenSession(fixture).cookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void legacyTokenSessionAloneIsRejectedWhileFreshTokenStillWorks() throws Exception {
        var fixture = fixture();
        var token = access.createToken(fixture.actor(), fixture.project(), "旧会话迁移", List.of("project:read"), 1);
        var legacy = legacyTokenSession(fixture);
        mvc.perform(get(url(fixture)).cookie(legacy.cookie())).andExpect(status().isUnauthorized());
        var saved = sessions.findById(legacy.id());
        assertTrue(saved == null || saved.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) == null,
                "Legacy PAT identity must be removed from the persisted session");
        mvc.perform(get(url(fixture)).cookie(legacy.cookie()).header("Authorization", "Bearer " + token.token()))
                .andExpect(status().isOk());
        mvc.perform(get(url(fixture)).cookie(legacy.cookie())).andExpect(status().isUnauthorized());
    }

    @Test
    void bearerTakesPrecedenceWithoutReplacingBrowserIdentity() throws Exception {
        var fixture = fixture();
        var sibling = access.createProject(fixture.actor(), "兄弟项目", "sibling-" + UUID.randomUUID(), "");
        var token = access.createToken(fixture.actor(), fixture.project(), "只读范围", List.of("project:read"), 1);
        var browser = browserSession(fixture);
        mvc.perform(get(url(fixture)).cookie(browser.cookie())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/projects/" + sibling.id()).cookie(browser.cookie())
                .header("Authorization", "Bearer " + token.token())).andExpect(status().isForbidden());
        mvc.perform(post(url(fixture) + "/members").cookie(browser.cookie())
                .header("Authorization", "Bearer " + token.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("account", fixture.actor().account().account(), "roleCode", "VIEWER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/projects/" + sibling.id()).cookie(browser.cookie())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(browser.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(fixture.actor().userId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer invalid", "Bearer ", "Basic invalid"})
    void invalidAuthorizationCannotFallBackToBrowserSession(String authorization) throws Exception {
        var fixture = fixture();
        var browser = browserSession(fixture);
        mvc.perform(get(url(fixture)).cookie(browser.cookie())).andExpect(status().isOk());
        mvc.perform(get(url(fixture)).cookie(browser.cookie()).header("Authorization", authorization))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(url(fixture)).cookie(browser.cookie())).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(ints = {79, 80, 81, 159, 160})
    void memberCreationKeepsFullAccountAndBoundsGeneratedDisplayName(int length) throws Exception {
        var fixture = fixture();
        String account = ("qa" + UUID.randomUUID().toString().replace("-", "")).substring(0, 9) + "x".repeat(length - 9);
        var response = mvc.perform(post(url(fixture) + "/members").with(user(fixture.actor())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("account", account, "roleCode", "TESTER"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode saved = json.readTree(response.getResponse().getContentAsByteArray()).path("data");
        assertEquals(account, saved.path("account").asText());
        assertEquals(account.substring(0, Math.min(80, length)), saved.path("displayName").asText());
        assertEquals(account, jdbc.sql("SELECT account FROM user_account WHERE id=:id")
                .param("id", saved.path("userId").asText()).query(String.class).single());
    }

    @Test
    void generatedMemberNameDoesNotSplitSupplementaryUnicode() {
        var fixture = fixture();
        String account = "q".repeat(79) + "😀end";
        var saved = access.upsertMember(fixture.actor(), fixture.project(), account, "TESTER");
        assertEquals(account, saved.account());
        assertEquals("q".repeat(79), saved.displayName());
    }

    static Stream<List<String>> invalidScopes() {
        return Stream.of(Arrays.asList((String) null), List.of(""), List.of(" "),
                Arrays.asList("project:read", null), List.of("project:read", ""));
    }

    @ParameterizedTest
    @MethodSource("invalidScopes")
    void invalidScopeElementsReturnClientErrorsWithoutCreatingTokens(List<String> scopes) throws Exception {
        var fixture = fixture();
        mvc.perform(post(url(fixture) + "/access-tokens").with(user(fixture.actor())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", "输入校验", "scopes", scopes))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        var error = assertThrows(ResponseStatusException.class,
                () -> access.createToken(fixture.actor(), fixture.project(), "服务边界", scopes, 1));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals(0L, jdbc.sql("SELECT COUNT(*) FROM personal_access_token WHERE project_id=:id")
                .param("id", fixture.project()).query(Long.class).single());
    }

    @RepeatedTest(3)
    void concurrentProjectCreationReturnsOneConflictAndOneCompleteProject() throws Exception {
        var fixture = fixture();
        String code = "race-" + UUID.randomUUID();
        List<Integer> statuses = race(() -> access.createProject(fixture.actor(), "并发创建", code, ""),
                () -> access.createProject(fixture.actor(), "并发创建", code, ""));
        assertEquals(List.of(200, 409), statuses);
        String project = jdbc.sql("SELECT id FROM project WHERE code=:code").param("code", code).query(String.class).single();
        assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM project_environment WHERE project_id=:id")
                .param("id", project).query(Long.class).single());
        assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE project_id=:id AND action='PROJECT_CREATED'")
                .param("id", project).query(Long.class).single());
    }

    @RepeatedTest(3)
    void concurrentProjectRenamesPreserveLosingProjectAndProduceOneConflict() throws Exception {
        var fixture = fixture();
        var left = access.createProject(fixture.actor(), "左项目", "left-" + UUID.randomUUID(), "左说明");
        var right = access.createProject(fixture.actor(), "右项目", "right-" + UUID.randomUUID(), "右说明");
        String code = "rename-" + UUID.randomUUID();
        assertEquals(List.of(200, 409), race(() -> access.updateProject(fixture.actor(), left.id(), "新名称", code, "新说明"),
                () -> access.updateProject(fixture.actor(), right.id(), "新名称", code, "新说明")));
        var nextLeft = access.project(fixture.actor(), left.id());
        var nextRight = access.project(fixture.actor(), right.id());
        assertTrue(nextLeft.code().equals(code) ^ nextRight.code().equals(code));
        if (!nextLeft.code().equals(code)) assertEquals(left, nextLeft);
        if (!nextRight.code().equals(code)) assertEquals(right, nextRight);
    }

    private List<Integer> race(Runnable left, Runnable right) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = Stream.of(left, right).map(action -> pool.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try { action.run(); return 200; }
                catch (ResponseStatusException exception) { return exception.getStatusCode().value(); }
            })).toList();
            start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS))
                    .stream().sorted().toList();
        }
    }
}
