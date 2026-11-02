package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessRepository;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
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
class AccountConfigFixesFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry, directory, "account-config-fixture");
        else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "account-config-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccountManagementService accounts;
    @Autowired UserAccountRepository users;
    @Autowired AccessService access;
    @Autowired AccessRepository memberships;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;

    String name() { return "fix-" + UUID.randomUUID(); }
    String password() { return UUID.randomUUID() + "Aa1!"; }
    PlatformUserPrincipal admin() {
        String name = name();
        var principal = new PlatformUserPrincipal(registration.register(name, name + "@example.test", "修复回归", password()));
        String organization = access.context(principal).organizations().getFirst().id();
        memberships.insertMembership(UlidCreator.getUlid().toString(), principal.userId(), organization, null, "SYSTEM_ADMIN");
        return principal;
    }
    UserAccountRepository.ManagedAccount create(PlatformUserPrincipal admin, String account, String email) {
        return accounts.create(admin, account, "QA", email, null, true);
    }
    void conflict(Runnable action) {
        assertEquals(409, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }
    void login(String identifier, String password, int expected, String userId) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("account", identifier, "password", password))))
                .andExpect(status().is(expected)).andReturn();
        if (expected == 200) assertEquals(userId, json.readTree(result.getResponse().getContentAsByteArray()).path("data").path("id").asText());
        else {
            assertEquals("AUTH_INVALID_CREDENTIALS", json.readTree(result.getResponse().getContentAsByteArray()).path("code").asText());
            var session = result.getRequest().getSession(false);
            assertTrue(session == null || session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) == null);
            assertFalse(result.getResponse().getContentAsString().contains(password));
        }
    }
    @Test void rejectsBothCrossFieldDirectionsOnCreateAndUpdateWithoutChangingAccountsOrAudit() throws Exception {
        var admin = admin();
        var a = create(admin, name() + "@login.test", name() + "@example.test");
        var b = create(admin, name(), name() + "@example.test");
        long before = jdbc.sql("SELECT COUNT(*) FROM audit_log").query(Long.class).single();
        conflict(() -> create(admin, a.email().toUpperCase(java.util.Locale.ROOT), name() + "@example.test"));
        conflict(() -> create(admin, name(), a.account().toUpperCase(java.util.Locale.ROOT)));
        conflict(() -> accounts.update(admin, b.id(), a.email(), "不应保存", b.email(), null, true));
        conflict(() -> accounts.update(admin, b.id(), b.account(), "不应保存", a.account(), null, true));
        assertEquals(b, users.findManagedById(b.id()).orElseThrow());
        assertEquals(before, jdbc.sql("SELECT COUNT(*) FROM audit_log").query(Long.class).single());
        login(a.email(), AccountManagementService.DEFAULT_PASSWORD, 200, a.id());
        login(a.account(), AccountManagementService.DEFAULT_PASSWORD, 200, a.id());
    }
    @Test void ownIdentifiersMayMatchOrSwapAndDeletedIdentifiersCanBeReused() {
        var admin = admin();
        var a = create(admin, name() + "@login.test", name() + "@example.test");
        var swapped = accounts.update(admin, a.id(), a.email(), "QA", a.account(), null, true);
        assertEquals(a.email(), swapped.account());
        var same = accounts.update(admin, a.id(), a.account(), "QA", a.account(), null, true);
        assertEquals(same.account(), same.email());
        accounts.delete(admin, a.id());
        assertNotEquals(a.id(), create(admin, a.account(), a.email()).id());
    }
    @Test void disabledIdentifiersRemainReservedAndRegistrationCannotIntroduceCrossFieldCollision() {
        var admin = admin();
        var a = create(admin, name() + "@login.test", name() + "@example.test");
        accounts.update(admin, a.id(), a.account(), a.displayName(), a.email(), null, false);
        conflict(() -> create(admin, a.email(), name() + "@example.test"));
        conflict(() -> registration.register(name(), a.account(), "QA", password()));
    }
    int outcome(Runnable action) {
        try { action.run(); return 200; }
        catch (ResponseStatusException error) { return error.getStatusCode().value(); }
    }
    void race(Callable<Integer> left, Callable<Integer> right, String identifier) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var jobs = List.of(left, right).stream().map(task -> pool.submit(() -> {
                ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return task.call();
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            assertEquals(List.of(200, 409), jobs.stream().map(job -> {
                try { return job.get(20, TimeUnit.SECONDS); } catch (Exception error) { throw new AssertionError(error); }
            }).sorted().toList());
        }
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM user_account WHERE deleted_at IS NULL AND (account=:value OR email=:value)")
                .param("value", identifier).query(Integer.class).single());
    }
    @RepeatedTest(3) void concurrentCreateCannotReserveTheSameIdentifierAcrossDifferentColumns() throws Exception {
        var admin = admin(); String shared = name() + "@example.test";
        race(() -> outcome(() -> create(admin, shared, name() + "@example.test")),
                () -> outcome(() -> create(admin, name(), shared)), shared);
    }
    @RepeatedTest(3) void concurrentUpdatesCannotReserveTheSameIdentifierAcrossDifferentColumns() throws Exception {
        var admin = admin(); var a = create(admin, name(), name() + "@example.test");
        var b = create(admin, name(), name() + "@example.test"); String shared = name() + "@example.test";
        race(() -> outcome(() -> accounts.update(admin, a.id(), shared, "QA", a.email(), null, true)),
                () -> outcome(() -> accounts.update(admin, b.id(), b.account(), "QA", shared, null, true)), shared);
    }
    @RepeatedTest(3) void concurrentRegistrationAndAdministrationShareTheSameIdentityGuard() throws Exception {
        var admin = admin(); String shared = name() + "@example.test";
        race(() -> outcome(() -> create(admin, shared, name() + "@example.test")),
                () -> outcome(() -> registration.register(name(), shared, "QA", password())), shared);
    }
    @Test void legacyAmbiguityFailsClosedWithoutBreakingTheUnambiguousAccountLogin() throws Exception {
        var admin = admin(); var a = create(admin, name(), name() + "@example.test");
        var b = create(admin, name(), name() + "@example.test");
        jdbc.sql("UPDATE user_account SET account=:account WHERE id=:id").param("account", a.email()).param("id", b.id()).update();
        login(a.email(), AccountManagementService.DEFAULT_PASSWORD, 401, null);
        login(a.account(), AccountManagementService.DEFAULT_PASSWORD, 200, a.id());
        // Existing ambiguous data still produces a conflict on subsequent identity writes.
        conflict(() -> create(admin, a.email(), name() + "@example.test"));
    }
    @ParameterizedTest @ValueSource(ints = {80, 81, 160})
    void emptyGeneratedNameNeverShortensTheAccount(int length) {
        var admin = admin(); String account = "q".repeat(length - 36) + UUID.randomUUID();
        var a = accounts.create(admin, account, null, null, null, true);
        assertEquals(account, a.account()); assertEquals(account.substring(0, 80), a.displayName());
        var updated = accounts.update(admin, a.id(), account, "  ", a.email(), null, true);
        assertEquals(a.displayName(), updated.displayName()); assertEquals(account, updated.account());
    }
    @Test void automaticNameDoesNotSplitASurrogatePairAndExplicitOverlengthNameStillFails() {
        var admin = admin(); String account = "q".repeat(79) + "😀" + UUID.randomUUID();
        var a = accounts.create(admin, account, "", null, null, true);
        assertEquals("q".repeat(79), a.displayName()); assertEquals(account, a.account());
        assertEquals(a.displayName(), accounts.update(admin, a.id(), account, null, a.email(), null, true).displayName());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> accounts.create(admin, name(), "名".repeat(81), null, null, true)).getStatusCode().value());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void disabledNewLoginIs401WithNoAuthenticatedSessionForCorrectOrWrongPassword(boolean disableAfterCreate) throws Exception {
        var admin = admin(); String name = name();
        var a = accounts.create(admin, name, "QA", null, null, disableAfterCreate);
        if (disableAfterCreate) {
            login(a.account(), AccountManagementService.DEFAULT_PASSWORD, 200, a.id());
            accounts.update(admin, a.id(), a.account(), a.displayName(), a.email(), null, false);
        }
        login(a.account(), AccountManagementService.DEFAULT_PASSWORD, 401, null);
        login(a.account(), password(), 401, null);
        accounts.update(admin, a.id(), a.account(), a.displayName(), a.email(), null, true);
        login(a.account(), AccountManagementService.DEFAULT_PASSWORD, 200, a.id());
    }
    @ParameterizedTest @ValueSource(strings = {"", " ", "\t \n", "中文😀"})
    void ordinaryConfigCanBeCreatedAndUpdatedWithEmptyOrWhitespaceValues(String value) throws Exception {
        var admin = admin(); String key = "qa." + UUID.randomUUID();
        for (String input : List.of(value, "non-empty", "")) {
            mvc.perform(post("/api/v1/system-config").with(user(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("key", key, "value", input)))).andExpect(status().isNoContent());
            assertEquals(input, jdbc.sql("SELECT config_value FROM system_config WHERE config_key=:key").param("key", key).query(String.class).single());
        }
    }
    @Test void nullMissingOversizedConfigAndEmptyNetworkBooleanAreStillRejected() throws Exception {
        var admin = admin(); String key = "qa." + UUID.randomUUID();
        for (String body : List.of("{\"key\":\"" + key + "\"}", "{\"key\":\"" + key + "\",\"value\":null}",
                json.writeValueAsString(Map.of("key", key, "value", "x".repeat(20001))))) {
            mvc.perform(post("/api/v1/system-config").with(user(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isBadRequest());
        }
        String network = "api-debug.allow-configured-private-targets";
        for (String value : List.of("", " ", "1")) {
            mvc.perform(post("/api/v1/system-config").with(user(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("key", network, "value", value)))).andExpect(status().isBadRequest());
        }
    }
}
