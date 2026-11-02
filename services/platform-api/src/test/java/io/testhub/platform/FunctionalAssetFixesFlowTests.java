package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.functional.FunctionalAssetService;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class FunctionalAssetFixesFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        FunctionalDatabaseProfile.configure(registry, directory, "functional-assets-fixture");
        if (!FunctionalDatabaseProfile.mysql()) {
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired FunctionalAssetService assets;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    record Fixture(PlatformUserPrincipal actor, String project) { }
    Fixture fixture() {
        String account = "qa-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test", "功能资产回归", UUID.randomUUID() + "Aa1!"));
        var context = access.context(actor);
        return new Fixture(actor, context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    FunctionalAssetService.CatalogInput catalog(String name, String scope, String parent) {
        return new FunctionalAssetService.CatalogInput(name, parent, 0, scope);
    }
    void conflict(Runnable action) {
        assertEquals(409, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }
    long auditCount(String project) {
        return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE project_id=:p").param("p", project).query(Long.class).single();
    }

    @Test void rejectsRootCreateRenameAndMoveConflictsWithoutAuditOrDataChanges() {
        var f = fixture();
        var a = assets.createCatalog(f.actor(), f.project(), catalog("根A", "REQUIREMENTS", null));
        var b = assets.createCatalog(f.actor(), f.project(), catalog("根B", "REQUIREMENTS", null));
        var child = assets.createCatalog(f.actor(), f.project(), catalog("根A", "REQUIREMENTS", b.id()));
        long audit = auditCount(f.project());
        conflict(() -> assets.createCatalog(f.actor(), f.project(), catalog(" 根A ", "REQUIREMENTS", null)));
        conflict(() -> assets.updateCatalog(f.actor(), f.project(), b.id(), catalog("根A", "REQUIREMENTS", null)));
        conflict(() -> assets.updateCatalog(f.actor(), f.project(), child.id(), catalog("根A", "REQUIREMENTS", null)));
        assertEquals(audit, auditCount(f.project()));
        assertEquals(3, assets.listCatalogs(f.actor(), f.project(), "REQUIREMENTS").size());
        assertEquals(b.id(), assets.listCatalogs(f.actor(), f.project(), "REQUIREMENTS").stream().filter(c -> c.id().equals(child.id())).findFirst().orElseThrow().parentId());
        assertEquals(a.id(), assets.updateCatalog(f.actor(), f.project(), a.id(), catalog("根A", "REQUIREMENTS", null)).id());
        assertNotNull(assets.createCatalog(f.actor(), f.project(), catalog("根A", "CASES", null)));
        assertNotNull(assets.createCatalog(f.actor(), f.project(), catalog("根A", "REQUIREMENTS", a.id())));
        var other = fixture();
        assertNotNull(assets.createCatalog(other.actor(), other.project(), catalog("根A", "REQUIREMENTS", null)));
    }

    @Test void concurrentRootCreatesCommitExactlyOneCatalog() throws Exception {
        var f = fixture(); var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var jobs = List.of(1, 2).stream().map(i -> pool.submit(() -> {
                assertTrue(gate.await(10, TimeUnit.SECONDS));
                try { assets.createCatalog(f.actor(), f.project(), catalog("并发根", "REQUIREMENTS", null)); return 200; }
                catch (ResponseStatusException error) { return error.getStatusCode().value(); }
            })).toList();
            gate.countDown();
            var statuses = List.of(jobs.get(0).get(20, TimeUnit.SECONDS), jobs.get(1).get(20, TimeUnit.SECONDS));
            assertEquals(List.of(200, 409), statuses.stream().sorted().toList());
            assertEquals(1, assets.listCatalogs(f.actor(), f.project(), "REQUIREMENTS").size());
        }
    }

    @Test void concurrentRootRenamesCommitExactlyOneTargetName() throws Exception {
        var f = fixture();
        var first = assets.createCatalog(f.actor(), f.project(), catalog("第一根", "REQUIREMENTS", null));
        var second = assets.createCatalog(f.actor(), f.project(), catalog("第二根", "REQUIREMENTS", null));
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var jobs = List.of(first.id(), second.id()).stream().map(id -> pool.submit(() -> {
                assertTrue(gate.await(10, TimeUnit.SECONDS));
                try { assets.updateCatalog(f.actor(), f.project(), id, catalog("共同目标", "REQUIREMENTS", null)); return 200; }
                catch (ResponseStatusException error) { return error.getStatusCode().value(); }
            })).toList();
            gate.countDown();
            assertEquals(List.of(200, 409), List.of(jobs.get(0).get(20, TimeUnit.SECONDS), jobs.get(1).get(20, TimeUnit.SECONDS)).stream().sorted().toList());
            assertEquals(1, assets.listCatalogs(f.actor(), f.project(), "REQUIREMENTS").stream().filter(c -> c.name().equals("共同目标")).count());
        }
    }

    @Test void referencedRequirementDeleteIs409AndUnlinkAllowsDelete() throws Exception {
        var f = fixture();
        var rc = assets.createCatalog(f.actor(), f.project(), catalog("需求", "REQUIREMENTS", null));
        var cc = assets.createCatalog(f.actor(), f.project(), catalog("用例", "CASES", null));
        var req = assets.createRequirement(f.actor(), f.project(), new FunctionalAssetService.RequirementInput(rc.id(), "REQ_1", "需求", "P1", "DRAFT", ""));
        var c = assets.createCase(f.actor(), f.project(), new FunctionalAssetService.CaseInput(cc.id(), req.id(), "CASE_1", "用例", "P1", "DRAFT", "FUNCTIONAL", "", "步骤", "结果"));
        long audit = auditCount(f.project());
        mvc.perform(delete("/api/v1/projects/{p}/functional/requirements/{r}", f.project(), req.id()).with(user(f.actor())).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_REJECTED"))
                .andExpect(jsonPath("$.message").value("需求仍被其他资产引用，请先解除关联后再删除"));
        assertEquals(audit, auditCount(f.project()));
        assertEquals(req.id(), assets.listRequirements(f.actor(), f.project(), null).getFirst().id());
        assertEquals(req.id(), assets.listCases(f.actor(), f.project(), null).getFirst().requirementId());
        assets.updateCase(f.actor(), f.project(), c.id(), new FunctionalAssetService.CaseInput(cc.id(), null, "CASE_1", "用例", "P1", "DRAFT", "FUNCTIONAL", "", "步骤", "结果"));
        assets.deleteRequirement(f.actor(), f.project(), req.id());
        assertTrue(assets.listRequirements(f.actor(), f.project(), null).isEmpty());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> assets.deleteRequirement(f.actor(), f.project(), req.id())).getStatusCode().value());
    }

    @Test void missingAssetTagQueryParametersUse400Envelope() throws Exception {
        var f = fixture();
        String route = "/api/v1/projects/" + f.project() + "/functional/asset-tags";
        for (String query : List.of("?assetType=CASE", "?assetId=missing", "")) {
            mvc.perform(get(route + query).with(user(f.actor())))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_REJECTED"))
                    .andExpect(jsonPath("$.requestId").isNotEmpty());
        }
        mvc.perform(get(route + "?assetType=CASE&assetId=missing").with(user(f.actor())))
                .andExpect(status().isNotFound());
    }
}
