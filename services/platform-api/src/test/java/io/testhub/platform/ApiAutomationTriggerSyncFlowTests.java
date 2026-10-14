package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.apiautomation.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quartz.Scheduler;
import org.quartz.JobKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
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
class ApiAutomationTriggerSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r, directory, "trigger-sync-fixture");
        else {
            r.add("testhub.desktop.data-directory", directory::toString);
            r.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            r.add("spring.datasource.username", () -> "sa"); r.add("spring.datasource.password", () -> "trigger-sync-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired ApiAutomationTriggerSyncService sync;
    @Autowired ApiAutomationScenarioService scenarios;
    @Autowired ApiAutomationExecutionProfileService profiles;
    @Autowired ApiAutomationScheduleService schedules;
    @Autowired ApiAutomationScheduleRepository scheduleRepository;
    @Autowired ApiAutomationWebhookTriggerService webhooks;
    @Autowired ApiAutomationWebhookTriggerRepository webhookRepository;
    @Autowired ApiAutomationExecutionService executions;
    @Autowired ApiAutomationExecutionRepository executionRepository;
    @Autowired Scheduler quartz;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    record Fixture(PlatformUserPrincipal actor, String project, String scene, String profile) { }
    Fixture fixture() {
        String account = "trigger-sync-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test", "触发配置验收", "Trigger-fixture-2026!"));
        var context = access.context(actor); String project = context.projects().get(context.organizations().getFirst().id()).getFirst().id();
        var scene = scenarios.create(actor, project, new ApiAutomationScenarioService.ScenarioInput("来源场景", "", "test", "STOP", List.of(new AutomationStep("wait", "WAIT", "等待", Map.of("delayMillis", 1))), Map.of(), "", "", null));
        var profile = profiles.create(actor, project, new ApiAutomationExecutionProfileService.ProfileInput("运行配置", "test", Map.of(), null));
        return new Fixture(actor, project, scene.id(), profile.id());
    }
    ApiAutomationScheduleService.ScheduleInput input(Fixture f, String name, int timeout, boolean enabled) {
        return new ApiAutomationScheduleService.ScheduleInput(f.scene(), f.profile(), name, "0 0 9 1 1 ? 2099", "Asia/Shanghai", timeout, 3, enabled);
    }
    ApiAutomationTriggerSyncService.Snapshot snapshot(Fixture f) { return sync.snapshot(f.actor(), f.project(), f.actor().userId()); }
    ApiAutomationTriggerSyncService.Item item(Fixture f, String kind, String name) { return snapshot(f).items().stream().filter(i -> i.kind().equals(kind) && i.name().equals(name)).findFirst().orElseThrow(); }
    ApiAutomationTriggerSyncService.Request request(Fixture f, ApiAutomationTriggerSyncService.Item... items) {
        return new ApiAutomationTriggerSyncService.Request(UUID.randomUUID().toString(), f.actor().userId(), snapshot(f).revision(), java.util.Arrays.stream(items).map(i -> new ApiAutomationTriggerSyncService.Operation(UUID.randomUUID().toString(), i)).toList());
    }
    boolean job(String id) throws Exception { return quartz.checkExists(new JobKey(id, "testhub-api-automation")); }
    int count(String table, Fixture f) { return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id=:p").param("p", f.project()).query(Integer.class).single(); }
    String signature(String secret, String timestamp, String nonce, String body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal((timestamp + "\n" + nonce + "\n" + body).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void copiesDefinitionsWithMappedIdsPausedPlanAndWebhookDraftWithoutSecretOrExecution() throws Exception {
        var source = fixture(); var target = fixture();
        var sourcePlan = schedules.create(source.actor(), source.project(), input(source, "日常计划", 40, true));
        var sourceHook = webhooks.create(source.actor(), source.project(), source.scene(), "CI触发器");
        var data = snapshot(source); String encoded = json.writeValueAsString(data);
        assertFalse(encoded.contains(sourceHook.secret())); assertFalse(encoded.contains("secretCiphertext")); assertFalse(encoded.contains("lastExecutionId"));
        var req = request(target, item(source, "SCHEDULE", "日常计划"), item(source, "WEBHOOK", "CI触发器"));
        var receipt = sync.apply(target.actor(), target.project(), req);
        var plan = schedules.list(target.actor(), target.project()).getFirst(); var hook = webhooks.list(target.actor(), target.project()).getFirst();
        assertEquals(target.scene(), plan.scenarioId()); assertEquals(target.profile(), plan.executionProfileId()); assertFalse(plan.enabled()); assertFalse(job(plan.id()));
        assertEquals(target.scene(), hook.scenarioId()); assertFalse(hook.enabled()); assertFalse(hook.secretReady());
        assertEquals(0, count("api_automation_execution", target)); assertEquals(sourcePlan, schedules.list(source.actor(), source.project()).getFirst());
        assertEquals(receipt, sync.apply(target.actor(), target.project(), req)); assertEquals(1, count("api_automation_webhook_trigger", target));
    }
    @Test void localSecretPreparationIsOneTimeAndOldSourceSignatureCannotActivateCopy() throws Exception {
        var source = fixture(); var target = fixture(); var original = webhooks.create(source.actor(), source.project(), source.scene(), "CI");
        sync.apply(target.actor(), target.project(), request(target, item(source, "WEBHOOK", "CI")));
        var draft = webhooks.list(target.actor(), target.project()).getFirst();
        assertThrows(ResponseStatusException.class, () -> webhooks.setEnabled(target.actor(), target.project(), draft.id(), true));
        var ready = webhooks.prepareSecret(target.actor(), target.project(), draft.id());
        assertTrue(ready.webhook().secretReady()); assertFalse(ready.webhook().enabled()); assertNotEquals(original.secret(), ready.secret());
        var cipher = webhookRepository.findStoredById(draft.id()).orElseThrow().secretCiphertext();
        assertThrows(ResponseStatusException.class, () -> webhooks.prepareSecret(target.actor(), target.project(), draft.id()));
        assertEquals(cipher, webhookRepository.findStoredById(draft.id()).orElseThrow().secretCiphertext());
        webhooks.setEnabled(target.actor(), target.project(), draft.id(), true);
        String timestamp = Instant.now().toString(), nonce = UUID.randomUUID().toString(), body = "{\"timeoutSeconds\":10}";
        String oldSignature = signature(original.secret(), timestamp, nonce, body);
        assertThrows(ResponseStatusException.class, () -> webhooks.trigger(draft.id(), timestamp, nonce, oldSignature, body)); assertEquals(0, count("api_automation_execution", target));
        var run = webhooks.trigger(draft.id(), timestamp, nonce, signature(ready.secret(), timestamp, nonce, body), body);
        assertEquals(target.project(), run.projectId());
        assertThrows(ResponseStatusException.class, () -> webhooks.trigger(draft.id(), timestamp, nonce, signature(ready.secret(), timestamp, nonce, body), body));
        assertEquals(1, count("api_automation_execution", target));
    }
    @Test void incomingPlanEditKeepsOwnHistoryAndFailuresAndLateOutcomeNeverReenablesIt() throws Exception {
        var source = fixture(); var target = fixture(); schedules.create(source.actor(), source.project(), input(source, "计划", 90, true));
        var plan = schedules.create(target.actor(), target.project(), input(target, "计划", 30, true));
        var execution = executions.create(target.actor(), target.project(), target.scene(), 60, null, Map.of());
        scheduleRepository.markTriggered(plan.id(), execution.id(), Instant.now());
        jdbc.sql("UPDATE api_automation_schedule SET consecutive_failures=2 WHERE id=:id").param("id", plan.id()).update();
        var before = scheduleRepository.findById(plan.id()).orElseThrow(); assertTrue(job(plan.id()));
        sync.apply(target.actor(), target.project(), request(target, item(source, "SCHEDULE", "计划")));
        var after = scheduleRepository.findById(plan.id()).orElseThrow(); assertFalse(after.enabled()); assertFalse(job(plan.id()));
        assertEquals(90, after.timeoutSeconds()); assertEquals(2, after.consecutiveFailures()); assertEquals(before.lastExecutionId(), after.lastExecutionId()); assertEquals(before.lastTriggeredAt(), after.lastTriggeredAt()); assertEquals(before.lastExecutionStatus(), after.lastExecutionStatus());
        executionRepository.applyRunnerEvent(execution.taskId(), execution.id(), "SUCCEEDED", "EXECUTION_SUCCEEDED", "", "fixture-runner", Instant.now());
        schedules.reconcileOutcomes(); assertFalse(scheduleRepository.findById(plan.id()).orElseThrow().enabled());
    }
    @Test void unchangedAndReceiptReplayKeepManualEnablementAndExistingWebhookCiphertext() {
        var source = fixture(); var target = fixture(); schedules.create(source.actor(), source.project(), input(source, "计划", 30, false));
        var original = webhooks.create(source.actor(), source.project(), source.scene(), "CI");
        var plan = schedules.create(target.actor(), target.project(), input(target, "计划", 30, true));
        var hook = webhooks.create(target.actor(), target.project(), target.scene(), "CI").webhook();
        var ciphertext = webhookRepository.findStoredById(hook.id()).orElseThrow().secretCiphertext();
        var req = request(target, item(source, "SCHEDULE", "计划"), item(source, "WEBHOOK", "CI"));
        var receipt = sync.apply(target.actor(), target.project(), req);
        assertTrue(scheduleRepository.findById(plan.id()).orElseThrow().enabled()); assertTrue(webhooks.list(target.actor(), target.project()).getFirst().enabled());
        assertEquals(ciphertext, webhookRepository.findStoredById(hook.id()).orElseThrow().secretCiphertext());
        assertEquals(receipt, sync.apply(target.actor(), target.project(), req)); assertNotNull(original.secret());
    }
    @Test void changedWebhookDefinitionPausesAndPreservesLocalKey() {
        var source = fixture(); var target = fixture(); var other = scenarios.create(target.actor(), target.project(), new ApiAutomationScenarioService.ScenarioInput("旧目标", "", "test", "STOP", List.of(new AutomationStep("wait", "WAIT", "等", Map.of("delayMillis", 1))), Map.of(), "", "", null));
        webhooks.create(source.actor(), source.project(), source.scene(), "CI"); var existing = webhooks.create(target.actor(), target.project(), other.id(), "CI");
        var cipher = webhookRepository.findStoredById(existing.webhook().id()).orElseThrow().secretCiphertext();
        sync.apply(target.actor(), target.project(), request(target, item(source, "WEBHOOK", "CI")));
        var actual = webhooks.list(target.actor(), target.project()).getFirst(); assertFalse(actual.enabled()); assertTrue(actual.secretReady()); assertEquals(target.scene(), actual.scenarioId()); assertEquals(existing.webhook().id(), actual.id());
        assertEquals(cipher, webhookRepository.findStoredById(actual.id()).orElseThrow().secretCiphertext());
    }
    @Test void duplicateWebhookNamesBlockOnlyRelatedDefinitions() {
        var source = fixture();
        // Reproduce legacy duplicate names in this isolated fixture, then restore the production constraint.
        jdbc.sql("ALTER TABLE api_automation_webhook_trigger " + (FunctionalDatabaseProfile.mysql() ? "DROP INDEX " : "DROP CONSTRAINT ") + "uk_api_automation_webhook_project_name").update();
        try {
            webhooks.create(source.actor(), source.project(), source.scene(), "重复"); webhooks.create(source.actor(), source.project(), source.scene(), "重复");
            schedules.create(source.actor(), source.project(), input(source, "独立计划", 30, false));
            var snapshot = snapshot(source); assertTrue(snapshot.blockedKeys().containsKey("WEBHOOK\n重复")); assertEquals(1, snapshot.items().size());
        } finally {
            for (var hook : webhooks.list(source.actor(), source.project())) webhookRepository.delete(hook.id(), source.project());
            jdbc.sql("ALTER TABLE api_automation_webhook_trigger ADD CONSTRAINT uk_api_automation_webhook_project_name UNIQUE(project_id,name)").update();
        }
    }
    @Test void lateSqlFailureRollsBackDatabaseAndLeavesQuartzJobUntouched() throws Exception {
        var source = fixture(); var target = fixture(); schedules.create(source.actor(), source.project(), input(source, "回滚计划", 90, false));
        var existing = schedules.create(target.actor(), target.project(), input(target, "回滚计划", 30, true)); var before = snapshot(target);
        jdbc.sql("ALTER TABLE api_automation_webhook_trigger ADD CONSTRAINT trigger_sync_qa_reject CHECK(name <> 'RollbackHook')").update();
        try {
            var hook = new ApiAutomationTriggerSyncService.Item("WEBHOOK", "RollbackHook", Map.of("scenarioName", "来源场景"));
            var failure = assertThrows(RuntimeException.class, () -> sync.apply(target.actor(), target.project(), request(target, item(source, "SCHEDULE", "回滚计划"), hook)));
            // MySQL reports a CHECK violation as UncategorizedSQLException; H2 translates it as an integrity conflict.
            assertTrue(failure instanceof ResponseStatusException || failure instanceof org.springframework.dao.DataAccessException);
            assertEquals(before, snapshot(target)); assertTrue(job(existing.id())); assertEquals(0, count("api_automation_trigger_sync_receipt", target));
        } finally { jdbc.sql("ALTER TABLE api_automation_webhook_trigger " + (FunctionalDatabaseProfile.mysql() ? "DROP CHECK " : "DROP CONSTRAINT ") + "trigger_sync_qa_reject").update(); }
    }
    @Test void missingReferencesAndUnknownSecretOrActivationFieldsFailBeforeWrite() throws Exception {
        var source = fixture(); var target = fixture(); var missing = new ApiAutomationTriggerSyncService.Item("WEBHOOK", "缺失", Map.of("scenarioName", "没有场景"));
        assertThrows(ResponseStatusException.class, () -> sync.apply(target.actor(), target.project(), request(target, missing)));
        var req = request(target, new ApiAutomationTriggerSyncService.Item("WEBHOOK", "非法", Map.of("scenarioName", "来源场景", "secret", "must-not-import")));
        assertThrows(ResponseStatusException.class, () -> sync.apply(target.actor(), target.project(), req)); assertEquals(0, count("api_automation_webhook_trigger", target));
        assertThrows(ResponseStatusException.class, () -> sync.decode("{\"requestKey\":\"bad\",\"enabled\":true}".getBytes()));
    }
    @Test void staleUiEnableOrSaveCannotBypassAChangedConfiguration() {
        var f = fixture(); var hook = webhooks.create(f.actor(), f.project(), f.scene(), "CI").webhook();
        assertThrows(ResponseStatusException.class, () -> webhooks.setEnabled(f.actor(), f.project(), hook.id(), true, hook.updatedAt().minusSeconds(1)));
        var plan = schedules.create(f.actor(), f.project(), input(f, "计划", 30, false));
        assertThrows(ResponseStatusException.class, () -> schedules.updateIfUnchanged(f.actor(), f.project(), plan.id(), input(f, "计划", 30, true), plan.updatedAt().minusSeconds(1)));
        assertFalse(scheduleRepository.findById(plan.id()).orElseThrow().enabled());
    }
    @Test void managerScopeCsrfActorAndRequestIdentityAreEnforced() throws Exception {
        var source = fixture(); var target = fixture(); webhooks.create(source.actor(), source.project(), source.scene(), "CI"); var req = request(target, item(source, "WEBHOOK", "CI"));
        String path = "/api/v1/projects/" + target.project() + "/api-automation/triggers/sync";
        mvc.perform(post(path).with(user(target.actor())).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(req))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user(source.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(req))).andExpect(status().isForbidden());
        sync.apply(target.actor(), target.project(), req);
        var altered = new ApiAutomationTriggerSyncService.Request(req.requestKey(), req.expectedUserId(), req.expectedRevision(), List.of(new ApiAutomationTriggerSyncService.Operation(UUID.randomUUID().toString(), req.operations().getFirst().item())));
        assertThrows(ResponseStatusException.class, () -> sync.apply(target.actor(), target.project(), altered));
        jdbc.sql("UPDATE membership SET role_code='TESTER' WHERE user_id=:id").param("id", target.actor().userId()).update();
        mvc.perform(get(path).param("expectedUserId", target.actor().userId()).with(user(target.actor()))).andExpect(status().isForbidden());
    }
}
