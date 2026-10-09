package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.uiautomation.*;
import io.testhub.platform.uiautomation.UiAutomationTransferService.*;
import io.testhub.platform.functional.FunctionalTestExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.quartz.Scheduler;
import org.quartz.JobKey;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
class UiAutomationTransferFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry, directory, "ui-transfer-fixture");
        else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "ui-transfer-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired UiAutomationService automation;
    @Autowired UiAutomationScheduleService schedules;
    @Autowired UiAutomationTransferService transfer;
    @Autowired FunctionalTestExecutionService functional;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Scheduler quartz;
    @BeforeEach void allowed() {
        jdbc.sql("DELETE FROM system_config WHERE config_key = 'ui-automation.allowed-origins'").update();
        jdbc.sql("INSERT INTO system_config(config_key,config_value,secret,updated_by) VALUES('ui-automation.allowed-origins','https://ui.example.test',FALSE,'fixture')").update();
    }
    record Fixture(PlatformUserPrincipal actor, String project) { }
    Fixture fixture() {
        String account = "ui-transfer-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test", "Web UI 迁移测试", "Transfer-fixture-2026!"));
        var context = access.context(actor);
        return new Fixture(actor, context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    int count(String table, Fixture fixture) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id=:project")
                .param("project", fixture.project()).query(Integer.class).single();
    }
    Snapshot source(Fixture f, String planId) {
        var profile = automation.createProfile(f.actor(), f.project(), new UiAutomationService.ProfileRequest("桌面浏览器", "chromium", "https://ui.example.test/orders", 1200, 800, true));
        automation.updateProfile(f.actor(), f.project(), profile.id(), new UiAutomationService.ProfileRequest("桌面浏览器", "chromium", profile.baseUrl(), 1280, 900, true));
        var object = automation.createPageObject(f.actor(), f.project(), new UiAutomationService.PageObjectRequest("提交按钮", "#submit", "对象说明"));
        var steps = List.of(new UiAutomationStep("open", "NAVIGATE", "打开", null, null, null, "/", null, 10000),
                new UiAutomationStep("fill", "FILL", "填写", "#input", null, "${order}", null, null, 10000),
                new UiAutomationStep("click", "CLICK", "点击对象", null, object.id(), null, null, null, 10000),
                new UiAutomationStep("visible", "ASSERT_VISIBLE", "可见", "#result", null, null, null, null, 10000),
                new UiAutomationStep("text", "ASSERT_TEXT", "文本", "#result", null, null, null, "${order}", 10000),
                new UiAutomationStep("wait", "WAIT", "等待", null, null, null, null, null, 20),
                new UiAutomationStep("capture", "SCREENSHOT", "截图", null, null, null, null, null, 10000));
        var request = new UiAutomationService.ScenarioRequest(profile.id(), "订单场景", "第一版", "CONTINUE", Map.of("order", "A1"), steps);
        var scenario = automation.createScenario(f.actor(), f.project(), request);
        automation.updateScenario(f.actor(), f.project(), scenario.id(), new UiAutomationService.ScenarioRequest(profile.id(), request.name(), "第二版", request.failureStrategy(), request.variables(), steps));
        schedules.create(f.actor(), f.project(), new UiAutomationScheduleService.ScheduleInput(scenario.id(), planId, "每年检查", "0 0 0 1 1 ? 2099", "Asia/Shanghai", 120, 2, 10, true));
        return transfer.export(f.actor(), f.project());
    }
    ImportRequest request(Fixture f, Snapshot snapshot) { return new ImportRequest(UUID.randomUUID().toString(), f.actor().userId(), snapshot); }
    String plan(Fixture f) {
        return functional.createPlan(f.actor(), f.project(), new FunctionalTestExecutionService.PlanInput(null, "PLAN-UI", "关联计划", "DRAFT", "", List.of())).id();
    }
    @Test void preservesVersionsStepsReferencesAndDisabledSchedules() throws Exception {
        Fixture local = fixture(), target = fixture(); Snapshot original = source(local, null);
        var receipt = transfer.transfer(target.actor(), target.project(), request(target, original));
        assertEquals(1, receipt.profiles()); assertEquals(1, receipt.pageObjects()); assertEquals(1, receipt.scenarios());
        assertEquals(7, receipt.steps()); assertEquals(1, receipt.schedules());
        var imported = transfer.export(target.actor(), target.project());
        var profile = imported.profiles().getFirst(); var object = imported.pageObjects().getFirst(); var scenario = imported.scenarios().getFirst();
        assertNotEquals(original.profiles().getFirst().id(), profile.id()); assertFalse(profile.enabled()); assertEquals(2, profile.version());
        assertEquals(1280, profile.viewportWidth()); assertEquals(900, profile.viewportHeight());
        assertNotEquals(original.pageObjects().getFirst().id(), object.id());
        assertNotEquals(original.scenarios().getFirst().id(), scenario.id()); assertEquals(2, scenario.version());
        assertEquals(profile.id(), scenario.browserProfileId()); assertEquals(object.id(), scenario.steps().get(2).pageObjectId());
        assertEquals(original.scenarios().getFirst().variables(), scenario.variables()); assertEquals("CONTINUE", scenario.failureStrategy());
        assertEquals(original.scenarios().getFirst().steps().stream().map(UiAutomationStep::id).toList(), scenario.steps().stream().map(UiAutomationStep::id).toList());
        var timer = schedules.list(target.actor(), target.project()).getFirst();
        assertFalse(timer.enabled()); assertEquals(scenario.id(), timer.scenarioId()); assertEquals("Asia/Shanghai", timer.timezone());
        assertEquals(2, timer.maxRetryAttempts()); assertEquals(10, timer.retryBackoffSeconds()); assertNull(timer.lastExecutionId());
        assertFalse(quartz.checkExists(new JobKey(timer.id(), "testhub-ui-automation")));
        assertEquals(0, count("ui_automation_execution", target));
        assertThrows(ResponseStatusException.class, () -> automation.execute(target.actor(), target.project(), scenario.id(), 120, null));
        automation.updateProfile(target.actor(), target.project(), profile.id(), new UiAutomationService.ProfileRequest(profile.name(), profile.browser(), profile.baseUrl(), profile.viewportWidth(), profile.viewportHeight(), true));
        var execution = automation.execute(target.actor(), target.project(), scenario.id(), 120, null);
        assertEquals(2, execution.scenarioVersion());
        assertEquals(original, transfer.export(local.actor(), local.project()));
    }
    @Test void inactiveImportedLocalTargetsNeverGrantAuthorization() {
        Fixture local = fixture(), target = fixture(); Snapshot original = source(local, null);
        var p = original.profiles().getFirst();
        var unsupportedTarget = new Profile(p.id(), p.name(), "webkit", "http://127.0.0.1:18086/", p.viewportWidth(), p.viewportHeight(), true, p.version());
        var snapshot = new Snapshot(1, original.sourceProjectId(), List.of(unsupportedTarget), original.pageObjects(), original.scenarios(), original.schedules());
        transfer.transfer(target.actor(), target.project(), request(target, snapshot));
        var imported = transfer.export(target.actor(), target.project()).profiles().getFirst();
        assertFalse(imported.enabled()); assertEquals("webkit", imported.browser()); assertEquals(unsupportedTarget.baseUrl(), imported.baseUrl());
        assertThrows(ResponseStatusException.class, () -> automation.updateProfile(target.actor(), target.project(), imported.id(),
                new UiAutomationService.ProfileRequest(imported.name(), imported.browser(), imported.baseUrl(), 1280, 900, true)));
        assertEquals(0, count("ui_automation_execution", target));
    }
    @Test void requiresTargetFunctionalPlanCodeAndRemapsToItsOwnIdentity() {
        Fixture local = fixture(), target = fixture(); Snapshot original = source(local, plan(local));
        var request = request(target, original);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> transfer.transfer(target.actor(), target.project(), request)).getStatusCode().value());
        assertEquals(0, count("ui_automation_browser_profile", target));
        String targetPlan = plan(target); transfer.transfer(target.actor(), target.project(), request);
        var timer = schedules.list(target.actor(), target.project()).getFirst();
        assertEquals(targetPlan, timer.functionalPlanId()); assertFalse(timer.enabled());
    }
    @Test void idempotencyNameConflictsAndPayloadChangesDoNotOverwrite() {
        Fixture local = fixture(), target = fixture(); Snapshot original = source(local, null); var request = request(target, original);
        var receipt = transfer.transfer(target.actor(), target.project(), request);
        assertEquals(receipt, transfer.transfer(target.actor(), target.project(), request));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> transfer.transfer(target.actor(), target.project(), request(target, original))).getStatusCode().value());
        var p = original.profiles().getFirst(); var renamed = new Profile(p.id(), " " + p.name() + " ", p.browser(), p.baseUrl(), p.viewportWidth(), p.viewportHeight(), p.enabled(), p.version());
        var padded = new Snapshot(1, original.sourceProjectId(), List.of(renamed), original.pageObjects(), original.scenarios(), original.schedules());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> transfer.transfer(target.actor(), target.project(), request(target, padded))).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> transfer.transfer(target.actor(), target.project(),
                new ImportRequest(request.requestKey(), target.actor().userId(), padded))).getStatusCode().value());
        assertEquals(1, count("ui_automation_browser_profile", target)); assertEquals(1, count("ui_automation_transfer_receipt", target));
    }
    @Test void lateMalformedScheduleAndDanglingReferencesRollbackWholeBatch() {
        Fixture local = fixture(), target = fixture(); Snapshot original = source(local, null);
        var s = original.schedules().getFirst();
        var bad = new Schedule(s.id(), s.scenarioId(), s.name(), "invalid-cron", s.timezone(), s.timeoutSeconds(), s.maxRetryAttempts(), s.retryBackoffSeconds(), true, null);
        var snapshot = new Snapshot(1, original.sourceProjectId(), original.profiles(), original.pageObjects(), original.scenarios(), List.of(bad));
        assertThrows(ResponseStatusException.class, () -> transfer.transfer(target.actor(), target.project(), request(target, snapshot)));
        for (String table : List.of("ui_automation_browser_profile", "ui_automation_page_object", "ui_automation_scenario", "ui_automation_schedule", "ui_automation_transfer_receipt")) assertEquals(0, count(table, target));
        var dangling = new Snapshot(1, original.sourceProjectId(), original.profiles(), List.of(), original.scenarios(), original.schedules());
        assertThrows(ResponseStatusException.class, () -> transfer.transfer(target.actor(), target.project(), request(target, dangling)));
        assertEquals(0, count("ui_automation_transfer_receipt", target));
    }
    @Test void strictHttpProjectAndAccountChecks() throws Exception {
        Fixture local = fixture(), target = fixture(); Snapshot original = source(local, null);
        String url = "/api/v1/projects/" + target.project() + "/ui-automation/transfer";
        mvc.perform(get(url + "/capabilities").with(user(target.actor()))).andExpect(status().isOk());
        mvc.perform(post(url).with(user(local.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request(target, original)))).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user(target.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new ImportRequest(UUID.randomUUID().toString(), local.actor().userId(), original)))).andExpect(status().isConflict());
        var body = json.valueToTree(request(target, original)); ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("history", true);
        mvc.perform(post(url).with(user(target.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
        assertEquals(0, count("ui_automation_transfer_receipt", target));
    }
}
