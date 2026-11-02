package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import io.testhub.p1fixtures.P1HealthFixtureConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {"testhub.registration.enabled=true", "testhub.bootstrap.demo-enabled=false",
        "management.endpoint.health.group.readiness.include=readinessState,db,minio,p1Dependency"})
@Import(P1HealthFixtureConfiguration.class)
class P1HealthDeadlineFlowTests extends P1FlowTestSupport {
    @AfterEach void recover() { P1HealthFixtureConfiguration.MODE.set("UP"); }
    @Test @DisplayName("[P1-D25-01][POS] normal readiness and liveness remain available without health details")
    void healthy() throws Exception {
        String body = mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("components")); mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
    @Test @DisplayName("[P1-D25-02][NEG] a critical dependency failure makes readiness unavailable while liveness stays up")
    void failedDependency() throws Exception {
        P1HealthFixtureConfiguration.MODE.set("DOWN");
        var result = mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable()).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("p1-private"));
        mvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
    @Test @DisplayName("[P1-D25-03][NEG] a slow dependency cannot hold the readiness response beyond five seconds")
    void deadline() {
        P1HealthFixtureConfiguration.MODE.set("SLOW");
        assertTimeoutPreemptively(Duration.ofMillis(5_300), () -> mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable()));
    }
    @Test @DisplayName("[P1-D25-04][NEG] probe saturation keeps threads bounded and liveness independent")
    void saturation() throws Exception {
        P1HealthFixtureConfiguration.MODE.set("SLOW");
        try (var pool = Executors.newFixedThreadPool(4)) {
            var pending = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) pending.add(pool.submit(() -> { try { mvc.perform(get("/actuator/health/readiness")); } catch (Exception error) { throw new RuntimeException(error); } }));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (P1HealthFixtureConfiguration.BUSY.get() < 4 && System.nanoTime() < until) Thread.sleep(10);
            assertEquals(4, P1HealthFixtureConfiguration.BUSY.get());
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable()));
            assertTrue(Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("testhub-health-probe-")).count() <= 4);
            for (var future : pending) future.get(6, TimeUnit.SECONDS);
        }
    }
    @Test @DisplayName("[P1-D25-05][NEG] known dependency errors return controlled 503 and product bugs remain 500")
    void businessErrors() throws Exception {
        var actor = actor();
        var unavailable = mvc.perform(get("/api/v1/p1-fixture/redis-failure").with(user(actor))).andExpect(status().isServiceUnavailable()).andReturn();
        assertTrue(unavailable.getResponse().getContentAsString().contains("DEPENDENCY_UNAVAILABLE")); assertFalse(unavailable.getResponse().getContentAsString().contains("p1-private"));
        mvc.perform(get("/api/v1/p1-fixture/product-defect").with(user(actor))).andExpect(status().isInternalServerError());
    }
    @Test @DisplayName("[P1-D25-06][NEG] authentication and permission denials are not rewritten as dependency failures")
    void permissionStatus() throws Exception {
        mvc.perform(get("/api/v1/p1-fixture/redis-failure")).andExpect(status().isUnauthorized());
        var actor = actor(); var other = actor();
        mvc.perform(get("/api/v1/projects/" + project(other)).with(user(actor))).andExpect(status().isForbidden());
    }
    @Test @DisplayName("[P1-D25-07][POS] recovered critical dependency returns readiness to available")
    void recovery() throws Exception {
        P1HealthFixtureConfiguration.MODE.set("DOWN"); mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        P1HealthFixtureConfiguration.MODE.set("UP"); mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
    @Test @DisplayName("[P1-D25-08][POS] the active profile checks its actual local dependencies without an optional Runner requirement")
    void profileReadiness() throws Exception { mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()); }
}
