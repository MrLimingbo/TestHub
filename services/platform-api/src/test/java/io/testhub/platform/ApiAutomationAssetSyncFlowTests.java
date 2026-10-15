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
import io.testhub.platform.storage.FileObjectService;
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
import org.springframework.mock.web.MockMultipartFile;
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
class ApiAutomationAssetSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry, directory, "automation-asset-sync-fixture");
        else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "automation-asset-sync-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired ApiAutomationAssetSyncService sync;
    @Autowired ApiAutomationReusableStepService reusable;
    @Autowired ApiAutomationReusableStepRepository reusableRepository;
    @Autowired ApiAutomationScenarioService scenarios;
    @Autowired ApiAutomationScenarioExpansionService expansion;
    @Autowired ApiAutomationDataSetService dataSets;
    @Autowired ApiAutomationDataSetRepository dataSetRepository;
    @Autowired ApiAutomationExecutionProfileService profiles;
    @Autowired FileObjectService files;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    record Fixture(PlatformUserPrincipal actor, String project) { }
    Fixture fixture() {
        String account = "automation-asset-sync-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test", "自动化同步测试", "Sync-fixture-2026!"));
        var context = access.context(actor);
        return new Fixture(actor, context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    List<AutomationStep> steps(String path) { return List.of(new AutomationStep("get", "REQUEST", "读取", Map.of("method", "GET", "url", "https://api.example.test/" + path))); }
    ApiAutomationAssetSyncService.Snapshot snapshot(Fixture f) { return sync.snapshot(f.actor(), f.project(), f.actor().userId()); }
    ApiAutomationAssetSyncService.Item item(String kind, String name, Object value) {
        return new ApiAutomationAssetSyncService.Item(kind, name, json.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
    }
    ApiAutomationAssetSyncService.Request request(Fixture f, ApiAutomationAssetSyncService.Item... items) {
        return new ApiAutomationAssetSyncService.Request(UUID.randomUUID().toString(), f.actor().userId(), snapshot(f).revision(),
                java.util.Arrays.stream(items).map(item -> new ApiAutomationAssetSyncService.Operation(UUID.randomUUID().toString(), item)).toList());
    }
    void apply(Fixture f, ApiAutomationAssetSyncService.Item... items) { sync.apply(f.actor(), f.project(), request(f, items)); }
    void seed(Fixture f) {
        var step = reusable.create(f.actor(), f.project(), new ApiAutomationReusableStepService.ReusableStepInput("读取订单", "v1", steps("old"), null));
        reusable.update(f.actor(), f.project(), step.id(), new ApiAutomationReusableStepService.ReusableStepInput("读取订单", "v2", steps("new"), 1));
        dataSets.create(f.actor(), f.project(), new ApiAutomationDataSetService.DataSetInput("订单数据", "JSON",
                "[{\"orderId\":\"1\"},{\"orderId\":\"2\"},{\"orderId\":\"3\"},{\"orderId\":\"4\"},{\"orderId\":\"5\"},{\"orderId\":\"6\"},{\"orderId\":\"7\"}]"));
        profiles.create(f.actor(), f.project(), new ApiAutomationExecutionProfileService.ProfileInput("测试配置", "test", Map.of("region", "qa"), null));
    }
    @Test void copiesCurrentAssetsAndFullRowsThenEditsReturnWithStableIds() {
        var source = fixture(); seed(source); var target = fixture();
        apply(target, snapshot(source).items().toArray(ApiAutomationAssetSyncService.Item[]::new));
        assertEquals(snapshot(source).items(), snapshot(target).items());
        assertNotEquals(snapshot(source).itemIds(), snapshot(target).itemIds());
        var targetReusable = reusable.list(target.actor(), target.project()).getFirst();
        assertEquals(1, targetReusable.version());
        var targetData = dataSets.list(target.actor(), target.project()).getFirst();
        assertEquals(7, dataSetRepository.findContent(target.project(), targetData.id()).orElseThrow().rows().size());
        var profile = profiles.list(target.actor(), target.project()).getFirst();
        profiles.update(target.actor(), target.project(), profile.id(), new ApiAutomationExecutionProfileService.ProfileInput(profile.name(), "test", Map.of("region", "web"), profile.version()));
        var changed = snapshot(target).items().stream().filter(item -> item.kind().equals("EXECUTION_PROFILE")).findFirst().orElseThrow();
        var oldIds = snapshot(source).itemIds(); apply(source, changed);
        assertEquals(oldIds, snapshot(source).itemIds());
        assertEquals(snapshot(source).items(), snapshot(target).items());
        int version = profiles.list(source.actor(), source.project()).getFirst().version();
        apply(source, changed);
        assertEquals(version, profiles.list(source.actor(), source.project()).getFirst().version());
    }
    @Test void reusableUpdateAppendsAndPinnedScenarioStillExpandsOriginalRevision() {
        var target = fixture();
        var old = reusable.create(target.actor(), target.project(), new ApiAutomationReusableStepService.ReusableStepInput("读取订单", "旧版", steps("old"), null));
        var scenario = scenarios.create(target.actor(), target.project(), new ApiAutomationScenarioService.ScenarioInput("保留引用", "", "test", "STOP",
                List.of(new AutomationStep("use", "REUSABLE_STEP", "固定旧版", Map.of("reusableStepId", old.id(), "reusableStepVersion", 1))), Map.of(), "", "", null));
        var before = reusableRepository.findRevisionSnapshot(target.project(), old.id(), 1).orElseThrow();
        var changed = item("REUSABLE_STEP", old.name(), new ApiAutomationAssetSyncService.Reusable("新版", steps("new")));
        var request = request(target, changed);
        var receipt = sync.apply(target.actor(), target.project(), request);
        assertEquals(receipt, sync.apply(target.actor(), target.project(), request));
        assertEquals(2, reusable.detail(target.actor(), target.project(), old.id()).version());
        assertEquals(before, reusableRepository.findRevisionSnapshot(target.project(), old.id(), 1).orElseThrow());
        assertEquals("https://api.example.test/old", expansion.expand(target.project(), scenario.id(), scenario.steps()).getFirst().config().get("url"));
        assertEquals(scenario, scenarios.detail(target.actor(), target.project(), scenario.id()));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM api_automation_execution WHERE project_id=:p").param("p", target.project()).query(Integer.class).single());
    }
    @Test void invalidEnvironmentAndLateFileFailureRollBackEntireBatch() {
        var f = fixture(); seed(f); var before = snapshot(f);
        var extra = item("REUSABLE_STEP", "新公共步骤", new ApiAutomationAssetSyncService.Reusable("", steps("extra")));
        var profile = item("EXECUTION_PROFILE", "缺失环境", new ApiAutomationAssetSyncService.Profile("missing", Map.of()));
        assertThrows(ResponseStatusException.class, () -> apply(f, extra, profile)); assertEquals(before, snapshot(f));
        var broken = item("DATA_SET", "缺失来源", new ApiAutomationAssetSyncService.DataSet("JSON", List.of("id"), List.of(Map.of("id", "1")),
                "/api/v1/projects/" + f.project() + "/files/" + "A".repeat(26) + "/download"));
        assertThrows(ResponseStatusException.class, () -> apply(f, extra, broken)); assertEquals(before, snapshot(f));
        var file = files.upload(f.actor(), f.project(), new MockMultipartFile("file", "large.txt", "text/plain", new byte[512 * 1024 + 1]));
        var largeFile = item("DATA_SET", "过大来源", new ApiAutomationAssetSyncService.DataSet("JSON", List.of("id"), List.of(Map.of("id", "1")),
                "/api/v1/projects/" + f.project() + "/files/" + file.id() + "/download"));
        assertThrows(ResponseStatusException.class, () -> apply(f, extra, largeFile));
        assertFalse(snapshot(f).items().stream().anyMatch(item -> item.name().equals("新公共步骤")));
    }
    @Test void sourceFileAssociationCanMapAndChangeWithoutTouchingExistingFile() {
        var f = fixture();
        var file = files.upload(f.actor(), f.project(), new MockMultipartFile("file", "data.json", "application/json", "[{\"id\":\"1\"}]".getBytes()));
        var data = item("DATA_SET", "来源数据", new ApiAutomationAssetSyncService.DataSet("JSON", List.of("id"), List.of(Map.of("id", "1")),
                "/api/v1/projects/" + f.project() + "/files/" + file.id() + "/download"));
        var req = request(f, data); var receipt = sync.apply(f.actor(), f.project(), req);
        var existing = dataSets.list(f.actor(), f.project()).getFirst();
        assertEquals(file.id(), existing.sourceFile().id());
        var changed = item("DATA_SET", data.name(), new ApiAutomationAssetSyncService.DataSet("CSV", List.of("id"), List.of(Map.of("id", "2")), null));
        apply(f, changed);
        assertEquals(existing.id(), dataSets.list(f.actor(), f.project()).getFirst().id());
        assertNull(dataSets.list(f.actor(), f.project()).getFirst().sourceFile());
        assertEquals(file.id(), files.metadata(f.actor(), f.project(), file.id()).id());
        assertEquals(receipt, sync.apply(f.actor(), f.project(), req));
    }
    @Test void staleRevisionWrongActorExtraFieldsAndCsrfFailWithoutWrites() throws Exception {
        var f = fixture(); var other = fixture();
        var item = item("REUSABLE_STEP", "测试步骤", new ApiAutomationAssetSyncService.Reusable("", steps("old")));
        var stale = request(f, item);
        apply(f, item("REUSABLE_STEP", "独立步骤", new ApiAutomationAssetSyncService.Reusable("", steps("new"))));
        assertThrows(ResponseStatusException.class, () -> sync.apply(f.actor(), f.project(), stale));
        assertThrows(ResponseStatusException.class, () -> sync.snapshot(f.actor(), f.project(), other.actor().userId()));
        var malformed = new java.util.HashMap<>(item.definition()); malformed.put("version", 99);
        assertThrows(ResponseStatusException.class, () -> apply(f, new ApiAutomationAssetSyncService.Item(item.kind(), item.name(), malformed)));
        String url = "/api/v1/projects/" + f.project() + "/api-automation/assets/sync";
        byte[] body = json.writeValueAsBytes(request(f, item));
        mvc.perform(post(url).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(get(url).param("expectedUserId", other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());
    }
}
