package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.performance.*;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
class PerformanceHistorySyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry, directory, "history-sync-fixture");
        else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa"); registry.add("spring.datasource.password", () -> "history-sync-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired PerformanceHistorySyncService sync;
    @Autowired io.testhub.platform.execution.UnifiedExecutionService unified;
    @Autowired PerformanceTestPlanService plans;
    @Autowired PerformanceTestExecutionService executions;
    @Autowired PerformanceTestExecutionRepository repository;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    record Fixture(PlatformUserPrincipal actor, String project) { }
    Fixture fixture() {
        String account = "history-sync-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test", "历史同步测试", "History-fixture-2026!"));
        var context = access.context(actor); return new Fixture(actor, context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    PerformanceTestExecution seed(Fixture f, boolean terminal) {
        var group = new PerformanceTestPlanService.ThreadGroupInput("指标组", "https://perf.example.test/private-fixture-url", "STEADY", 1, 0, null, null, 10, null, 500, new java.math.BigDecimal("1.25"), "GET", Map.of("X-Private", "fixture-withheld-value"), Map.of(), "", 200, "", "", null);
        var scene = plans.create(f.actor(), f.project(), new PerformanceTestPlanService.PlanInput("执行时名称", "", "READY", List.of(group), null));
        var e = executions.create(f.actor(), f.project(), scene.id(), 60);
        if (terminal) repository.applyRunnerEvent(new PerformanceRunnerProtocol.PerformanceRunnerEvent(e.taskId(), e.id(), "fixture-runner", "SUCCEEDED", "EXECUTION_SUCCEEDED", "fixture private log", Instant.now().truncatedTo(ChronoUnit.MILLIS)), "SUCCEEDED");
        return executions.detail(f.actor(), f.project(), e.id());
    }
    PerformanceHistorySyncService.Inventory inventory(Fixture f) { return sync.inventory(f.actor(), f.project(), f.actor().userId()); }
    PerformanceHistorySyncService.Item item(Fixture f, PerformanceTestExecution e) { return sync.read(f.actor(), f.project(), f.actor().userId(), f.project() + "." + e.id()); }
    PerformanceHistorySyncService.Request request(Fixture f, PerformanceHistorySyncService.Item... items) { return new PerformanceHistorySyncService.Request(UUID.randomUUID().toString(), f.actor().userId(), inventory(f).revision(), List.of(items)); }
    int count(String table, Fixture f) { return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id=:p").param("p", f.project()).query(Integer.class).single(); }
    PerformanceHistorySyncService.Item changed(PerformanceHistorySyncService.Item item, String name, String revision) {
        var tree = json.valueToTree(item.report()); ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("planName", name);
        var p = json.convertValue(tree, PerformanceHistorySyncService.Payload.class);
        return new PerformanceHistorySyncService.Item(item.key(), revision, sync.hash(sync.encoded(p)), p);
    }
    @Test void copiesSafeTerminalReportWithoutCurrentPlanOrRunnerSideEffectsAndPreservesOrigin() {
        var source = fixture(); var target = fixture(); var e = seed(source, true); var item = item(source, e);
        assertEquals(1, inventory(source).entries().size());
        String payload = new String(sync.encoded(item), java.nio.charset.StandardCharsets.UTF_8);
        for (var hidden : List.of("private-fixture-url", "fixture-withheld-value", "private log", "manifestRef", "requestedBy", "variableBindings", "withheldSecretNames", "runnerId")) assertFalse(payload.contains(hidden));
        var request = request(target, item); var receipt = sync.apply(target.actor(), target.project(), request);
        assertEquals(item, sync.read(target.actor(), target.project(), target.actor().userId(), item.key()));
        assertFalse(inventory(target).entries().getFirst().nativeRecord());
        var row = unified.list(target.actor(), target.project()).getFirst();
        assertEquals("PERFORMANCE_HISTORY", row.module()); assertEquals(item.key(), row.id()); assertEquals(e.requestedAt(), row.createdAt());
        assertEquals(1, unified.list(source.actor(), source.project()).size());
        assertEquals(e.planVersion(), item.report().planVersion()); assertEquals(e.requestedAt(), item.report().requestedAt());
        assertEquals(0, count("performance_test_execution", target)); assertTrue(plans.list(target.actor(), target.project()).isEmpty());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM performance_runner_outbox WHERE execution_id IN (SELECT id FROM performance_test_execution WHERE project_id=:p)").param("p", target.project()).query(Integer.class).single());
        assertEquals(receipt, sync.apply(target.actor(), target.project(), request)); assertEquals(1, count("performance_history_archive", target));
        assertEquals(e, executions.detail(source.actor(), source.project(), e.id()));
    }
    @Test void ignoresActiveTasksAndIncludesMoreThanLegacyHundredWithoutTruncation() {
        var f = fixture(); var active = seed(f, false);
        assertTrue(inventory(f).entries().isEmpty());
        var plan = plans.detail(f.actor(), f.project(), active.planId());
        for (int n = 0; n < 101; n++) {
            String id = com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();
            String task = com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();
            repository.insertExecution(id, task, active.organizationId(), f.project(), plan, "fixture", f.actor().userId(), active.requestedAt(), active.deadlineAt());
            repository.applyRunnerEvent(new PerformanceRunnerProtocol.PerformanceRunnerEvent(task, id, "fixture-runner", "CANCELLED", "CANCELLED_BEFORE_DISPATCH", "", Instant.now().truncatedTo(ChronoUnit.MILLIS)), "CANCELLED");
        }
        assertEquals(101, inventory(f).entries().size());
        assertEquals(100, executions.list(f.actor(), f.project()).size());
    }
    @Test void lateMetricsAndResourcesUpdateArchiveOnceWithCanonicalDecimals() {
        var source = fixture(); var target = fixture(); var e = seed(source, true); var first = item(source, e);
        sync.apply(target.actor(), target.project(), request(target, first));
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repository.insertMetric(e, new PerformanceRunnerProtocol.PerformanceRunnerMetric(e.taskId(), e.id(), "fixture-runner", "__TOTAL__", 4, 4, 0, 1, new java.math.BigDecimal("2.5000"), 7, 8, 6, 9, now));
        repository.insertResource(e, new PerformanceRunnerProtocol.PerformanceRunnerResource(e.taskId(), e.id(), "fixture-runner", new java.math.BigDecimal("0.0001"), 4096, now));
        var latest = item(source, e); assertNotEquals(first.revision(), latest.revision()); assertNotEquals(first.sha256(), latest.sha256());
        assertEquals(4, latest.report().summary().sampleCount()); assertEquals(1, latest.report().resources().size());
        assertEquals(new java.math.BigDecimal("2.5"), latest.report().summary().peakThroughputPerSecond());
        String encoded = new String(sync.encoded(latest), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(encoded.contains("\"peakThroughputPerSecond\":2.5")); assertTrue(encoded.contains("\"cpuPercent\":0.0001"));
        sync.apply(target.actor(), target.project(), request(target, latest));
        assertEquals(latest, sync.read(target.actor(), target.project(), target.actor().userId(), latest.key())); assertEquals(1, count("performance_history_archive", target));
        assertEquals(e, executions.detail(source.actor(), source.project(), e.id()));
    }
    @Test void rejectsActiveReportsBadDigestUnknownFieldsAndNativeOverwrite() throws Exception {
        var source = fixture(); var target = fixture(); var e = seed(source, true); var item = item(source, e);
        assertThrows(ResponseStatusException.class, () -> sync.apply(source.actor(), source.project(), request(source, item)));
        var bad = new PerformanceHistorySyncService.Item(item.key(), item.revision(), "0".repeat(64), item.report());
        assertThrows(ResponseStatusException.class, () -> sync.apply(target.actor(), target.project(), request(target, bad)));
        var tree = json.valueToTree(request(target, item)); ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("items").get(0).path("report")).put("responseBody", "must-not-import");
        assertThrows(ResponseStatusException.class, () -> sync.decode(json.writeValueAsBytes(tree)));
        var activeTree = json.valueToTree(item.report()); ((com.fasterxml.jackson.databind.node.ObjectNode) activeTree).put("status", "RUNNING");
        var p = json.convertValue(activeTree, PerformanceHistorySyncService.Payload.class);
        var active = new PerformanceHistorySyncService.Item(item.key(), item.revision(), sync.hash(sync.encoded(p)), p);
        assertThrows(ResponseStatusException.class, () -> sync.apply(target.actor(), target.project(), request(target, active)));
        assertEquals(0, count("performance_history_archive", target));
    }
    @Test void rejectsSameSourceRevisionWithDifferentContentAndRollsBackEarlierInsert() {
        var source = fixture(); var target = fixture(); var e = seed(source, true); var item = item(source, e);
        sync.apply(target.actor(), target.project(), request(target, item));
        var newKey = source.project() + "." + com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();
        var added = new PerformanceHistorySyncService.Item(newKey, item.revision(), item.sha256(), item.report());
        var changed = changed(item, "不同副本", item.revision()); var before = inventory(target);
        assertThrows(ResponseStatusException.class, () -> sync.apply(target.actor(), target.project(), request(target, added, changed)));
        assertEquals(before, inventory(target)); assertEquals(1, count("performance_history_archive", target));
    }
    @Test void accountPermissionCsrfReplayAndExportIntegrityAreProjectScoped() throws Exception {
        var source = fixture(); var target = fixture(); var e = seed(source, true); var item = item(source, e); var request = request(target, item);
        String route = "/api/v1/projects/" + target.project() + "/performance/history";
        mvc.perform(post(route + "/sync").with(user(target.actor())).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request))).andExpect(status().isForbidden());
        mvc.perform(post(route + "/sync").with(user(source.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request))).andExpect(status().isForbidden());
        var receipt = sync.apply(target.actor(), target.project(), request);
        assertEquals(receipt, sync.apply(target.actor(), target.project(), request));
        var altered = new PerformanceHistorySyncService.Request(request.requestKey(), request.expectedUserId(), request.expectedRevision(), List.of(changed(item, "另一内容", "f".repeat(64))));
        assertThrows(ResponseStatusException.class, () -> sync.apply(target.actor(), target.project(), altered));
        var response = mvc.perform(get(route + "/" + item.key() + "/export").with(user(target.actor()))).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(sync.hash(response.getContentAsByteArray()), response.getHeader("X-TestHub-Report-SHA256"));
        mvc.perform(get(route + "/" + item.key()).with(user(source.actor()))).andExpect(status().isForbidden());
        jdbc.sql("UPDATE membership SET role_code='VIEWER' WHERE user_id=:id").param("id", target.actor().userId()).update();
        mvc.perform(get(route).with(user(target.actor()))).andExpect(status().isOk());
        mvc.perform(post(route + "/sync").with(user(target.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request))).andExpect(status().isForbidden());
    }
    @Test void rejectsMissingNumericFieldsAndNullPrimitiveValuesInsteadOfDefaultingToZero() throws Exception {
        var source = fixture(); var target = fixture(); var item = item(source, seed(source, true));
        var missing = json.valueToTree(request(target, item));
        ((com.fasterxml.jackson.databind.node.ObjectNode) missing.path("items").get(0).path("report").path("summary")).remove("sampleCount");
        assertThrows(ResponseStatusException.class, () -> sync.decode(json.writeValueAsBytes(missing)));
        var nullable = json.valueToTree(request(target, item));
        ((com.fasterxml.jackson.databind.node.ObjectNode) nullable.path("items").get(0).path("report").path("summary")).putNull("sampleCount");
        assertThrows(ResponseStatusException.class, () -> sync.decode(json.writeValueAsBytes(nullable)));
        assertEquals(0, count("performance_history_archive", target));
    }
    @Test void invalidResourceAndSummaryCountersRollBackWithoutAnArchive() throws Exception {
        var source = fixture(); var target = fixture(); var original = item(source, seed(source, true));
        for (var field : List.of("successCount", "errorRatePercent")) {
            var tree = json.valueToTree(original.report());
            ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("summary")).put(field, 101);
            var payload = json.convertValue(tree, PerformanceHistorySyncService.Payload.class);
            var invalid = new PerformanceHistorySyncService.Item(original.key(), original.revision(), sync.hash(sync.encoded(payload)), payload);
            assertThrows(ResponseStatusException.class, () -> sync.apply(target.actor(), target.project(), request(target, invalid)));
        }
        assertEquals(0, count("performance_history_archive", target));
    }

    @Test void preservesOneMillisecondTimestampRoundingButRejectsLargerReversals() {
        var source = fixture(); var target = fixture(); var original = item(source, seed(source, true));
        var tree = json.valueToTree(original.report());
        var finished = original.report().finishedAt();
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("requestedAt", finished.plusMillis(1).toString());
        var payload = json.convertValue(tree, PerformanceHistorySyncService.Payload.class);
        var rounded = new PerformanceHistorySyncService.Item(original.key(), original.revision(), sync.hash(sync.encoded(payload)), payload);
        sync.apply(target.actor(), target.project(), request(target, rounded));
        assertEquals(finished.plusMillis(1), sync.read(target.actor(), target.project(), target.actor().userId(), original.key()).report().requestedAt());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("requestedAt", finished.plusMillis(2).toString());
        var invalid = json.convertValue(tree, PerformanceHistorySyncService.Payload.class);
        assertThrows(ResponseStatusException.class, () -> sync.apply(target.actor(), target.project(), request(target,
                new PerformanceHistorySyncService.Item(original.key(), "f".repeat(64), sync.hash(sync.encoded(invalid)), invalid))));
    }

}
