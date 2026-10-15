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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class ApiAutomationScenarioSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry, directory, "scenario-sync-fixture");
        else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa"); registry.add("spring.datasource.password", () -> "scenario-sync-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired ApiAutomationScenarioSyncService sync;
    @Autowired ApiAutomationScenarioService scenarios;
    @Autowired ApiAutomationScenarioRepository scenarioRepository;
    @Autowired ApiAutomationReusableStepService reusable;
    @Autowired ApiAutomationReusableStepRepository reusableRepository;
    @Autowired ApiAutomationScenarioExpansionService expansions;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    record Fixture(PlatformUserPrincipal actor, String project) { }
    Fixture fixture() {
        String account = "scenario-sync-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test", "场景同步测试", "Sync-fixture-2026!"));
        var context = access.context(actor); return new Fixture(actor, context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    List<AutomationStep> primitive(String url) { return List.of(new AutomationStep("get", "REQUEST", "读取", Map.of("method", "GET", "url", "https://api.example.test/" + url))); }
    ApiAutomationScenarioService.ScenarioInput input(String name, String description, List<AutomationStep> steps, Integer version) {
        return new ApiAutomationScenarioService.ScenarioInput(name, description, "test", "STOP", steps, Map.of("x", "one"), "", "", version);
    }
    ApiAutomationScenarioDetail scene(Fixture f, String name, String description, List<AutomationStep> steps) { return scenarios.create(f.actor(), f.project(), input(name, description, steps, null)); }
    ApiAutomationReusableStepDetail reuse(Fixture f, String description, String url) { return reusable.create(f.actor(), f.project(), new ApiAutomationReusableStepService.ReusableStepInput("公共", description, primitive(url), null)); }
    ApiAutomationScenarioSyncProjection.Snapshot snapshot(Fixture f) { return sync.snapshot(f.actor(), f.project(), f.actor().userId()); }
    ApiAutomationScenarioSyncProjection.Item item(Fixture f, String name) { return snapshot(f).items().stream().filter(item -> item.name().equals(name)).findFirst().orElseThrow(); }
    ApiAutomationScenarioSyncService.Request request(Fixture f, ApiAutomationScenarioSyncProjection.Item... items) {
        return new ApiAutomationScenarioSyncService.Request(UUID.randomUUID().toString(), f.actor().userId(), snapshot(f).revision(),
                java.util.Arrays.stream(items).map(item -> new ApiAutomationScenarioSyncService.Operation(UUID.randomUUID().toString(), item)).toList());
    }
    void apply(Fixture f, ApiAutomationScenarioSyncProjection.Item... items) { sync.apply(f.actor(), f.project(), request(f, items)); }
    record Source(ApiAutomationReusableStepDetail reusable, ApiAutomationScenarioDetail child, ApiAutomationScenarioDetail parent) { }
    Source seed(Fixture f) {
        var publicStep = reuse(f, "公共旧", "old-public");
        reusable.update(f.actor(), f.project(), publicStep.id(), new ApiAutomationReusableStepService.ReusableStepInput("公共", "公共最新", primitive("new-public"), 1));
        var child = scene(f, "子场景", "子旧", primitive("old-child"));
        scenarios.update(f.actor(), f.project(), child.id(), input(child.name(), "子最新", primitive("new-child"), 1));
        var parent = scene(f, "父场景", "父当前", List.of(
                new AutomationStep("reuse", "REUSABLE_STEP", "公共旧版", Map.of("reusableStepId", publicStep.id(), "reusableStepVersion", 1)),
                new AutomationStep("child", "SUB_SCENARIO", "子旧版", Map.of("scenarioId", child.id(), "scenarioVersion", 1))));
        return new Source(publicStep, child, parent);
    }
    @Test void importsMissingHistoryAndRestoresExistingHeadsWithoutChangingOldPins() {
        var source = fixture(); var seed = seed(source); var target = fixture();
        var targetReuse = reuse(target, "目标公共当前", "target-public");
        var targetChild = scene(target, "子场景", "目标子当前", primitive("target-child"));
        var existingParent = scene(target, "已有固定引用", "", List.of(
                new AutomationStep("public", "REUSABLE_STEP", "旧公共", Map.of("reusableStepId", targetReuse.id(), "reusableStepVersion", 1)),
                new AutomationStep("child", "SUB_SCENARIO", "旧子", Map.of("scenarioId", targetChild.id(), "scenarioVersion", 1))));
        var oldPublic = reusableRepository.findRevisionSnapshot(target.project(), targetReuse.id(), 1).orElseThrow();
        var oldChild = scenarioRepository.findRevisionSnapshot(target.project(), targetChild.id(), 1).orElseThrow();
        var request = request(target, item(source, seed.parent().name()));
        var receipt = sync.apply(target.actor(), target.project(), request);
        var copied = scenarios.list(target.actor(), target.project()).stream().filter(row -> row.name().equals(seed.parent().name())).findFirst().orElseThrow();
        var detail = scenarios.detail(target.actor(), target.project(), copied.id());
        assertEquals(2, detail.steps().getFirst().config().get("reusableStepVersion"));
        assertEquals(2, detail.steps().getLast().config().get("scenarioVersion"));
        assertNotEquals(seed.reusable().id(), detail.steps().getFirst().config().get("reusableStepId"));
        var expanded = expansions.expand(target.project(), detail.id(), detail.steps());
        assertEquals("https://api.example.test/old-public", expanded.getFirst().config().get("url"));
        assertEquals("https://api.example.test/old-child", expanded.getLast().config().get("url"));
        assertEquals("目标公共当前", reusable.detail(target.actor(), target.project(), targetReuse.id()).description());
        assertEquals("目标子当前", scenarios.detail(target.actor(), target.project(), targetChild.id()).description());
        assertEquals(oldPublic, reusableRepository.findRevisionSnapshot(target.project(), targetReuse.id(), 1).orElseThrow());
        assertEquals(oldChild, scenarioRepository.findRevisionSnapshot(target.project(), targetChild.id(), 1).orElseThrow());
        assertEquals(existingParent, scenarios.detail(target.actor(), target.project(), existingParent.id()));
        var beforeReplay = snapshot(target); assertEquals(receipt, sync.apply(target.actor(), target.project(), request)); assertEquals(beforeReplay, snapshot(target));
        assertEquals(item(source, seed.parent().name()), item(target, seed.parent().name()));
    }
    @Test void copiesParentBeforeMissingChildAndAllCommittedVersionSlotsHaveRealContent() {
        var source = fixture(); var seed = seed(source); var target = fixture();
        reuse(target, "公共最新", "new-public");
        apply(target, item(source, seed.parent().name()), item(source, seed.child().name()));
        assertEquals(snapshot(source).items(), snapshot(target).items());
        var child = scenarios.list(target.actor(), target.project()).stream().filter(row -> row.name().equals("子场景")).findFirst().orElseThrow();
        assertEquals(2, child.version());
        assertEquals("子旧", scenarioRepository.findRevisionSnapshot(target.project(), child.id(), 1).orElseThrow().description());
        assertEquals("子最新", scenarioRepository.findRevisionSnapshot(target.project(), child.id(), 2).orElseThrow().description());
        for (var row : scenarios.list(target.actor(), target.project())) for (int version = 1; version <= row.version(); version++)
            assertTrue(scenarioRepository.findRevisionSnapshot(target.project(), row.id(), version).isPresent());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM api_automation_execution WHERE project_id=:p").param("p", target.project()).query(Integer.class).single());
    }
    @Test void mapsExistingMatchingContentAtDifferentVersionNumbersWithoutAppendingToSources() {
        var source = fixture(); seed(source); var target = fixture();
        var publicStep = reuse(target, "其他", "other");
        reusable.update(target.actor(), target.project(), publicStep.id(), new ApiAutomationReusableStepService.ReusableStepInput("公共", "公共旧", primitive("old-public"), 1));
        reusable.update(target.actor(), target.project(), publicStep.id(), new ApiAutomationReusableStepService.ReusableStepInput("公共", "其他当前", primitive("other"), 2));
        var child = scene(target, "子场景", "其他", primitive("other"));
        scenarios.update(target.actor(), target.project(), child.id(), input("子场景", "子旧", primitive("old-child"), 1));
        scenarios.update(target.actor(), target.project(), child.id(), input("子场景", "其他当前", primitive("other"), 2));
        apply(target, item(source, "父场景"));
        assertEquals(3, reusable.detail(target.actor(), target.project(), publicStep.id()).version());
        assertEquals(3, scenarios.detail(target.actor(), target.project(), child.id()).version());
        var parent = scenarios.list(target.actor(), target.project()).stream().filter(row -> row.name().equals("父场景")).findFirst().orElseThrow();
        var detail = scenarios.detail(target.actor(), target.project(), parent.id());
        assertEquals(2, detail.steps().getFirst().config().get("reusableStepVersion"));
        assertEquals(2, detail.steps().getLast().config().get("scenarioVersion"));
    }
    @Test void legalMutualCurrentReferencesMapToPrimitiveHistoricalVersionsInOneBatch() {
        var source = fixture(); var target = fixture();
        var a = scene(source, "A", "A旧", primitive("a-old")); var b = scene(source, "B", "B旧", primitive("b-old"));
        scenarios.update(source.actor(), source.project(), a.id(), input("A", "A新", List.of(new AutomationStep("b", "SUB_SCENARIO", "引用B旧", Map.of("scenarioId", b.id(), "scenarioVersion", 1))), 1));
        scenarios.update(source.actor(), source.project(), b.id(), input("B", "B新", List.of(new AutomationStep("a", "SUB_SCENARIO", "引用A旧", Map.of("scenarioId", a.id(), "scenarioVersion", 1))), 1));
        apply(target, item(source, "A"), item(source, "B"));
        assertEquals(snapshot(source).items(), snapshot(target).items());
        for (var row : scenarios.list(target.actor(), target.project())) {
            assertEquals(2, row.version()); var detail = scenarios.detail(target.actor(), target.project(), row.id());
            assertEquals("https://api.example.test/" + (row.name().equals("A") ? "b-old" : "a-old"), expansions.expand(target.project(), row.id(), detail.steps()).getFirst().config().get("url"));
        }
    }
    @Test void lateInvalidPrimitiveRollsBackReservedRowsImportedHistoryAndHeadVersions() {
        var source = fixture(); seed(source); var target = fixture(); reuse(target, "当前", "current"); scene(target, "子场景", "当前", primitive("current"));
        var original = item(source, "父场景"); var d = json.convertValue(original.definition(), ApiAutomationScenarioSyncProjection.Definition.class);
        var steps = new java.util.ArrayList<>(d.steps()); steps.add(new AutomationStep("invalid", "REQUEST", "错误方法", Map.of("method", "BREW", "url", "https://api.example.test")));
        var changed = new ApiAutomationScenarioSyncProjection.Item(original.name(), json.convertValue(new ApiAutomationScenarioSyncProjection.Definition(d.description(), d.environmentCode(), d.failureStrategy(), steps, d.variables(), d.preRequestScript(), d.postResponseScript()), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        var before = snapshot(target); assertThrows(ResponseStatusException.class, () -> apply(target, changed)); assertEquals(before, snapshot(target));
        assertFalse(scenarios.list(target.actor(), target.project()).stream().anyMatch(row -> row.name().equals("父场景")));
    }
    @Test void missingSourceRevisionBlocksOnlyRelatedScenarioInProjection() {
        var source = fixture(); var seed = seed(source); scene(source, "独立", "", primitive("independent"));
        jdbc.sql("DELETE FROM api_automation_reusable_step_revision WHERE project_id=:p AND reusable_step_id=:id AND version=1")
                .param("p", source.project()).param("id", seed.reusable().id()).update();
        var result = snapshot(source);
        assertTrue(result.blockedNames().containsKey("父场景")); assertTrue(result.itemIds().containsKey("父场景"));
        assertFalse(result.items().stream().anyMatch(item -> item.name().equals("父场景")));
        assertTrue(result.items().stream().anyMatch(item -> item.name().equals("独立")));
    }
    @Test void historicalNameSurvivesWhileCurrentReferenceSourceNameIsMatched() {
        var source = fixture(); var target = fixture();
        var old = reusable.create(source.actor(), source.project(), new ApiAutomationReusableStepService.ReusableStepInput("旧名", "旧", primitive("old"), null));
        reusable.update(source.actor(), source.project(), old.id(), new ApiAutomationReusableStepService.ReusableStepInput("新名", "新", primitive("new"), 1));
        scene(source, "父", "", List.of(new AutomationStep("use", "REUSABLE_STEP", "旧引用", Map.of("reusableStepId", old.id(), "reusableStepVersion", 1))));
        var latest = reusable.create(target.actor(), target.project(), new ApiAutomationReusableStepService.ReusableStepInput("新名", "新", primitive("new"), null));
        apply(target, item(source, "父"));
        assertEquals("新名", reusable.detail(target.actor(), target.project(), latest.id()).name());
        assertEquals("旧名", reusableRepository.findRevisionSnapshot(target.project(), latest.id(), 2).orElseThrow().name());
        assertEquals(item(source, "父"), item(target, "父"));
    }
    @Test void actorRevisionPermissionsCsrfAndChangedRequestKeyAreEnforced() throws Exception {
        var source = fixture(); scene(source, "简单", "", primitive("one")); var target = fixture(); var other = fixture();
        var request = request(target, item(source, "简单")); String url = "/api/v1/projects/" + target.project() + "/api-automation/scenarios/sync";
        byte[] body = json.writeValueAsBytes(request);
        mvc.perform(post(url).with(user(target.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user(target.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        var receipt = sync.apply(target.actor(), target.project(), request);
        jdbc.sql("UPDATE project_environment SET enabled=FALSE WHERE project_id=:p AND environment_code='test'").param("p", target.project()).update();
        assertEquals(receipt, sync.apply(target.actor(), target.project(), request));
        mvc.perform(get(url).param("expectedUserId", other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());
        assertThrows(ResponseStatusException.class, () -> sync.snapshot(target.actor(), target.project(), other.actor().userId()));
        assertThrows(ResponseStatusException.class, () -> sync.apply(target.actor(), target.project(), new ApiAutomationScenarioSyncService.Request(request.requestKey(), request.expectedUserId(), request.expectedRevision(),
                List.of(new ApiAutomationScenarioSyncService.Operation(UUID.randomUUID().toString(), item(source, "简单"))))));
    }
}
