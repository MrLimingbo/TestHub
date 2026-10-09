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
import io.testhub.platform.apiautomation.*;
import io.testhub.platform.apiautomation.ApiAutomationTransferService.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
class ApiAutomationTransferFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry, directory, "api-automation-transfer-fixture");
        else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "api-automation-transfer-fixture");
        }
    }

    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired ApiAutomationTransferService transfer;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record Fixture(PlatformUserPrincipal actor, String project) { }
    Fixture fixture() {
        String account = "automation-transfer-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test",
                "自动化迁移测试", "Transfer-fixture-2026!"));
        var context = access.context(actor);
        return new Fixture(actor, context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    String id() { return UlidCreator.getUlid().toString(); }
    int count(String table, Fixture target) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id=:project")
                .param("project", target.project()).query(Integer.class).single();
    }
    Snapshot snapshot() {
        String reusable = id(), child = id(), parent = id(), dataSet = id(), profile = id();
        var requestStep = new AutomationStep("request-1", "REQUEST", "读取订单",
                Map.of("method", "GET", "url", "https://api.example.test/orders/1"));
        var reusableStep = new Reusable(reusable, List.of(
                new ApiAutomationReusableStepRevisionSnapshot("公共步骤", "v1", List.of(requestStep)),
                new ApiAutomationReusableStepRevisionSnapshot("公共步骤", "v2", List.of(requestStep))));
        var childRevision = new ApiAutomationScenarioRevisionSnapshot("子场景", "", "test", "STOP",
                List.of(new AutomationStep("request-child", "REQUEST", "子请求", requestStep.config())), Map.of(), "", "");
        var parentRevision = new ApiAutomationScenarioRevisionSnapshot("父场景", "", "test", "STOP",
                List.of(new AutomationStep("use-reusable", "REUSABLE_STEP", "复用", Map.of("reusableStepId", reusable, "reusableStepVersion", 1)),
                        new AutomationStep("use-child", "SUB_SCENARIO", "子流程", Map.of("scenarioId", child, "scenarioVersion", 1))),
                Map.of("baseUrl", "https://api.example.test"), "", "");
        return new Snapshot(1, id(), List.of(reusableStep), List.of(new Scenario(child, List.of(childRevision)),
                new Scenario(parent, List.of(parentRevision, parentRevision))),
                List.of(new DataSet(dataSet, "订单数据", "JSON", List.of("orderId"), List.of(Map.of("orderId", "1")), null)),
                List.of(new Profile(profile, "运行配置", "test", Map.of("token", "fixture-private-value"), 3)),
                List.of(new Schedule(id(), parent, profile, "每日回归", "0 0 0 1 1 ? 2099", "Asia/Shanghai", 300, 3, true)));
    }
    ImportRequest request(Fixture target, Snapshot source) {
        return new ImportRequest(UUID.randomUUID().toString(), target.actor().userId(), source);
    }

    @Test void importsCompleteRevisionsAndReferencesWithDisabledScheduleAndIdempotentReceipt() {
        Fixture target = fixture(); Snapshot source = snapshot(); ImportRequest request = request(target, source);
        Receipt receipt = transfer.transfer(target.actor(), target.project(), request);
        assertEquals(1, receipt.reusableSteps()); assertEquals(2, receipt.scenarios());
        assertEquals(1, receipt.dataSets()); assertEquals(1, receipt.executionProfiles()); assertEquals(1, receipt.schedules());
        Snapshot imported = transfer.export(target.actor(), target.project());
        assertEquals(2, imported.reusableSteps().getFirst().revisions().size());
        assertEquals(2, imported.scenarios().stream().filter(item -> item.revisions().size() == 2).findFirst().orElseThrow().revisions().size());
        assertEquals(3, imported.executionProfiles().getFirst().version());
        assertEquals("fixture-private-value", imported.executionProfiles().getFirst().variables().get("token"));
        assertFalse(imported.schedules().getFirst().wasEnabled());
        assertEquals(imported.executionProfiles().getFirst().id(), imported.schedules().getFirst().executionProfileId());
        var parent = imported.scenarios().stream().filter(item -> item.revisions().size() == 2).findFirst().orElseThrow();
        assertEquals(imported.reusableSteps().getFirst().id(), parent.revisions().getFirst().steps().getFirst().config().get("reusableStepId"));
        assertNotEquals(source.scenarios().getFirst().id(), parent.revisions().getFirst().steps().get(1).config().get("scenarioId"));
        assertEquals(receipt, transfer.transfer(target.actor(), target.project(), request));
        assertEquals(2, count("api_automation_scenario", target));
        var changed = new Snapshot(source.version(), source.sourceProjectId(), source.reusableSteps(), source.scenarios(),
                List.of(), source.executionProfiles(), source.schedules());
        var reusedKey = new ImportRequest(request.requestKey(), request.expectedUserId(), changed);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> transfer.transfer(target.actor(), target.project(), reusedKey)).getStatusCode().value());
    }

    @Test void duplicateScheduleNameRollsBackEveryAssetAndReceipt() {
        Fixture target = fixture(); Snapshot source = snapshot();
        var schedules = List.of(source.schedules().getFirst(), new Schedule(id(), source.schedules().getFirst().scenarioId(),
                source.schedules().getFirst().executionProfileId(), "每日回归", "0 0 0 1 1 ? 2099", "Asia/Shanghai", 300, 3, false));
        var invalid = new Snapshot(1, source.sourceProjectId(), source.reusableSteps(), source.scenarios(),
                source.dataSets(), source.executionProfiles(), schedules);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> transfer.transfer(target.actor(), target.project(), request(target, invalid))).getStatusCode().value());
        for (String table : List.of("api_automation_scenario", "api_automation_reusable_step", "api_automation_data_set",
                "api_automation_execution_profile", "api_automation_schedule", "api_automation_transfer_receipt"))
            assertEquals(0, count(table, target), table);
    }

    @Test void rejectsWrongActorAndForeignProjectOverHttp() throws Exception {
        Fixture owner = fixture(), outsider = fixture(); Snapshot source = snapshot();
        String url = "/api/v1/projects/" + owner.project() + "/api-automation/transfer";
        var body = json.writeValueAsBytes(request(owner, source));
        mvc.perform(post(url).with(user(outsider.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(get(url + "/capabilities").with(user(owner.actor()))).andExpect(status().isOk());
        mvc.perform(post(url).with(user(owner.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new ImportRequest(UUID.randomUUID().toString(), outsider.actor().userId(), source))))
                .andExpect(status().isConflict());
        assertEquals(0, count("api_automation_transfer_receipt", owner));
    }

    @Test void copiesAndVerifiesDataSetSourceFileWithinTheSameTransaction() throws Exception {
        Fixture target = fixture(); Snapshot source = snapshot();
        byte[] content = "[{\"orderId\":\"1\"}]".getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var file = new SourceFile(id(), "orders.json", "application/json", content.length, hash, content);
        var original = source.dataSets().getFirst();
        var dataSet = new DataSet(original.id(), original.name(), original.sourceFormat(), original.columns(), original.rows(), file);
        var withFile = new Snapshot(1, source.sourceProjectId(), source.reusableSteps(), source.scenarios(),
                List.of(dataSet), source.executionProfiles(), source.schedules());
        var receipt = transfer.transfer(target.actor(), target.project(), request(target, withFile));
        assertEquals(1, receipt.dataSetSourceFiles());
        var imported = transfer.export(target.actor(), target.project()).dataSets().getFirst();
        assertNotEquals(file.id(), imported.sourceFile().id());
        assertArrayEquals(content, imported.sourceFile().content());
        assertEquals(1, count("file_object", target));

        Fixture rejected = fixture();
        var corrupt = new DataSet(original.id(), original.name(), original.sourceFormat(), original.columns(), original.rows(),
                new SourceFile(file.id(), file.name(), file.type(), file.size(), "0".repeat(64), content));
        var bad = new Snapshot(1, source.sourceProjectId(), source.reusableSteps(), source.scenarios(),
                List.of(corrupt), source.executionProfiles(), source.schedules());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> transfer.transfer(rejected.actor(), rejected.project(), request(rejected, bad))).getStatusCode().value());
        assertEquals(0, count("file_object", rejected));
    }
}
