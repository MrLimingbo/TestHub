package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.mock.*;
import io.testhub.platform.mock.MockTransferService.*;
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
class MockTransferFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry, directory, "mock-transfer-fixture");
        else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "mock-transfer-fixture");
        }
    }

    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired MockService mocks;
    @Autowired MockTransferService transfer;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record Fixture(PlatformUserPrincipal actor, String project) { }
    Fixture fixture() {
        String account = "mock-transfer-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test",
                "Mock 迁移测试", "Transfer-fixture-2026!"));
        var context = access.context(actor);
        return new Fixture(actor, context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    int count(String table, Fixture target) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id=:project")
                .param("project", target.project()).query(Integer.class).single();
    }
    Snapshot source(Fixture fixture) {
        var server = mocks.createServer(fixture.actor(), fixture.project(), new MockService.ServerInput("迁移订单 Mock"));
        mocks.createRule(fixture.actor(), fixture.project(), server.id(), new MockService.RuleInput("条件响应", "POST", "/orders", 100,
                Map.of("mode", "preview"), Map.of("X-Client", "desktop"), "JSON_SUBSET", "{\"kind\":\"order\"}",
                202, Map.of("Content-Type", "application/json", "X-Mock-Source", "transfer"), "{\"ok\":true}"));
        mocks.setSmartExampleEnabled(fixture.actor(), fixture.project(), server.id(), true);
        mocks.createShare(fixture.actor(), fixture.project(), server.id(), new MockService.ShareInput("只读分享", 30));
        return transfer.export(fixture.actor(), fixture.project());
    }
    ImportRequest request(Fixture target, Snapshot source) {
        return new ImportRequest(UUID.randomUUID().toString(), target.actor().userId(), source);
    }

    @Test void copiesRulesWithNewKeysAndRevokedSharesThenAllowsManualActivation() {
        Fixture local = fixture(), target = fixture(); Snapshot snapshot = source(local);
        ImportRequest request = request(target, snapshot);
        Receipt receipt = transfer.transfer(target.actor(), target.project(), request);
        assertEquals(1, receipt.servers()); assertEquals(1, receipt.rules()); assertEquals(1, receipt.shares());
        Snapshot imported = transfer.export(target.actor(), target.project());
        assertEquals(1, imported.servers().size());
        var server = mocks.servers(target.actor(), target.project()).getFirst();
        assertFalse(server.enabled()); assertTrue(server.smartExampleEnabled());
        assertNotEquals(mocks.servers(local.actor(), local.project()).getFirst().publicKey(), server.publicKey());
        assertEquals(snapshot.servers().getFirst().rules().getFirst().requestBody(), imported.servers().getFirst().rules().getFirst().requestBody());
        assertEquals(snapshot.servers().getFirst().rules().getFirst().responseHeaders(), imported.servers().getFirst().rules().getFirst().responseHeaders());
        var share = mocks.shares(target.actor(), target.project(), server.id()).getFirst();
        assertNotNull(share.revokedAt());
        assertNotEquals(mocks.shares(local.actor(), local.project(), mocks.servers(local.actor(), local.project()).getFirst().id())
                .getFirst().shareKey(), share.shareKey());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> mocks.publicShare(share.shareKey())).getStatusCode().value());
        var runtime = new MockRuntimeRequest("POST", "/orders", Map.of("mode", List.of("preview")),
                Map.of("X-Client", "desktop"), "{\"kind\":\"order\",\"extra\":1}");
        assertTrue(mocks.runtime(server.publicKey(), runtime).isEmpty());
        mocks.setServerEnabled(target.actor(), target.project(), server.id(), true);
        assertEquals(202, mocks.runtime(server.publicKey(), runtime).orElseThrow().status());
    }

    @Test void receiptIsIdempotentAndConflictsRollback() {
        Fixture local = fixture(), target = fixture(); Snapshot snapshot = source(local);
        ImportRequest request = request(target, snapshot);
        Receipt receipt = transfer.transfer(target.actor(), target.project(), request);
        assertEquals(receipt, transfer.transfer(target.actor(), target.project(), request));
        assertEquals(1, count("mock_server", target));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> transfer.transfer(target.actor(), target.project(),
                        new ImportRequest(request.requestKey(), target.actor().userId(), new Snapshot(1, snapshot.sourceProjectId(), List.of()))))
                .getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> transfer.transfer(target.actor(), target.project(), request(target, snapshot))).getStatusCode().value());
        assertEquals(1, count("mock_server", target));
        assertEquals(1, count("mock_transfer_receipt", target));
    }

    @Test void twentyImportedRevokedSharesDoNotPreventCreatingNewTargetShares() {
        Fixture local = fixture(), target = fixture();
        var server = mocks.createServer(local.actor(), local.project(), new MockService.ServerInput("满额分享 Mock"));
        for (int index = 0; index < 20; index++)
            mocks.createShare(local.actor(), local.project(), server.id(), new MockService.ShareInput("分享 " + index, 30));
        transfer.transfer(target.actor(), target.project(), request(target, transfer.export(local.actor(), local.project())));
        var imported = mocks.servers(target.actor(), target.project()).getFirst();
        assertEquals(20, mocks.shares(target.actor(), target.project(), imported.id()).size());
        assertNotNull(mocks.createShare(target.actor(), target.project(), imported.id(),
                new MockService.ShareInput("新分享", 30)).shareKey());
    }

    @Test void malformedRuleRollsBackWholeBatchAndForeignProjectIsForbidden() throws Exception {
        Fixture local = fixture(), target = fixture(); Snapshot snapshot = source(local);
        var item = snapshot.servers().getFirst();
        var original = item.rules().getFirst();
        var invalid = new Rule(original.id(), original.name(), original.httpMethod(), "../escape", original.priority(),
                original.requestQuery(), original.requestHeaders(), original.requestBodyMatch(), original.requestBody(),
                original.responseStatus(), original.responseHeaders(), original.responseBody(), true);
        var bad = new Snapshot(1, snapshot.sourceProjectId(), List.of(new Server(item.id(), item.name(), item.enabled(),
                item.smartExampleEnabled(), List.of(invalid), item.shares())));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> transfer.transfer(target.actor(), target.project(), request(target, bad))).getStatusCode().value());
        assertEquals(0, count("mock_server", target)); assertEquals(0, count("mock_transfer_receipt", target));
        String url = "/api/v1/projects/" + target.project() + "/mock/transfer";
        mvc.perform(get(url + "/capabilities").with(user(target.actor()))).andExpect(status().isOk());
        mvc.perform(post(url).with(user(local.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request(target, snapshot)))).andExpect(status().isForbidden());
    }
}
