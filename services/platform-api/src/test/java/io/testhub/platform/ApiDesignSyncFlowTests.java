package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.apidesign.ApiDesignService;
import io.testhub.platform.apidesign.ApiDesignSyncProjection;
import io.testhub.platform.apidesign.ApiDesignSyncService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {"testhub.registration.enabled=true", "testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver = FunctionalDatabaseProfile.class)
class ApiDesignSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry, directory, "api-design-sync-fixture");
        else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "api-design-sync-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired ApiDesignService design;
    @Autowired ApiDesignSyncService sync;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;

    record Fixture(PlatformUserPrincipal actor, String project) { }
    Fixture fixture() {
        String account = "api-design-sync-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test", "接口同步测试", "Sync-fixture-2026!"));
        var context = access.context(actor);
        return new Fixture(actor, context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    ApiDesignSyncProjection.Snapshot snapshot(Fixture fixture) { return sync.snapshot(fixture.actor(), fixture.project(), fixture.actor().userId()); }
    ApiDesignSyncService.Request request(Fixture target, ApiDesignSyncProjection.Item... items) {
        return new ApiDesignSyncService.Request(UUID.randomUUID().toString(), target.actor().userId(), snapshot(target).revision(),
                java.util.Arrays.stream(items).map(item -> new ApiDesignSyncService.Operation(UUID.randomUUID().toString(), item)).toList());
    }
    void apply(Fixture target, ApiDesignSyncProjection.Item... items) { sync.apply(target.actor(), target.project(), request(target, items)); }
    ApiDesignSyncProjection.Item item(String kind, String name, Object definition) {
        return new ApiDesignSyncProjection.Item(kind, name, json.convertValue(definition,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
    }
    void seed(Fixture fixture) {
        var root = design.createCatalog(fixture.actor(), fixture.project(), new ApiDesignService.CatalogInput("接口", null, 1));
        var folder = design.createCatalog(fixture.actor(), fixture.project(), new ApiDesignService.CatalogInput("订单", root.id(), 2));
        var api = design.createDefinition(fixture.actor(), fixture.project(), new ApiDesignService.DefinitionInput(folder.id(), "GET", "/orders/{id}",
                "查询订单", "1.0.0", "摘要", "说明", List.of(), List.of(), List.of(), "", "{}", Map.of("ok", true)));
        design.createVersion(fixture.actor(), fixture.project(), api.id(), new ApiDesignService.VersionInput("1.1.0", "新版", "说明二",
                List.of(), List.of(), List.of(), "", "{}", Map.of("ok", true)));
        design.createCase(fixture.actor(), fixture.project(), api.id(), new ApiDesignService.CaseInput("成功查询", null,
                json.createObjectNode().put("path", "/orders/1"), 200, "", "{}"));
    }

    @Test void nestedCatalogDefinitionsVersionsAndCasesCopyWithoutPhysicalIds() {
        var source = fixture(); seed(source); var target = fixture();
        var sourceItems = snapshot(source).items();
        assertEquals(6, sourceItems.size());
        apply(target, sourceItems.toArray(ApiDesignSyncProjection.Item[]::new));
        assertEquals(sourceItems, snapshot(target).items());
        assertNotEquals(snapshot(source).itemIds(), snapshot(target).itemIds());
        var targetApi = design.definitions(target.actor(), target.project(), null).getFirst();
        assertEquals("1.1.0", design.versions(target.actor(), target.project(), targetApi.id()).stream()
                .filter(value -> value.id().equals(targetApi.currentVersionId())).findFirst().orElseThrow().versionName());
        assertEquals("成功查询", design.cases(target.actor(), target.project(), targetApi.id()).getFirst().name());
        var changed = item("CASE", "GET /orders/{id}\n成功查询", new ApiDesignSyncProjection.Case(null,
                json.createObjectNode().put("path", "/orders/2"), 200, "", "{}"));
        apply(target, changed);
        apply(source, snapshot(target).items().stream().filter(value -> value.kind().equals("CASE")).findFirst().orElseThrow());
        assertEquals(snapshot(target).items(), snapshot(source).items());
    }

    @Test void historicalVersionContentCanOverwriteWithoutChangingPhysicalIdAndReplayIsStable() {
        var fixture = fixture(); seed(fixture);
        var original = snapshot(fixture);
        var version = original.items().stream().filter(value -> value.kind().equals("VERSION")).findFirst().orElseThrow();
        var body = new java.util.HashMap<>(version.definition()); body.put("summary", "被篡改的历史摘要");
        var changed = new ApiDesignSyncProjection.Item(version.kind(), version.name(), body);
        var extra = item("CATALOG", "[\"无关\"]", new ApiDesignSyncProjection.Catalog(0));
        apply(fixture, extra, changed);
        assertEquals(original.itemIds().get("VERSION\n" + version.name().toLowerCase()),
                snapshot(fixture).itemIds().get("VERSION\n" + version.name().toLowerCase()));
        assertEquals("被篡改的历史摘要", snapshot(fixture).items().stream()
                .filter(value -> value.name().equals(version.name())).findFirst().orElseThrow().definition().get("summary"));
        var request = request(fixture, item("CATALOG", "[\"再新增\"]", new ApiDesignSyncProjection.Catalog(0)));
        var receipt = sync.apply(fixture.actor(), fixture.project(), request);
        assertEquals(receipt, sync.apply(fixture.actor(), fixture.project(), request));
        assertThrows(ResponseStatusException.class, () -> sync.apply(fixture.actor(), fixture.project(),
                new ApiDesignSyncService.Request(request.requestKey(), request.expectedUserId(), request.expectedRevision(),
                        List.of(new ApiDesignSyncService.Operation(UUID.randomUUID().toString(), changed)))));
    }

    @Test void publishingAExistingDraftPropagatesWithoutOverwritingVersionContent() {
        var source = fixture(); seed(source); var target = fixture();
        apply(target, snapshot(source).items().toArray(ApiDesignSyncProjection.Item[]::new));
        var sourceApi = design.definitions(source.actor(), source.project(), null).getFirst();
        var sourceVersion = design.versions(source.actor(), source.project(), sourceApi.id()).stream()
                .filter(value -> value.versionName().equals("1.1.0")).findFirst().orElseThrow();
        design.publish(source.actor(), source.project(), sourceApi.id(), sourceVersion.id());
        var published = snapshot(source).items().stream().filter(value -> value.kind().equals("VERSION")
                && value.name().endsWith("1.1.0")).findFirst().orElseThrow();
        apply(target, published);
        var targetApi = design.definitions(target.actor(), target.project(), null).getFirst();
        var actual = design.versions(target.actor(), target.project(), targetApi.id()).stream()
                .filter(value -> value.versionName().equals("1.1.0")).findFirst().orElseThrow();
        assertEquals("PUBLISHED", actual.status());
        var publishedBy = actual.publishedBy(); var publishedAt = actual.publishedAt();
        assertEquals(published, snapshot(target).items().stream().filter(value -> value.name().equals(published.name())).findFirst().orElseThrow());
        var editedBody = new java.util.HashMap<>(published.definition()); editedBody.put("summary", "覆盖发布内容");
        apply(target, new ApiDesignSyncProjection.Item("VERSION", published.name(), editedBody));
        var edited = design.versions(target.actor(), target.project(), targetApi.id()).stream()
                .filter(value -> value.versionName().equals("1.1.0")).findFirst().orElseThrow();
        assertEquals("覆盖发布内容", edited.summary());
        assertEquals(actual.id(), edited.id());
        assertEquals(publishedBy, edited.publishedBy()); assertEquals(publishedAt, edited.publishedAt());
        var reversed = new java.util.HashMap<>(published.definition()); reversed.put("status", "DRAFT");
        assertThrows(ResponseStatusException.class, () -> apply(target,
                new ApiDesignSyncProjection.Item("VERSION", published.name(), reversed)));
        assertEquals("PUBLISHED", design.versions(target.actor(), target.project(), targetApi.id()).stream()
                .filter(value -> value.versionName().equals("1.1.0")).findFirst().orElseThrow().status());
    }

    @Test void missingParentDescriptorAndStaleRevisionDoNotWrite() {
        var fixture = fixture();
        var orphan = item("DEFINITION", "GET /orphan", new ApiDesignSyncProjection.Definition("[\"missing\"]", "孤立接口", "1.0.0"));
        assertThrows(ResponseStatusException.class, () -> apply(fixture, orphan));
        assertTrue(snapshot(fixture).items().isEmpty());
        seed(fixture);
        var before = snapshot(fixture);
        var catalog = item("CATALOG", "[\"new\"]", new ApiDesignSyncProjection.Catalog(0));
        var stale = request(fixture, catalog);
        apply(fixture, item("CATALOG", "[\"other\"]", new ApiDesignSyncProjection.Catalog(0)));
        assertThrows(ResponseStatusException.class, () -> sync.apply(fixture.actor(), fixture.project(), stale));
        var snapshot = json.createObjectNode().put("grpcDescriptorId", "A".repeat(26));
        var bad = item("CASE", "GET /orders/{id}\n包含描述符", new ApiDesignSyncProjection.Case(null, snapshot, null, "", ""));
        assertThrows(ResponseStatusException.class, () -> apply(fixture, bad));
        assertEquals(before.items().size() + 1, snapshot(fixture).items().size());
    }

    @Test void readRequiresSameActorAndPostRequiresCsrf() throws Exception {
        var fixture = fixture(); var other = fixture();
        assertThrows(ResponseStatusException.class, () -> sync.snapshot(fixture.actor(), fixture.project(), other.actor().userId()));
        String url = "/api/v1/projects/" + fixture.project() + "/api-design/sync";
        mvc.perform(get(url).param("expectedUserId", fixture.actor().userId()).with(user(fixture.actor()))).andExpect(status().isOk());
        byte[] body = json.writeValueAsBytes(request(fixture, item("CATALOG", "[\"接口\"]", new ApiDesignSyncProjection.Catalog(0))));
        mvc.perform(post(url).with(user(fixture.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user(fixture.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }
}
