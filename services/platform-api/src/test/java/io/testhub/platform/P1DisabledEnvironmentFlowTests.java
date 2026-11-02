package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.apiautomation.*;
import io.testhub.platform.parameter.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

class P1DisabledEnvironmentFlowTests extends P1FlowTestSupport {
    @Autowired ProjectEnvironmentService environments;
    @Autowired ApiAutomationScenarioService scenarios;
    @Autowired ApiAutomationExecutionService executions;
    @Autowired ApiAutomationExecutionProfileService profiles;
    @Autowired ApiAutomationDataSetService dataSets;
    @Autowired ApiAutomationScheduleService schedules;
    @Autowired ApiAutomationWebhookTriggerService webhooks;
    record Fixture(PlatformUserPrincipal actor, String project, ProjectEnvironment environment, ApiAutomationScenarioDetail scenario) { }
    Fixture fixture(boolean enabled) {
        var a = actor(); String p = project(a);
        var env = environments.create(a, p, new ProjectEnvironmentService.CreateInput("P1环境", "p1-qa", "https://example.test", "", enabled, false));
        return new Fixture(a, p, env, scenario(a, p, "p1-qa"));
    }
    ApiAutomationScenarioDetail scenario(PlatformUserPrincipal a, String p, String env) {
        return scenarios.create(a, p, new ApiAutomationScenarioService.ScenarioInput(unique(), "", env, "STOP",
                List.of(new AutomationStep("wait", "WAIT", "等待", Map.of("delayMillis", 1))), Map.of(), "", "", null));
    }
    void enabled(Fixture f, boolean enabled) {
        var current = environments.list(f.actor(), f.project()).stream().filter(e -> e.id().equals(f.environment().id())).findFirst().orElseThrow();
        environments.update(f.actor(), f.project(), current.id(), new ProjectEnvironmentService.UpdateInput(current.name(), current.baseUrl(), "", enabled, current.version()));
    }
    ApiAutomationExecution create(Fixture f) { return executions.create(f.actor(), f.project(), f.scenario().id(), 300, null, Map.of()); }
    long count(Fixture f, String table) { return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id=:p").param("p", f.project()).query(Long.class).single(); }
    void conflict(Runnable operation) { assertEquals(409, assertThrows(ResponseStatusException.class, operation::run).getStatusCode().value()); }
    @Test @DisplayName("[P1-D03-01][POS] enabled environment still creates a complete frozen execution")
    void enabledQueue() {
        var f = fixture(true); var execution = create(f);
        assertNotNull(executions.manifest(f.actor(), f.project(), execution.id())); assertEquals(1, count(f, "api_automation_execution"));
    }
    @Test @DisplayName("[P1-D03-02][POS] accepted execution continues with its original snapshot after disabling")
    void acceptedContinues() {
        var f = fixture(true); var e = create(f); String checksum = executions.manifest(f.actor(), f.project(), e.id()).checksum(); enabled(f, false);
        for (String state : List.of("ACCEPTED", "RUNNING", "SUCCEEDED")) executions.applyRunnerEvent(new ApiRunnerProtocol.ApiRunnerEvent(e.taskId(), e.id(), "p1-owned", state, state, "QA", Instant.now()));
        assertEquals("SUCCEEDED", executions.detail(f.actor(), f.project(), e.id()).status());
        assertEquals(checksum, executions.manifest(f.actor(), f.project(), e.id()).checksum());
    }
    @Test @DisplayName("[P1-D03-03][POS] disabled environment remains available for pure configuration preview")
    void readonlyPreview() {
        var f = fixture(false); var preview = executions.previewConfiguration(f.actor(), f.project(), f.scenario().id(), null, Map.of());
        assertEquals("p1-qa", preview.environmentCode()); assertEquals(0, count(f, "api_automation_execution"));
    }
    @Test @DisplayName("[P1-D03-04][POS] re-enabling allows subsequent submissions without catch-up runs")
    void reenabled() { var f = fixture(false); enabled(f, true); assertNotNull(create(f)); assertEquals(1, count(f, "api_automation_execution")); }
    @Test @DisplayName("[P1-D03-05][POS] existing undefined-scenario validation remains separate from deferred parameter rules")
    void undefinedStillLegacy() {
        var a = actor(); String p = project(a);
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> scenario(a, p, "undefined-legacy")).getStatusCode().value());
    }
    @Test @DisplayName("[P1-D03-06][NEG] disabled ordinary submission is rejected without a task or success audit")
    void disabledQueue() {
        var f = fixture(false); long before = auditCount(); conflict(() -> create(f));
        assertEquals(0, count(f, "api_automation_execution")); assertEquals(before, auditCount());
    }
    @Test @DisplayName("[P1-D03-07][NEG] disabled data-driven submission cannot leave a partially queued batch")
    void dataSet() {
        var f = fixture(false); var data = dataSets.create(f.actor(), f.project(), new ApiAutomationDataSetService.DataSetInput(unique(), "JSON", "[{\"row\":\"a\"},{\"row\":\"b\"}]"));
        conflict(() -> dataSets.createExecutions(f.actor(), f.project(), data.dataSet().id(), f.scenario().id(), 300));
        assertEquals(0, count(f, "api_automation_execution"));
    }
    @Test @DisplayName("[P1-D03-08][NEG] effective disabled execution-profile environment overrides an enabled scenario")
    void profileOverride() {
        var f = fixture(true); environments.create(f.actor(), f.project(), new ProjectEnvironmentService.CreateInput("停用覆盖", "p1-disabled", "https://example.test", "", false, false));
        var profile = profiles.create(f.actor(), f.project(), new ApiAutomationExecutionProfileService.ProfileInput(unique(), "p1-disabled", Map.of(), null));
        conflict(() -> executions.create(f.actor(), f.project(), f.scenario().id(), 300, profile.id(), Map.of())); assertEquals(0, count(f, "api_automation_execution"));
    }
    @Test @DisplayName("[P1-D03-09][NEG] Quartz skips disabled environment without counting Runner failures or disabling the schedule")
    void quartzSkip() {
        var f = fixture(false); var schedule = schedules.create(f.actor(), f.project(), new ApiAutomationScheduleService.ScheduleInput(f.scenario().id(), null, unique(), "0 0 0 1 1 ? 2099", "UTC", 300, 3, true));
        schedules.executeQuartzJob(schedule.id());
        var current = schedules.list(f.actor(), f.project()).getFirst(); assertTrue(current.enabled()); assertEquals(0, current.consecutiveFailures()); assertNull(current.lastExecutionId());
        assertEquals(0, count(f, "api_automation_execution"));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE resource_id=:id AND action='API_AUTOMATION_SCHEDULE_SKIPPED'").param("id", schedule.id()).query(Long.class).single());
        conflict(() -> schedules.runNow(f.actor(), f.project(), schedule.id()));
    }
    @Test @DisplayName("[P1-D03-10][NEG] a correctly signed inbound Webhook receives 409 without enqueuing")
    void signedWebhook() throws Exception {
        var f = fixture(false); var hook = webhooks.create(f.actor(), f.project(), f.scenario().id(), unique());
        String timestamp = Instant.now().toString(), nonce = unique(), body = "{}";
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(hook.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal((timestamp + "\n" + nonce + "\n" + body).getBytes(StandardCharsets.UTF_8)));
        mvc.perform(post("/api/v1/integrations/api-automation/webhooks/" + hook.webhook().id() + "/trigger")
                .header("X-TestHub-Webhook-Timestamp", timestamp).header("X-TestHub-Webhook-Nonce", nonce).header("X-TestHub-Webhook-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict()); assertEquals(0, count(f, "api_automation_execution"));
    }
    @Test @DisplayName("[P1-D03-11][NEG] authorized PAT execution cannot bypass disabled-environment validation")
    void pat() throws Exception {
        var f = fixture(false); var token = access.createToken(f.actor(), f.project(), unique(), List.of("run:execute"), 1);
        mvc.perform(post("/api/v1/projects/" + f.project() + "/api-automation/scenarios/" + f.scenario().id() + "/executions")
                .header("Authorization", "Bearer " + token.token()).contentType(MediaType.APPLICATION_JSON).content("{\"timeoutSeconds\":300}"))
                .andExpect(status().isConflict()); assertEquals(0, count(f, "api_automation_execution"));
    }
    @Test @DisplayName("[P1-D03-12][NEG] concurrent disable and submit cannot create a partial or unvalidated execution")
    void race() throws Exception {
        var f = fixture(true); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var submit = pool.submit(() -> { start.await(); try { create(f); return 0; } catch (ResponseStatusException ex) { return ex.getStatusCode().value(); } });
            var disable = pool.submit(() -> { start.await(); enabled(f, false); return true; }); start.countDown();
            int outcome = submit.get(10, TimeUnit.SECONDS); assertTrue(disable.get(10, TimeUnit.SECONDS)); assertTrue(outcome == 0 || outcome == 409);
            assertEquals(outcome == 0 ? 1 : 0, count(f, "api_automation_execution"));
            for (var e : executions.list(f.actor(), f.project())) assertNotNull(executions.manifest(f.actor(), f.project(), e.id()));
        }
    }
}
