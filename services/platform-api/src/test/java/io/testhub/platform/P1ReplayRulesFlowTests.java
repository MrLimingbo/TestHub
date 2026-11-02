package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.apidebug.ApiDebugHistory;
import io.testhub.platform.apidebug.ApiDebugService;
import io.testhub.platform.security.SecretCipher;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@Import(P1ReplayRulesFlowTests.TimeConfiguration.class)
class P1ReplayRulesFlowTests extends P1FlowTestSupport {
    static final Instant START = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    static final MutableClock CLOCK = new MutableClock();
    static class MutableClock extends Clock {
        volatile Instant value = START;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(value, zone); }
        public Instant instant() { return value; }
    }
    @TestConfiguration static class TimeConfiguration { @Bean Clock replayClock() { return CLOCK; } }
    @Autowired ApiDebugService debug;
    @Autowired SecretCipher cipher;
    @Autowired ApplicationContext application;
    @BeforeEach void resetClock() { CLOCK.value = START; }
    ApiDebugHistory draft(PlatformUserPrincipal a, String p, ApiDebugService.AuthInput auth) {
        return debug.saveDraft(a, p, new ApiDebugService.DebugRequest("POST", "https://8.8.8.8/qa", List.of(), "{\"qa\":true}", 1000, null, auth, null, "QA草稿"));
    }
    ApiDebugHistory read(PlatformUserPrincipal a, String p, String id) { return debug.history(a, p, 100).stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow(); }
    JsonNode node(Object value) { return json.valueToTree(value); }
    String ciphertext(String id) { return jdbc.sql("SELECT replay_draft_ciphertext FROM api_debug_history WHERE id=:id").param("id", id).query(String.class).optional().orElse(null); }
    long rows(String id) { return jdbc.sql("SELECT COUNT(*) FROM api_debug_history WHERE id=:id").param("id", id).query(Long.class).single(); }
    void sweep() { application.getBean("apiDebugReplayRetention", Runnable.class).run(); }
    String legacy(PlatformUserPrincipal a, String p, Instant createdAt, String encrypted) {
        String id = com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();
        String organization = access.context(a).organizations().getFirst().id();
        jdbc.sql("""
                INSERT INTO api_debug_history(id,organization_id,project_id,method,protocol,request_url,response_status,duration_ms,response_size,result,replay_draft_ciphertext,created_by,created_at)
                VALUES(:id,:org,:project,'GET','HTTP','https://8.8.8.8/qa',200,1,0,'COMPLETED',:cipher,:actor,:created)
                """).param("id", id).param("org", organization).param("project", p).param("cipher", encrypted)
                .param("actor", a.userId()).param("created", Timestamp.from(createdAt)).update();
        return id;
    }
    @Test @DisplayName("[P1-D12-01][POS] an active replay is encrypted at rest and returned only as an authorized draft")
    void activeRoundTrip() {
        var a = actor(); String p = project(a), marker = unique();
        var saved = draft(a, p, new ApiDebugService.AuthInput("BEARER", marker, null, null, null, null, null));
        assertNotEquals(saved.replayDraft(), ciphertext(saved.id())); assertFalse(ciphertext(saved.id()).contains(marker));
        assertTrue(read(a, p, saved.id()).replayDraft().contains(marker));
        assertEquals("AVAILABLE", node(read(a, p, saved.id())).path("replayStatus").asText());
    }
    @Test @DisplayName("[P1-D12-02][POS] replay remains available immediately before the thirty-day cutoff")
    void beforeCutoff() {
        var a = actor(); String p = project(a), id = draft(a, p, null).id(); CLOCK.value = START.plus(30, ChronoUnit.DAYS).minusMillis(1);
        assertNotNull(read(a, p, id).replayDraft());
    }
    @Test @DisplayName("[P1-D12-03][POS] current authentication fields survive encrypted roundtrip")
    void activeAuth() {
        var a = actor(); String p = project(a), value = unique();
        var saved = draft(a, p, new ApiDebugService.AuthInput("BASIC", null, "qa", value, null, null, null));
        assertTrue(read(a, p, saved.id()).replayDraft().contains(value));
    }
    @Test @DisplayName("[P1-D12-04][POS] a legacy summary-only row stays available without inventing a replay")
    void summaryOnly() {
        var a = actor(); String p = project(a), id = legacy(a, p, START.minus(50, ChronoUnit.DAYS), null);
        assertNull(read(a, p, id).replayDraft()); assertEquals("SUMMARY_ONLY", node(read(a, p, id)).path("replayStatus").asText());
    }
    @Test @DisplayName("[P1-D12-05][POS] G0 removes only a managed expired ciphertext while preserving its summary row")
    void managedCleanup() {
        var a = actor(); String p = project(a), id = draft(a, p, null).id(); CLOCK.value = START.plus(31, ChronoUnit.DAYS);
        sweep(); assertNull(ciphertext(id)); assertEquals(1, rows(id)); assertEquals("EXPIRED", node(read(a, p, id)).path("replayStatus").asText());
    }
    @Test @DisplayName("[P1-D12-06][NEG] replay is unavailable at the exact UTC cutoff")
    void exactCutoff() {
        var a = actor(); String p = project(a), id = draft(a, p, null).id(); CLOCK.value = START.plus(30, ChronoUnit.DAYS);
        assertNull(read(a, p, id).replayDraft()); assertEquals("EXPIRED", node(read(a, p, id)).path("replayStatus").asText());
    }
    @Test @DisplayName("[P1-D12-07][NEG] expired legacy ciphertext is hidden by L0 but never bulk-deleted by G0")
    void legacyCutoff() {
        var a = actor(); String p = project(a), encrypted = cipher.encrypt("{\"protocol\":\"HTTP\"}");
        String id = legacy(a, p, START.minus(31, ChronoUnit.DAYS), encrypted);
        assertNull(read(a, p, id).replayDraft()); sweep(); assertEquals(encrypted, ciphertext(id)); assertEquals(1, rows(id));
    }
    @Test @DisplayName("[P1-D12-08][NEG] malformed active ciphertext reports unreadable without exposing ciphertext or hiding all history")
    void unreadable() {
        var a = actor(); String p = project(a), raw = "invalid-" + unique(), id = legacy(a, p, START, raw);
        var row = read(a, p, id); assertNull(row.replayDraft()); assertEquals("UNREADABLE", node(row).path("replayStatus").asText());
        assertFalse(node(row).toString().contains(raw)); assertEquals(raw, ciphertext(id));
    }
    @Test @DisplayName("[P1-D12-09][NEG] disabled authentication modes cannot leave hidden secrets in saved replay")
    void inactiveFields() {
        var a = actor(); String p = project(a), hidden = unique();
        var saved = draft(a, p, new ApiDebugService.AuthInput("BEARER", "active", hidden, hidden, hidden, hidden, "HEADER"));
        String clear = cipher.decrypt(ciphertext(saved.id())); assertFalse(clear.contains(hidden)); assertTrue(clear.contains("active"));
    }
    @Test @DisplayName("[P1-D12-10][NEG] G0 does not clear a managed unexpired ciphertext")
    void unexpiredNotCleared() {
        var a = actor(); String p = project(a), id = draft(a, p, null).id(), before = ciphertext(id);
        sweep(); assertEquals(before, ciphertext(id));
    }
    @Test @DisplayName("[P1-D12-11][NEG] repeated cleanup preserves summary counts and is idempotent")
    void idempotentCleanup() {
        var a = actor(); String p = project(a), id = draft(a, p, null).id(); CLOCK.value = START.plus(31, ChronoUnit.DAYS);
        sweep(); sweep(); assertNull(ciphertext(id)); assertEquals(1, rows(id));
    }
    @Test @DisplayName("[P1-D12-12][NEG] reads and repository reopen do not extend the original expiration")
    void noRenewal() {
        var a = actor(); String p = project(a), id = draft(a, p, null).id();
        String deadline = node(read(a, p, id)).path("replayExpiresAt").asText(); assertFalse(deadline.isEmpty());
        CLOCK.value = START.plus(29, ChronoUnit.DAYS); assertEquals(deadline, node(read(a, p, id)).path("replayExpiresAt").asText());
        CLOCK.value = START.plus(31, ChronoUnit.DAYS); assertNull(read(a, p, id).replayDraft());
    }
    @Test @DisplayName("[P1-D12-13][NEG] a future legacy timestamp is not trusted to grant replay access")
    void futureTime() {
        var a = actor(); String p = project(a), id = legacy(a, p, START.plus(1, ChronoUnit.DAYS), cipher.encrypt("{}"));
        assertNull(read(a, p, id).replayDraft()); assertEquals("UNKNOWN_CREATED_AT", node(read(a, p, id)).path("replayStatus").asText());
    }
    @Test @DisplayName("[P1-D12-14][NEG] expired replay data cannot be extracted through history transfer")
    void transferCannotBypass() throws Exception {
        var a = actor(); String p = project(a), marker = unique();
        draft(a, p, new ApiDebugService.AuthInput("BEARER", marker, null, null, null, null, null));
        CLOCK.value = START.plus(31, ChronoUnit.DAYS);
        var transfer = application.getBean(io.testhub.platform.apidebug.ApiHistoryTransferService.class);
        assertFalse(json.writeValueAsString(transfer.export(a, p)).contains(marker));
    }

    @Test @DisplayName("[P1-D12-15][NEG] imported ciphertext retains its original deadline and never opts into physical legacy cleanup")
    void importedRetention() {
        var a = actor(); String p = project(a), id = com.github.f4b6a3.ulid.UlidCreator.getUlid().toString(), raw = cipher.encrypt("{}");
        var repository = application.getBean(io.testhub.platform.apidebug.ApiDebugHistoryRepository.class);
        repository.insert(new ApiDebugHistory(id, access.context(a).organizations().getFirst().id(), p, "GET", "HTTP", "https://example.test", 200, 1, 0,
                "COMPLETED", null, raw, a.userId(), START.minus(31, ChronoUnit.DAYS), "old-project", "old-row"));
        assertNull(read(a, p, id).replayDraft()); sweep(); assertEquals(raw, ciphertext(id));
        assertEquals(START.minus(1, ChronoUnit.DAYS).toString(), node(read(a, p, id)).path("replayExpiresAt").asText());
    }
}
