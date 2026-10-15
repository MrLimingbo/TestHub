package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import io.testhub.platform.apidesign.*;
import io.testhub.platform.apidesign.ApiTransferService.*;
import io.testhub.platform.functional.FunctionalTransferFiles;
import io.testhub.platform.parameter.ProjectEnvironmentService;
import io.testhub.platform.parameter.ProjectParameterService;
import io.testhub.platform.storage.FileObjectService;
import io.testhub.platform.storage.ObjectStorage;
import org.springframework.web.server.ResponseStatusException;
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
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {"testhub.registration.enabled=true", "testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver = FunctionalDatabaseProfile.class)
@DirtiesContext
class ApiTransferFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) {
            FunctionalDatabaseProfile.configure(registry, directory, "api-transfer-fixture");
        } else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "api-transfer-fixture");
        }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired JdbcClient jdbc;
    @Autowired ApiTransferService nativeTransfer;
    @Autowired ApiDesignService design;
    @Autowired ProjectEnvironmentService environments;
    @Autowired ProjectParameterService parameters;
    @Autowired FileObjectService projectFiles;
    @org.springframework.test.context.bean.override.convention.TestBean ObjectStorage objectStorage;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ApiTransferRepository transferRepository;
    static ObjectStorage objectStorage() { return new TransferStorage(); }
    static class TransferStorage implements ObjectStorage {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final AtomicInteger failAfter = new AtomicInteger(-1);
        public boolean bucketExists(String bucket) { return true; }
        public void ensureBucket(String bucket) { }
        public void put(String bucket, String key, InputStream input, long size, String type) throws Exception {
            if (failAfter.get() >= 0 && failAfter.getAndDecrement() == 0) throw new IOException("injected storage failure");
            objects.put(key, input.readAllBytes());
        }
        public InputStream get(String bucket, String key) throws Exception {
            if (!objects.containsKey(key)) throw new FileNotFoundException(key);
            return new ByteArrayInputStream(objects.get(key));
        }
        public void remove(String bucket, String key) { objects.remove(key); }
        public String label() { return "isolated-api-transfer-test"; }
    }
    Snapshot typed(ObjectNode s) throws Exception { return json.treeToValue(s, Snapshot.class); }
    ImportRequest typedRequest(Fixture f, Snapshot s) { return new ImportRequest(UUID.randomUUID().toString(), f.actor().userId(), s); }
    Receipt apply(Fixture f, Snapshot s) { return nativeTransfer.transfer(f.actor(), f.project(), typedRequest(f, s)); }
    int count(String table, Fixture f) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id=:project").param("project", f.project()).query(Integer.class).single();
    }
    void rejected(Fixture f, Snapshot s, int status) throws Exception {
        assertEquals(status, assertThrows(ResponseStatusException.class, () -> apply(f, s)).getStatusCode().value());
        empty(f); assertEquals(0, count("file_object", f));
    }
    record Fixture(PlatformUserPrincipal actor, String project) { }
    Fixture fixture() {
        String account = "transfer-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test",
                "接口迁移测试", "Transfer-fixture-2026!"));
        var context = access.context(actor);
        return new Fixture(actor, context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    String id() { return UlidCreator.getUlid().toString(); }
    String url(Fixture f) { return "/api/v1/projects/" + f.project() + "/api-design/transfer"; }
    ObjectNode snapshot() throws Exception {
        String catalog = id(), api = id(), older = id(), newer = id();
        ObjectNode s = json.createObjectNode().put("version", 1).put("sourceProjectId", id());
        s.putArray("catalogs").addObject().put("id", catalog).putNull("parentId").put("name", "订单").put("sortOrder", 3);
        s.putArray("definitions").addObject().put("id", api).put("catalogId", catalog).put("method", "GET")
                .put("path", "/transfer/orders").put("name", "查询订单").put("status", "PUBLISHED").put("currentVersionId", older);
        var versions = s.putArray("versions");
        for (int n = 0; n < 2; n++) {
            ObjectNode v = versions.addObject().put("id", n == 0 ? older : newer).put("apiId", api)
                    .put("versionName", n == 0 ? "1.0.0" : "2.0.0").put("summary", "摘要").put("description", "说明")
                    .put("requestBody", "{}").put("responseBody", "{}").put("status", "PUBLISHED")
                    .put("publishedAt", "2026-09-20T00:00:00Z").put("createdAt", n == 0 ? "2026-09-18T00:00:00Z" : "2026-09-19T00:00:00Z");
            v.putArray("headers"); v.putArray("query"); v.putArray("pathParameters");
            v.putObject("exampleResponse").put("statusCode", 200);
        }
        ObjectNode c = s.putArray("cases").addObject().put("id", id()).put("apiId", api).put("name", "成功查询")
                .putNull("environmentCode").put("expectedStatus", 200).put("responseValidation", "{}").put("savedResponseBody", "{}");
        c.set("requestSnapshot", json.readTree("{\"method\":\"GET\",\"auth\":{\"type\":\"bearer\",\"value\":\"fixture-secret\"}}"));
        return s;
    }
    ObjectNode request(Fixture f, JsonNode snapshot) {
        ObjectNode r = json.createObjectNode().put("requestKey", UUID.randomUUID().toString()).put("expectedUserId", f.actor().userId());
        r.set("snapshot", snapshot); return r;
    }
    ResultActions transfer(Fixture f, ObjectNode request) throws Exception {
        return mvc.perform(post(url(f)).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request.toString()));
    }
    JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray()).get("data");
    }
    JsonNode export(Fixture f) throws Exception { return data(mvc.perform(get(url(f)).with(user(f.actor()))).andExpect(status().isOk())); }
    void empty(Fixture f) throws Exception {
        JsonNode s = export(f);
        for (String key : new String[]{"catalogs", "definitions", "versions", "cases"}) assertEquals(0, s.get(key).size(), key);
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM api_transfer_receipt WHERE project_id=:project")
                .param("project", f.project()).query(Integer.class).single());
    }
    @Test void preservesAllPublishedVersionsAndNonLatestCurrentPointerAndSanitizesCases() throws Exception {
        var f = fixture(); var source = snapshot();
        JsonNode receipt = data(transfer(f, request(f, source)).andExpect(status().isOk()));
        assertEquals(2, receipt.path("versions").asInt());
        JsonNode exported = export(f), definition = exported.get("definitions").get(0);
        assertNotEquals(source.at("/definitions/0/id").asText(), definition.path("id").asText());
        assertEquals(2, exported.get("versions").size());
        for (JsonNode v : exported.get("versions")) {
            assertEquals("PUBLISHED", v.path("status").asText());
            assertEquals("2026-09-20T00:00:00Z", v.path("publishedAt").asText());
            if (v.path("versionName").asText().equals("1.0.0")) assertEquals(v.path("id"), definition.path("currentVersionId"));
        }
        assertFalse(exported.get("cases").toString().contains("fixture-secret"));
        assertEquals(1, exported.get("cases").size());
    }
    @Test void retriesReturnOriginalReceiptAndChangedPayloadConflicts() throws Exception {
        var f = fixture(); var r = request(f, snapshot());
        JsonNode first = data(transfer(f, r).andExpect(status().isOk()));
        assertEquals(first, data(transfer(f, r).andExpect(status().isOk())));
        ((ObjectNode) r.at("/snapshot/definitions/0")).put("name", "修改名称");
        transfer(f, r).andExpect(status().isConflict());
        assertEquals(1, export(f).get("definitions").size());
    }
    @Test void rejectsForeignProjectAndChangedActorWithoutWrites() throws Exception {
        var owner = fixture(); var outsider = fixture(); var r = request(owner, snapshot());
        mvc.perform(post(url(owner)).with(user(outsider.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(r.toString())).andExpect(status().isForbidden());
        r.put("expectedUserId", outsider.actor().userId()); transfer(owner, r).andExpect(status().isConflict()); empty(owner);
    }
    @Test void rejectsCatalogCyclesAndCrossApiCurrentPointersAtomically() throws Exception {
        var f = fixture(); var s = snapshot();
        ((ObjectNode) s.at("/catalogs/0")).put("parentId", s.at("/catalogs/0/id").asText());
        transfer(f, request(f, s)).andExpect(status().isBadRequest()); empty(f);
        s = snapshot(); ((ObjectNode) s.at("/definitions/0")).put("currentVersionId", id());
        transfer(f, request(f, s)).andExpect(status().isBadRequest()); empty(f);
    }
    @Test void existingRouteConflictDoesNotCreateAnExtraImportRoot() throws Exception {
        var f = fixture(); transfer(f, request(f, snapshot())).andExpect(status().isOk());
        JsonNode before = export(f);
        transfer(f, request(f, snapshot())).andExpect(status().isConflict());
        assertEquals(before, export(f));
    }
    @Test void rejectsMissingEnvironmentVariablesAndGrpcReferencesWithoutWrites() throws Exception {
        var f = fixture(); var s = snapshot();
        ((ObjectNode) s.at("/cases/0")).put("environmentCode", "missing-env");
        transfer(f, request(f, s)).andExpect(status().isConflict()); empty(f);
        s = snapshot(); ((ObjectNode) s.at("/versions/0")).put("requestBody", "{{missingVariable}}");
        transfer(f, request(f, s)).andExpect(status().isConflict()); empty(f);
        s = snapshot(); ((ObjectNode) s.at("/cases/0/requestSnapshot")).put("grpcDescriptorId", id());
        transfer(f, request(f, s)).andExpect(status().isConflict()); empty(f);
    }
    @Test void rejectsUnknownProtocolFieldsAndUnsupportedVersions() throws Exception {
        var f = fixture(); var r = request(f, snapshot()); r.put("unknown", true);
        transfer(f, r).andExpect(status().isBadRequest()); empty(f);
        r.remove("unknown"); ((ObjectNode) r.get("snapshot")).put("version", 4);
        transfer(f, r).andExpect(status().isBadRequest()); empty(f);
    }

    @Test void copiesFileBytesAndRewritesNestedSnapshotReferences() throws Exception {
        var f = fixture(); var s = snapshot(); String fileId = id();
        String sourcePath = "/api/v1/projects/" + s.path("sourceProjectId").asText() + "/files/" + fileId + "/download";
        ((ObjectNode) s.at("/versions/0")).put("description", sourcePath);
        ((ObjectNode) s.at("/cases/0/requestSnapshot")).putObject("attachment").put("url", sourcePath);
        byte[] bytes = "API transfer fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var files = s.putObject("files").put("projectId", s.path("sourceProjectId").asText());
        files.putArray("items").addObject().put("id", fileId).put("name", "fixture.txt").put("type", "text/plain")
                .put("size", bytes.length).put("sha256", io.testhub.platform.functional.FunctionalTransferFiles.sha256(bytes))
                .put("content", java.util.Base64.getEncoder().encodeToString(bytes));
        JsonNode result = data(transfer(f, request(f, s)).andExpect(status().isOk()));
        assertEquals(1, result.at("/files/items").asInt());
        JsonNode exported = data(mvc.perform(get(url(f)).param("includeFiles", "true").with(user(f.actor())))
                .andExpect(status().isOk()));
        String copied = "/api/v1/projects/" + f.project() + "/files/" + exported.at("/files/items/0/id").asText() + "/download";
        assertNotEquals(sourcePath, copied);
        assertEquals(copied, exported.at("/cases/0/requestSnapshot/attachment/url").asText());
        assertTrue(java.util.stream.StreamSupport.stream(exported.get("versions").spliterator(), false)
                .anyMatch(v -> copied.equals(v.path("description").asText())));
        assertArrayEquals(bytes, mvc.perform(get(copied).with(user(f.actor()))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    @Test void rejectsUnselectedReferencedFilesAndCorruptFileBytesBeforeWriting() throws Exception {
        var f = fixture(); var s = snapshot(); String fileId = id();
        ((ObjectNode) s.at("/versions/0")).put("description", "/api/v1/projects/" + s.path("sourceProjectId").asText()
                + "/files/" + fileId + "/download");
        transfer(f, request(f, s)).andExpect(status().isConflict()); empty(f);
        s.putObject("files").put("projectId", s.path("sourceProjectId").asText()).putArray("items").addObject()
                .put("id", fileId).put("name", "corrupt.txt").put("type", "text/plain").put("size", 1)
                .put("sha256", "0".repeat(64)).put("content", "YQ==");
        transfer(f, request(f, s)).andExpect(status().isBadRequest()); empty(f);
    }
    @Test void realSourceExportKeepsAllVersionsHistoricalTimesEmptyDirectoriesAndOwnership() throws Exception {
        var source = fixture(); var target = fixture();
        ObjectNode seed = snapshot(); String parent = seed.at("/catalogs/0/id").asText(), child = id();
        ((com.fasterxml.jackson.databind.node.ArrayNode) seed.path("catalogs")).addObject()
                .put("id", child).put("parentId", parent).put("name", "空目录").put("sortOrder", -5);
        var draft = ((ObjectNode) seed.at("/versions/1")).deepCopy().put("id", id()).put("versionName", "3.0.0")
                .put("status", "DRAFT").putNull("publishedAt").put("createdAt", "2026-09-21T00:00:00Z");
        ((com.fasterxml.jackson.databind.node.ArrayNode) seed.path("versions")).add(draft);
        apply(source, typed(seed));
        Snapshot before = nativeTransfer.export(source.actor(), source.project(), false);
        var result = apply(target, before);
        Snapshot after = nativeTransfer.export(target.actor(), target.project(), false);
        var api = after.definitions().getFirst();
        assertEquals(before.versions().size(), after.versions().size());
        assertEquals(before.catalogs().size(), result.catalogs());
        assertEquals(List.of("3.0.0", "2.0.0", "1.0.0"), after.versions().stream().map(Version::versionName).toList());
        for (var original : before.versions()) {
            var migrated = after.versions().stream().filter(v -> v.versionName().equals(original.versionName())).findFirst().orElseThrow();
            assertEquals(original.createdAt(), migrated.createdAt()); assertEquals(original.publishedAt(), migrated.publishedAt());
            assertEquals(original.status(), migrated.status()); assertEquals(original.requestBody(), migrated.requestBody());
            assertEquals(original.exampleResponse(), migrated.exampleResponse()); assertEquals(api.id(), migrated.apiId());
        }
        assertEquals("1.0.0", after.versions().stream().filter(v -> v.id().equals(api.currentVersionId())).findFirst().orElseThrow().versionName());
        var emptyCatalog = after.catalogs().stream().filter(c -> c.name().equals("空目录")).findFirst().orElseThrow();
        assertEquals(-5, emptyCatalog.sortOrder());
        assertEquals("订单", after.catalogs().stream().filter(c -> c.id().equals(emptyCatalog.parentId())).findFirst().orElseThrow().name());
        Set<String> sourceIds = new HashSet<>(), targetIds = new HashSet<>();
        before.catalogs().forEach(c -> sourceIds.add(c.id())); before.definitions().forEach(d -> sourceIds.add(d.id()));
        before.versions().forEach(v -> sourceIds.add(v.id())); before.cases().forEach(c -> sourceIds.add(c.id()));
        after.catalogs().forEach(c -> assertTrue(targetIds.add(c.id()))); after.definitions().forEach(d -> assertTrue(targetIds.add(d.id())));
        after.versions().forEach(v -> assertTrue(targetIds.add(v.id()))); after.cases().forEach(c -> assertTrue(targetIds.add(c.id())));
        assertTrue(Collections.disjoint(sourceIds, targetIds));
        String org = access.context(target.actor()).organizations().getFirst().id();
        for (var c : design.catalogs(target.actor(), target.project())) {
            assertEquals(org, c.organizationId()); assertEquals(target.project(), c.projectId()); assertEquals(target.actor().userId(), c.createdBy());
        }
        for (var d : design.definitions(target.actor(), target.project(), null)) {
            assertEquals(org, d.organizationId()); assertEquals(target.project(), d.projectId());
            assertEquals(target.actor().userId(), d.createdBy()); assertEquals(target.actor().userId(), d.updatedBy());
            for (var v : design.versions(target.actor(), target.project(), d.id())) { assertNull(v.publishedBy()); assertEquals(target.actor().userId(), v.createdBy()); }
        }
        var c = design.cases(target.actor(), target.project()).getFirst();
        assertEquals(before.cases().getFirst().requestSnapshot(), c.requestSnapshot());
        assertEquals(before.cases().getFirst().expectedStatus(), c.expectedStatus());
        assertEquals(before.cases().getFirst().responseValidation(), c.responseValidation());
        assertEquals(before.cases().getFirst().savedResponseBody(), c.savedResponseBody());
        assertEquals(target.actor().userId(), c.createdBy()); assertEquals(target.actor().userId(), c.updatedBy()); assertEquals(org, c.organizationId());
        assertEquals(before, nativeTransfer.export(source.actor(), source.project(), false));
    }

    @Test void concurrentSamePayloadReturnsOneReceiptAndObjectKeyOrderIsImmaterial() throws Exception {
        var target = fixture(); var s = typed(snapshot()); var req = typedRequest(target, s);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Receipt> work = () -> {
                start.await();
                org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                        new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
                try { return nativeTransfer.transfer(target.actor(), target.project(), req); }
                finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
            };
            var first = executor.submit(work); var second = executor.submit(work); start.countDown();
            assertEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
        assertEquals(1, count("api_definition", target)); assertEquals(1, count("api_transfer_receipt", target));
        ObjectNode reordered = json.valueToTree(s);
        var snap = (ObjectNode) reordered.at("/cases/0/requestSnapshot"); var method = snap.remove("method"); snap.set("method", method);
        assertEquals(nativeTransfer.transfer(target.actor(), target.project(), req), nativeTransfer.transfer(target.actor(), target.project(),
                new ImportRequest(req.requestKey(), req.expectedUserId(), typed(reordered))));
    }

    @Test void viewerAuthenticationCsrfCapabilitiesAndDefaultFileSelection() throws Exception {
        var f = fixture();
        mvc.perform(get(url(f))).andExpect(status().isUnauthorized());
        mvc.perform(get(url(f) + "/capabilities").with(user(f.actor()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope").value("API_DEFINITIONS"))
                .andExpect(jsonPath("$.data.versions[0]").value(1)).andExpect(jsonPath("$.data.versions.length()").value(3))
                .andExpect(jsonPath("$.data.maxBytes").value(8388608)).andExpect(jsonPath("$.data.fileSnapshotMaxBytes").value(33554432));
        mvc.perform(get(url(f)).with(user(f.actor()))).andExpect(status().isOk()).andExpect(jsonPath("$.data.files").doesNotExist());
        mvc.perform(post(url(f)).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(request(f, snapshot()).toString()))
                .andExpect(status().isForbidden());
        jdbc.sql("UPDATE membership SET role_code='VIEWER' WHERE user_id=:user").param("user", f.actor().userId()).update();
        mvc.perform(get(url(f)).with(user(f.actor()))).andExpect(status().isOk());
        transfer(f, request(f, snapshot())).andExpect(status().isForbidden()); empty(f);
    }

    @Test void disabledAndMissingEnvironmentsAndVariablesFailUntilDependenciesExist() throws Exception {
        var f = fixture(); ObjectNode s = snapshot(); var c = (ObjectNode) s.at("/cases/0");
        c.put("environmentCode", "qa");
        ((ObjectNode) c.path("requestSnapshot")).put("baseUrl", "{{HOST}}").putObject("auth").put("token", "{{TOKEN}}");
        Snapshot dependent = typed(s); rejected(f, dependent, 409);
        environments.create(f.actor(), f.project(), new ProjectEnvironmentService.CreateInput("QA", "qa", "", "", false, false));
        rejected(f, dependent, 409);
        jdbc.sql("UPDATE project_environment SET enabled=TRUE WHERE project_id=:project AND environment_code='qa'").param("project", f.project()).update();
        var error = assertThrows(ResponseStatusException.class, () -> apply(f, dependent));
        assertEquals(409, error.getStatusCode().value()); assertTrue(error.getReason().contains("qa:HOST")); assertTrue(error.getReason().contains("qa:TOKEN"));
        parameters.upsert(f.actor(), f.project(), "qa", "HOST", "https://target.example.test");
        parameters.upsert(f.actor(), f.project(), "qa", "TOKEN", "target-value-do-not-transfer");
        apply(f, dependent);
        var saved = design.cases(f.actor(), f.project()).getFirst();
        assertEquals("qa", saved.environmentCode()); assertEquals("{{TOKEN}}", saved.requestSnapshot().path("auth").path("token").asText());
        assertFalse(export(f).toString().contains("target-value-do-not-transfer"));
    }

    @Test void malformedReferencesDuplicateNamesAndDomainBoundsLeaveNoRows() throws Exception {
        var f = fixture(); ObjectNode base = snapshot();
        List<java.util.function.Consumer<ObjectNode>> invalid = List.of(
                s -> ((ObjectNode) s.at("/catalogs/0")).put("parentId", id()),
                s -> ((ObjectNode) s.at("/definitions/0")).put("catalogId", id()),
                s -> ((ObjectNode) s.at("/definitions/0")).put("status", "DRAFT"),
                s -> ((ObjectNode) s.at("/definitions/0")).put("method", "INVALID"),
                s -> ((ObjectNode) s.at("/definitions/0")).put("path", "relative"),
                s -> ((ObjectNode) s.at("/versions/0")).put("apiId", id()),
                s -> ((ObjectNode) s.at("/versions/0")).put("versionName", "invalid"),
                s -> ((ObjectNode) s.at("/versions/0")).putNull("createdAt"),
                s -> ((ObjectNode) s.at("/versions/0")).putNull("publishedAt"),
                s -> ((ObjectNode) s.at("/versions/0")).put("description", "x".repeat(20001)),
                s -> ((ObjectNode) s.at("/cases/0")).put("apiId", id()),
                s -> ((ObjectNode) s.at("/cases/0")).put("id", s.at("/versions/0/id").asText()),
                s -> ((ObjectNode) s.at("/cases/0")).put("expectedStatus", 600),
                s -> ((ObjectNode) s.at("/cases/0")).putArray("requestSnapshot"),
                s -> ((ObjectNode) s.at("/cases/0/requestSnapshot")).put("baseUrl", "https://user:password@example.test"),
                s -> ((ObjectNode) s.at("/cases/0/requestSnapshot")).put("environmentCode", "test"),
                s -> ((ObjectNode) s.at("/cases/0")).put("savedResponseBody", "x".repeat(1_000_001)),
                s -> ((ObjectNode) s.at("/cases/0")).put("responseValidation", "x".repeat(20_001)));
        for (var edit : invalid) { ObjectNode s = base.deepCopy(); edit.accept(s); rejected(f, typed(s), 400); }
        ObjectNode duplicate = base.deepCopy(); ((ObjectNode) duplicate.at("/versions/1")).put("versionName", "1.0.0"); rejected(f, typed(duplicate), 409);
        duplicate = base.deepCopy(); var copy = ((ObjectNode) duplicate.at("/cases/0")).deepCopy().put("id", id());
        ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.path("cases")).add(copy); rejected(f, typed(duplicate), 409);
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> nativeTransfer.transfer(f.actor(), f.project(),
                new ImportRequest("not-a-uuid", f.actor().userId(), typed(base)))).getStatusCode().value());
    }

    ObjectNode withFiles(ObjectNode s, String... names) {
        var files = s.putObject("files"); files.put("projectId", s.path("sourceProjectId").asText()); var items = files.putArray("items");
        for (String name : names) {
            byte[] bytes = {1, 2, 3};
            items.addObject().put("id", id()).put("name", name).put("type", "text/plain").put("size", bytes.length)
                    .put("sha256", FunctionalTransferFiles.sha256(bytes)).put("content", bytes);
        }
        return s;
    }

    @Test void fileLinksRewriteEveryVersionNestedJsonEscapedBodiesAndCaseFields() throws Exception {
        var f = fixture(); ObjectNode s = withFiles(snapshot(), "fixture.txt");
        String sourceFile = s.at("/files/items/0/id").asText();
        String link = "/api/v1/projects/" + s.path("sourceProjectId").asText() + "/files/" + sourceFile + "/download";
        String body = "{\"nested\":[{\"link\":\"" + link.replace("/", "\\/") + "\"}],\"quote\":\"a\\\"b\"}";
        for (JsonNode node : s.path("versions")) {
            var v = (ObjectNode) node; v.put("description", link).put("requestBody", body).put("responseBody", body);
            v.putObject("exampleResponse").putArray("links").add(link);
            v.putArray("headers").addObject().put("name", "X-Link").put("type", "string").put("required", false).put("example", link).put("description", "");
        }
        ((ObjectNode) s.at("/versions/0")).put("summary", "https://external.example" + link);
        var c = (ObjectNode) s.at("/cases/0"); c.put("savedResponseBody", body).put("responseValidation", body);
        ((ObjectNode) c.path("requestSnapshot")).put("body", body).putObject("nested").putArray("links").add(link);
        var receipt = apply(f, typed(s)); assertEquals(1, receipt.files().items()); assertEquals(3, receipt.files().bytes());
        var file = projectFiles.list(f.actor(), f.project()).getFirst(); assertNotEquals(sourceFile, file.id());
        String replacement = "/api/v1/projects/" + f.project() + "/files/" + file.id() + "/download";
        var api = design.definitions(f.actor(), f.project(), null).getFirst();
        for (var v : design.versions(f.actor(), f.project(), api.id())) {
            assertEquals(replacement, v.description());
            assertEquals(replacement, json.readTree(v.requestBody()).at("/nested/0/link").asText());
            assertEquals(replacement, json.readTree(v.responseBody()).at("/nested/0/link").asText());
            assertEquals("a\"b", json.readTree(v.requestBody()).path("quote").asText());
            assertEquals(replacement, json.readTree(v.requestHeaders()).at("/0/example").asText());
            assertEquals(replacement, json.readTree(v.exampleResponse()).at("/links/0").asText());
            if (v.versionName().equals("1.0.0")) assertEquals("https://external.example" + link, v.summary());
        }
        var saved = design.cases(f.actor(), f.project()).getFirst();
        assertEquals(replacement, saved.requestSnapshot().at("/nested/links/0").asText());
        assertEquals(replacement, json.readTree(saved.requestSnapshot().path("body").asText()).at("/nested/0/link").asText());
        assertEquals(replacement, json.readTree(saved.savedResponseBody()).at("/nested/0/link").asText());
        assertEquals(replacement, json.readTree(saved.responseValidation()).at("/nested/0/link").asText());
        try (var input = projectFiles.download(f.actor(), f.project(), file.id())) { assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes()); }
        var exported = nativeTransfer.export(f.actor(), f.project(), true); assertEquals(1, exported.files().items().size());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> nativeTransfer.export(f.actor(), f.project(), false)).getStatusCode().value());
        ObjectNode noFiles = s.deepCopy(); noFiles.remove("files"); rejected(fixture(), typed(noFiles), 409);
        ObjectNode missing = s.deepCopy(); ((ObjectNode) missing.path("files")).putArray("items"); rejected(fixture(), typed(missing), 400);
    }

    @Test void storageAndLateReceiptFailuresRollbackFilesAssetsVersionsAndReceipt() throws Exception {
        var f = fixture(); Snapshot s = typed(withFiles(snapshot(), "one.txt", "two.txt"));
        var storage = (TransferStorage) objectStorage; storage.failAfter.set(1);
        try { rejected(f, s, 502); } finally { storage.failAfter.set(-1); }
        assertTrue(storage.objects.keySet().stream().noneMatch(key -> key.startsWith(f.project() + "/")));
        assertEquals(0, count("file_transfer_intent", f));
        var req = typedRequest(f, s);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("injected receipt failure"))
                .when(transferRepository).saveReceipt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        try {
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> nativeTransfer.transfer(f.actor(), f.project(), req)).getStatusCode().value());
        } finally { org.mockito.Mockito.reset(transferRepository); }
        empty(f); assertEquals(0, count("file_object", f));
        assertTrue(storage.objects.keySet().stream().noneMatch(key -> key.startsWith(f.project() + "/")));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM api_definition_version v LEFT JOIN api_definition d ON v.api_id=d.id WHERE d.id IS NULL").query(Integer.class).single());
        var receipt = nativeTransfer.transfer(f.actor(), f.project(), req);
        assertEquals(2, receipt.versions()); assertEquals(1, receipt.cases()); assertEquals(2, count("file_object", f));
    }

    @Test void cardinalityDepthDomainAndWireSizeLimitsRejectBeforeWrites() throws Exception {
        var f = fixture(); Snapshot s = typed(snapshot());
        rejected(f, new Snapshot(1, s.sourceProjectId(), Collections.nCopies(5001, s.catalogs().getFirst()), s.definitions(), s.versions(), s.cases(), null), 400);
        rejected(f, new Snapshot(1, s.sourceProjectId(), s.catalogs(), Collections.nCopies(5001, s.definitions().getFirst()), s.versions(), s.cases(), null), 400);
        rejected(f, new Snapshot(1, s.sourceProjectId(), s.catalogs(), s.definitions(), Collections.nCopies(10001, s.versions().getFirst()), s.cases(), null), 400);
        rejected(f, new Snapshot(1, s.sourceProjectId(), s.catalogs(), s.definitions(), s.versions(), Collections.nCopies(10001, s.cases().getFirst()), null), 400);
        List<Catalog> deep = new ArrayList<>(); String parent = null;
        for (int i = 0; i < 65; i++) { String next = id(); deep.add(new Catalog(next, parent, "层级", i)); parent = next; }
        rejected(f, new Snapshot(1, s.sourceProjectId(), deep, List.of(), List.of(), List.of(), null), 400);
        ObjectNode large = snapshot(); ((ObjectNode) large.at("/cases/0/requestSnapshot")).put("body", "x".repeat(2_000_001)); rejected(f, typed(large), 400);
        large = snapshot(); ((ObjectNode) large.at("/versions/0")).put("requestBody", "x".repeat(2_000_001)); rejected(f, typed(large), 400);
        large = snapshot();
        for (JsonNode v : large.path("versions")) ((ObjectNode) v).put("requestBody", "x".repeat(2_000_000)).put("responseBody", "x".repeat(2_000_000));
        ((ObjectNode) large.at("/cases/0")).put("savedResponseBody", "x".repeat(1_000_000));
        rejected(f, typed(large), 413);
        assertEquals(413, assertThrows(ResponseStatusException.class, () -> nativeTransfer.decode(new byte[ApiTransferService.FILE_SNAPSHOT_MAX_BYTES + 1])).getStatusCode().value());
        byte[] padded = (" ".repeat(ApiTransferService.MAX_BYTES) + request(f, snapshot())).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(413, assertThrows(ResponseStatusException.class, () -> nativeTransfer.decode(padded)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> nativeTransfer.decode("{} {}".getBytes(java.nio.charset.StandardCharsets.UTF_8))).getStatusCode().value());
        empty(f);
    }

    @Autowired io.testhub.platform.apidebug.GrpcDescriptorService grpcDescriptors;
    @Test void grpcV2TransfersCompiledDescriptorsAndRemapsReferencesWithV1Preserved() throws Exception {
        var source = fixture(); var target = fixture();
        var file = new org.springframework.mock.web.MockMultipartFile("files", "echo.proto", "text/plain", """
                syntax = "proto3";
                package testhub.transfer;
                message Echo { string text = 1; }
                service EchoService { rpc EchoCall(Echo) returns (Echo); }
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var original = grpcDescriptors.importFiles(source.actor(), source.project(), "Echo", List.of(file));
        ObjectNode base = snapshot(); apply(source, typed(base));
        String sourceApi = design.definitions(source.actor(), source.project(), null).getFirst().id();
        var c = design.cases(source.actor(), source.project()).getFirst();
        ObjectNode draft = c.requestSnapshot().deepCopy(); draft.putObject("grpc").put("descriptorId", original.id());
        design.updateCase(source.actor(), source.project(), sourceApi, c.id(), new ApiDesignService.CaseInput(c.name(), null, draft, 200, "{}", "{}"));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> nativeTransfer.export(source.actor(), source.project(), false)).getStatusCode().value());
        var snapshot = nativeTransfer.export(source.actor(), source.project(), false, true);
        assertEquals(2, snapshot.version()); assertEquals(1, snapshot.grpcDescriptors().size());
        var conflicting = fixture(); grpcDescriptors.importFiles(conflicting.actor(), conflicting.project(), "Echo", List.of(file));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> apply(conflicting, snapshot)).getStatusCode().value());
        empty(conflicting); assertEquals(1, grpcDescriptors.list(conflicting.actor(), conflicting.project()).size());
        var req = typedRequest(target, snapshot); var receipt = nativeTransfer.transfer(target.actor(), target.project(), req);
        assertEquals(1, receipt.grpcDescriptors());
        assertEquals(receipt, nativeTransfer.transfer(target.actor(), target.project(), req));
        String targetDescriptor = grpcDescriptors.list(target.actor(), target.project()).getFirst().id();
        assertNotEquals(original.id(), targetDescriptor);
        assertEquals(targetDescriptor, design.cases(target.actor(), target.project()).getFirst().requestSnapshot().at("/grpc/descriptorId").asText());
        assertEquals(List.of("testhub.transfer.EchoService"), grpcDescriptors.discover(target.actor(), target.project(), targetDescriptor, null).services());
        assertEquals("EchoCall", grpcDescriptors.discover(target.actor(), target.project(), targetDescriptor, "testhub.transfer.EchoService").methods().getFirst().name());
        assertEquals(json.valueToTree(snapshot), json.valueToTree(nativeTransfer.export(source.actor(), source.project(), false, true)));
    }

    @Test void grpcV2RejectsCorruptBytesAndMissingReferencesAtomically() throws Exception {
        var f = fixture(); ObjectNode s = snapshot().put("version", 2);
        s.putArray("grpcDescriptors").addObject().put("id", id()).put("name", "bad").put("sha256", "0".repeat(64)).put("content", "YQ==");
        transfer(f, request(f, s)).andExpect(status().isBadRequest()); empty(f);
        ((ObjectNode) s.at("/grpcDescriptors/0")).put("sha256", FunctionalTransferFiles.sha256(new byte[]{97}));
        transfer(f, request(f, s)).andExpect(status().isBadRequest()); empty(f);
        s.putArray("grpcDescriptors"); ((ObjectNode) s.at("/cases/0/requestSnapshot")).putObject("grpc").put("descriptorId", id());
        transfer(f, request(f, s)).andExpect(status().isConflict()); empty(f);
        assertTrue(grpcDescriptors.list(f.actor(), f.project()).isEmpty());
    }

    @Autowired io.testhub.platform.apidebug.ApiDebugHistoryRepository historyRepository;
    @Autowired io.testhub.platform.apidebug.ApiDebugService debugService;
    @Autowired io.testhub.platform.security.SecretCipher cipher;
    void history(Fixture f, String encrypted, String historyId) {
        historyRepository.insert(new io.testhub.platform.apidebug.ApiDebugHistory(historyId, access.context(f.actor()).organizations().getFirst().id(),
                f.project(), "GET", "HTTP", "https://fixture.example.test/path", 200, 15, 30, "COMPLETED", null,
                encrypted, f.actor().userId(), java.time.Instant.parse("2026-09-18T01:02:03Z")));
    }
    @Test void historyV3TransfersMoreThanRecentLimitAndReencryptsWithProvenanceAndRetrySafety() throws Exception {
        var source = fixture(); var target = fixture();
        String draft = "{\"protocol\":\"HTTP\",\"request\":{\"method\":\"GET\",\"url\":\"https://fixture.example.test/path\",\"auth\":{\"token\":\"history-fixture-secret\"}}}";
        String encrypted = cipher.encrypt(draft);
        for (int n = 0; n < 125; n++) history(source, encrypted, id());
        assertNull(nativeTransfer.export(source.actor(), source.project(), false).debugHistory());
        var snapshot = nativeTransfer.export(source.actor(), source.project(), false, false, true);
        assertEquals(3, snapshot.version()); assertEquals(125, snapshot.debugHistory().size());
        var request = typedRequest(target, snapshot); var receipt = nativeTransfer.transfer(target.actor(), target.project(), request);
        assertEquals(125, receipt.debugHistory()); assertEquals(receipt, nativeTransfer.transfer(target.actor(), target.project(), request));
        var saved = historyRepository.findRecent(target.project(), 1000); assertEquals(125, saved.size());
        for (var row : saved) {
            assertNotEquals(encrypted, row.replayDraft()); assertEquals(json.readTree(draft), json.readTree(cipher.decrypt(row.replayDraft())));
            assertEquals(source.project(), row.importSourceProjectId()); assertNotNull(row.importSourceId());
            assertEquals(target.actor().userId(), row.createdBy()); assertEquals(java.time.Instant.parse("2026-09-18T01:02:03Z"), row.createdAt());
        }
        assertEquals(source.project(), debugService.history(target.actor(), target.project(), 1).getFirst().importSourceProjectId());
        assertEquals(125, historyRepository.findRecent(source.project(), 1000).size());
    }
    @Test void historyExportRetainsWriterPermissionAndFailsClosedOnCorruptCiphertext() throws Exception {
        var f = fixture(); history(f, "unreadable-ciphertext", id());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> nativeTransfer.export(f.actor(), f.project(), false, false, true)).getStatusCode().value());
        jdbc.sql("UPDATE membership SET role_code='VIEWER' WHERE user_id=:user").param("user", f.actor().userId()).update();
        assertNull(nativeTransfer.export(f.actor(), f.project(), false).debugHistory());
        mvc.perform(get(url(f)).param("includeHistory", "true").with(user(f.actor()))).andExpect(status().isForbidden());
    }
    @Test void historyDependenciesAndLateFailureLeaveNoImportedHistoryOrAssets() throws Exception {
        var source = fixture(); var target = fixture();
        String draft = "{\"protocol\":\"HTTP\",\"request\":{\"environmentCode\":\"missing\",\"url\":\"{{HOST}}\"}}";
        history(source, cipher.encrypt(draft), id());
        var snapshot = nativeTransfer.export(source.actor(), source.project(), false, false, true);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> apply(target, snapshot)).getStatusCode().value());
        assertEquals(0, count("api_debug_history", target)); empty(target);
        var good = fixture(); history(good, cipher.encrypt("{\"protocol\":\"HTTP\",\"request\":{}}"), id());
        var goodSnapshot = nativeTransfer.export(good.actor(), good.project(), false, false, true);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("injected receipt failure"))
                .when(transferRepository).saveReceipt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        try { assertEquals(409, assertThrows(ResponseStatusException.class, () -> apply(target, goodSnapshot)).getStatusCode().value()); }
        finally { org.mockito.Mockito.reset(transferRepository); }
        assertEquals(0, count("api_debug_history", target)); empty(target);
        apply(target, goodSnapshot); assertEquals(1, count("api_debug_history", target));
    }

    @Test void historyRejectsStoredBudgetBeforeDecryptionAndMalformedReplayBeforeWrites() throws Exception {
        var source = fixture(); history(source, "x".repeat(8 * 1024 * 1024), id());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> nativeTransfer.export(source.actor(), source.project(), false, false, true)).getStatusCode().value());
        var target = fixture(); ObjectNode s = snapshot().put("version", 3);
        var h = s.putArray("debugHistory").addObject().put("id", id()).put("method", "GET").put("protocol", "HTTP")
                .put("requestUrl", "https://fixture.example.test").put("responseStatus", 200).put("durationMs", 1).put("responseSize", 0)
                .put("result", "COMPLETED").put("createdAt", "2026-09-18T00:00:00Z");
        h.putObject("replayDraft").put("protocol", "GRPC").putObject("request");
        transfer(target, request(target, s)).andExpect(status().isBadRequest()); empty(target); assertEquals(0, count("api_debug_history", target));
        ((ObjectNode) h.get("replayDraft")).put("protocol", "HTTP");
        ((ObjectNode) h.at("/replayDraft/request")).put("descriptorId", id());
        transfer(target, request(target, s)).andExpect(status().isConflict()); empty(target); assertEquals(0, count("api_debug_history", target));
    }

    @Test void historyRewritesSelectedFileAndDescriptorReferencesBeforeReencrypting() throws Exception {
        var source = fixture(); var target = fixture();
        var proto = new org.springframework.mock.web.MockMultipartFile("files", "history.proto", "text/plain", """
                syntax = "proto3"; package fixture.history;
                message Echo { string text = 1; }
                service EchoService { rpc Call(Echo) returns (Echo); }
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var descriptor = grpcDescriptors.importFiles(source.actor(), source.project(), "history", List.of(proto));
        ObjectNode s = withFiles(snapshot(), "history.txt").put("version", 3);
        s.set("grpcDescriptors", json.valueToTree(nativeTransfer.export(source.actor(), source.project(), false, true).grpcDescriptors()));
        String path = "/api/v1/projects/" + s.path("sourceProjectId").asText() + "/files/" + s.at("/files/items/0/id").asText() + "/download";
        var h = s.putArray("debugHistory").addObject().put("id", id()).put("method", "GRPC").put("protocol", "GRPC")
                .put("requestUrl", "grpc://fixture.example.test/fixture.history.EchoService/Call").put("responseStatus", 0)
                .put("durationMs", 1).put("responseSize", 1).put("result", "COMPLETED").put("createdAt", "2026-09-18T00:00:00Z");
        var request = h.putObject("replayDraft").put("protocol", "GRPC").putObject("request");
        request.put("descriptorId", descriptor.id()).put("requestJson", "{\"url\":\"" + path + "\"}");
        apply(target, typed(s));
        var imported = debugService.history(target.actor(), target.project(), 1).getFirst();
        var draft = json.readTree(imported.replayDraft()).path("request");
        String newDescriptor = grpcDescriptors.list(target.actor(), target.project()).getFirst().id();
        assertEquals(newDescriptor, draft.path("descriptorId").asText()); assertNotEquals(descriptor.id(), newDescriptor);
        String newPath = "/api/v1/projects/" + target.project() + "/files/" + projectFiles.list(target.actor(), target.project()).getFirst().id() + "/download";
        assertEquals(newPath, json.readTree(draft.path("requestJson").asText()).path("url").asText());
        assertEquals(h.path("requestUrl").asText(), imported.requestUrl());
    }

}
