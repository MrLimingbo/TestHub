package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.parameter.EnvironmentSecretService;
import io.testhub.platform.parameter.ProjectParameterService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

class P1ParameterRulesFlowTests extends P1FlowTestSupport {
    @org.springframework.beans.factory.annotation.Autowired io.testhub.platform.parameter.ProjectEnvironmentService environments;
    @Override io.testhub.platform.account.PlatformUserPrincipal actor() {
        var actor = super.actor();
        for (String code : java.util.List.of("qa", "other")) {
            environments.create(actor, project(actor), new io.testhub.platform.parameter.ProjectEnvironmentService.CreateInput(code, code, "", "", true, false));
        }
        return actor;
    }

    @Autowired ProjectParameterService parameters;
    @Autowired EnvironmentSecretService secrets;
    record Stored(String id, String value, boolean secret) { }
    Stored stored(String project, String environment, String key) {
        return jdbc.sql("SELECT id, parameter_value, secret FROM project_parameter WHERE project_id=:project AND environment_code=:env AND parameter_key=:key")
                .param("project", project).param("env", environment).param("key", key)
                .query((rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getBoolean(3))).single();
    }
    void rejected(int status, Runnable operation) {
        assertEquals(status, assertThrows(ResponseStatusException.class, operation::run).getStatusCode().value());
    }
    @Test @DisplayName("[P1-D02-01][POS] same-type ordinary parameter updates remain valid")
    void ordinaryUpdate() {
        var actor = actor(); String p = project(actor);
        parameters.upsert(actor, p, "qa", "KEY", "one"); parameters.upsert(actor, p, "qa", "KEY", "two");
        assertEquals("two", stored(p, "qa", "KEY").value()); assertFalse(stored(p, "qa", "KEY").secret());
    }
    @Test @DisplayName("[P1-D02-02][POS] same-type secret updates preserve encrypted storage")
    void secretUpdate() {
        var actor = actor(); String p = project(actor), value = password();
        secrets.upsert(actor, p, "qa", "KEY", password()); secrets.upsert(actor, p, "qa", "KEY", value);
        assertTrue(stored(p, "qa", "KEY").secret()); assertNotEquals(value, stored(p, "qa", "KEY").value());
    }
    @Test @DisplayName("[P1-D02-03][POS] different project and environment scopes remain independent")
    void isolatedScopes() {
        var a = actor(); var b = actor(); String pa = project(a), pb = project(b);
        secrets.upsert(a, pa, "qa", "KEY", password()); parameters.upsert(a, pa, "other", "KEY", "ordinary");
        parameters.upsert(b, pb, "qa", "KEY", "separate"); assertTrue(stored(pa, "qa", "KEY").secret());
        assertEquals("ordinary", stored(pa, "other", "KEY").value()); assertEquals("separate", stored(pb, "qa", "KEY").value());
    }
    @Test @DisplayName("[P1-D02-04][POS] existing key and environment normalization is retained")
    void normalization() {
        var a = actor(); String p = project(a);
        parameters.upsert(a, p, " QA ", " KEY ", "one"); parameters.upsert(a, p, "qa", "KEY", "two");
        assertEquals("two", stored(p, "qa", "KEY").value());
    }
    @Test @DisplayName("[P1-D02-05][NEG] ordinary save cannot overwrite a secret or emit success audit")
    void secretToOrdinary() {
        var a = actor(); String p = project(a); secrets.upsert(a, p, "qa", "KEY", password());
        var before = stored(p, "qa", "KEY"); long audit = auditCount();
        rejected(409, () -> parameters.upsert(a, p, "qa", "KEY", "replacement"));
        assertEquals(before, stored(p, "qa", "KEY")); assertEquals(audit, auditCount());
        assertTrue(parameters.list(a, p, "qa").parameters().isEmpty());
    }
    @Test @DisplayName("[P1-D02-06][NEG] secret save cannot silently convert an ordinary parameter")
    void ordinaryToSecret() {
        var a = actor(); String p = project(a); parameters.upsert(a, p, "qa", "KEY", "original");
        var before = stored(p, "qa", "KEY"); long audit = auditCount();
        rejected(409, () -> secrets.upsert(a, p, "qa", "KEY", password()));
        assertEquals(before, stored(p, "qa", "KEY")); assertEquals(audit, auditCount());
    }
    @Test @DisplayName("[P1-D02-07][NEG] competing different-type creates commit exactly one type")
    void concurrentTypes() throws Exception {
        var a = actor(); String p = project(a); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.<Runnable>of(() -> parameters.upsert(a, p, "qa", "RACE", "ordinary"), () -> secrets.upsert(a, p, "qa", "RACE", password()));
            var futures = tasks.stream().map(task -> pool.submit(() -> {
                ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                try { task.run(); return 0; } catch (ResponseStatusException ex) { return ex.getStatusCode().value(); }
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            var statuses = List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
            assertTrue(statuses.contains(0)); assertTrue(statuses.contains(409));
        }
    }
    @Test @DisplayName("[P1-D02-08][NEG] unauthorized cross-project writes cannot inspect or alter a key")
    void permissions() {
        var a = actor(); var b = actor(); String p = project(a); secrets.upsert(a, p, "qa", "KEY", password());
        var before = stored(p, "qa", "KEY");
        assertThrows(AccessDeniedException.class, () -> parameters.upsert(b, p, "qa", "KEY", "no"));
        assertEquals(before, stored(p, "qa", "KEY"));
    }
    @Test @DisplayName("[P1-D02-09][NEG] BASE_URL stays reserved for environment management")
    void reserved() {
        var a = actor(); String p = project(a);
        rejected(400, () -> parameters.upsert(a, p, "qa", "BASE_URL", "x"));
        rejected(400, () -> secrets.upsert(a, p, "qa", "base_url", "x"));
    }
    @Test @DisplayName("[P1-D02-10][NEG] validation errors do not change an existing typed value")
    void validation() {
        var a = actor(); String p = project(a); secrets.upsert(a, p, "qa", "KEY", password());
        var before = stored(p, "qa", "KEY");
        rejected(400, () -> secrets.upsert(a, p, "qa", "KEY", ""));
        rejected(400, () -> parameters.upsert(a, p, "qa", "bad key", "x"));
        assertEquals(before, stored(p, "qa", "KEY"));
    }
}
