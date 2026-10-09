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
import io.testhub.platform.performance.*;
import io.testhub.platform.performance.PerformanceTransferService.*;
import java.math.BigDecimal;
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
class PerformanceTransferFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry, directory, "performance-transfer-fixture");
        else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "performance-transfer-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired PerformanceTestPlanService plans;
    @Autowired PerformanceTransferService transfer;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    record Fixture(PlatformUserPrincipal actor, String project) { }
    Fixture fixture() {
        String account = "performance-transfer-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test",
                "性能迁移测试", "Transfer-fixture-2026!"));
        var context = access.context(actor);
        return new Fixture(actor, context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    int count(String table, Fixture fixture) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id=:project")
                .param("project", fixture.project()).query(Integer.class).single();
    }
    PerformanceTestPlanService.ThreadGroupInput group(String name, String model, String url) {
        boolean stepped = "STEPPED_RAMP".equals(model);
        return new PerformanceTestPlanService.ThreadGroupInput(name, url, model, 2, "RAMP_UP".equals(model) ? 2 : 0,
                stepped ? 2 : null, stepped ? 1 : null, 10, 4, 1000, new BigDecimal("0.00"), "POST",
                Map.of("Content-Type", "application/json", "X-Row", "${order}"), Map.of("qty", "${qty}"),
                "{\"order\":\"${order}\",\"qty\":${qty}}", 200, "ok",
                "order,qty\nA1,1\nB2,2", stepped ? null : 2);
    }
    Snapshot source(Fixture fixture) {
        var groups = List.of(group("恒定", "STEADY", "http://127.0.0.1:18085/orders/${order}"),
                group("线性", "RAMP_UP", "http://127.0.0.1:18085/orders/${order}"),
                group("阶梯", "STEPPED_RAMP", "http://127.0.0.1:18085/orders/${order}"));
        var created = plans.create(fixture.actor(), fixture.project(),
                new PerformanceTestPlanService.PlanInput("订单性能", "计划说明", "READY", groups, null));
        plans.update(fixture.actor(), fixture.project(), created.id(),
                new PerformanceTestPlanService.PlanInput("订单性能", "第二版", "READY", groups, 1));
        return transfer.export(fixture.actor(), fixture.project());
    }
    ImportRequest request(Fixture fixture, Snapshot snapshot) {
        return new ImportRequest(UUID.randomUUID().toString(), fixture.actor().userId(), snapshot);
    }
    @Test void copiesAllLoadModelsAndInlineCsvAsEditableDraftWithoutExecution() {
        Fixture local = fixture(), target = fixture(); Snapshot original = source(local);
        var receipt = transfer.transfer(target.actor(), target.project(), request(target, original));
        assertEquals(1, receipt.plans()); assertEquals(3, receipt.threadGroups()); assertEquals(3, receipt.csvDataSets());
        var imported = transfer.export(target.actor(), target.project()).plans().getFirst();
        assertNotEquals(original.plans().getFirst().id(), imported.id());
        assertEquals("DRAFT", imported.status()); assertEquals(2, imported.version());
        assertEquals(original.plans().getFirst().threadGroups(), imported.threadGroups());
        assertEquals(original, transfer.export(local.actor(), local.project()));
        assertEquals(0, count("performance_test_execution", target));
        plans.update(target.actor(), target.project(), imported.id(), new PerformanceTestPlanService.PlanInput(
                imported.name(), "目标端修改", "READY", imported.threadGroups(), imported.version()));
        assertEquals(3, plans.detail(target.actor(), target.project(), imported.id()).version());
    }
    @Test void repeatedRequestReturnsSameReceiptAndNameConflictDoesNotOverwrite() {
        Fixture local = fixture(), target = fixture(); Snapshot source = source(local); ImportRequest request = request(target, source);
        var receipt = transfer.transfer(target.actor(), target.project(), request);
        assertEquals(receipt, transfer.transfer(target.actor(), target.project(), request));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> transfer.transfer(target.actor(), target.project(), request(target, source))).getStatusCode().value());
        Plan item = source.plans().getFirst();
        var paddedName = new Snapshot(1, source.sourceProjectId(), List.of(new Plan(item.id(), " " + item.name() + " ",
                item.description(), item.status(), item.version(), item.threadGroups())));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> transfer.transfer(target.actor(), target.project(), request(target, paddedName))).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> transfer.transfer(target.actor(), target.project(),
                new ImportRequest(request.requestKey(), target.actor().userId(), new Snapshot(1, source.sourceProjectId(), List.of()))))
                .getStatusCode().value());
        assertEquals(1, count("performance_test_plan", target)); assertEquals(1, count("performance_transfer_receipt", target));
    }
    @Test void invalidSecondPlanRollsBackEarlierPlanAndReceipt() {
        Fixture local = fixture(), target = fixture(); Snapshot original = source(local);
        var badPlan = new Plan(UlidCreator.getUlid().toString(), "非法目标", "", "READY", 1,
                List.of(group("非法组", "STEADY", "file:///tmp/fixture")));
        var bad = new Snapshot(1, original.sourceProjectId(), List.of(original.plans().getFirst(), badPlan));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> transfer.transfer(target.actor(), target.project(), request(target, bad))).getStatusCode().value());
        assertEquals(0, count("performance_test_plan", target));
        assertEquals(0, count("performance_test_thread_group", target));
        assertEquals(0, count("performance_transfer_receipt", target));
    }
    @Test void httpEnforcesProjectAccountAndStrictPayloadScope() throws Exception {
        Fixture local = fixture(), target = fixture(); Snapshot source = source(local);
        String url = "/api/v1/projects/" + target.project() + "/performance/transfer";
        mvc.perform(get(url + "/capabilities").with(user(target.actor()))).andExpect(status().isOk());
        mvc.perform(post(url).with(user(local.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request(target, source)))).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user(target.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new ImportRequest(UUID.randomUUID().toString(), local.actor().userId(), source))))
                .andExpect(status().isConflict());
        var body = json.valueToTree(request(target, source)); ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("history", true);
        mvc.perform(post(url).with(user(target.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
        assertEquals(0, count("performance_transfer_receipt", target));
    }
}
