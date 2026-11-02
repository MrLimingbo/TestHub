package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.apidesign.ApiDesignService;
import io.testhub.platform.apidesign.ApiInterchangeService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class ApiDesignFixesFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        FunctionalDatabaseProfile.configure(registry, directory, "api-design-fixes");
        if (!FunctionalDatabaseProfile.mysql()) {
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired ApiDesignService design;
    @Autowired ApiInterchangeService interchange;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    record Fixture(PlatformUserPrincipal actor, String project, String catalog) { }
    Fixture fixture() {
        String account = "qa-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test", "接口回归", UUID.randomUUID() + "Aa1!"));
        var context = access.context(actor);
        String project = context.projects().get(context.organizations().getFirst().id()).getFirst().id();
        return new Fixture(actor, project, design.createCatalog(actor, project, catalog("根", null)).id());
    }
    ApiDesignService.CatalogInput catalog(String name, String parent) { return new ApiDesignService.CatalogInput(name, parent, 0); }
    ApiDesignService.DefinitionInput definition(Fixture f, String path, List<ApiDesignService.ParameterInput> headers, String body) {
        return new ApiDesignService.DefinitionInput(f.catalog(), "POST", path, "接口", "1.0.0", "", "", headers, List.of(), List.of(), body, "{}", Map.of());
    }
    long auditCount(String project) { return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE project_id=:p").param("p", project).query(Long.class).single(); }
    static Stream<Arguments> nullParameters() {
        return Stream.of("create", "revise", "version").flatMap(operation -> Stream.of("headers", "query", "pathParameters").map(field -> Arguments.of(operation, field)));
    }
    @ParameterizedTest @MethodSource("nullParameters")
    void nullParameterElementsReturn400BeforeAnyWrite(String operation, String field) throws Exception {
        var f = fixture(); var api = design.createDefinition(f.actor(), f.project(), definition(f, "/initial", List.of(), "{}"));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("catalogId", f.catalog()); payload.put("method", "POST"); payload.put("path", "/new"); payload.put("name", "新接口"); payload.put("versionName", "1.1.0");
        payload.put(field, Collections.singletonList(null));
        String route = "/api/v1/projects/" + f.project() + "/api-design/definitions";
        if (!operation.equals("create")) route += "/" + api.id() + (operation.equals("revise") ? "/draft" : "/versions");
        long audit = auditCount(f.project());
        var request = operation.equals("revise") ? put(route) : post(route);
        mvc.perform(request.with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty()).andExpect(jsonPath("$.details").isNotEmpty());
        assertEquals(audit, auditCount(f.project()));
        assertEquals(List.of(api), design.definitions(f.actor(), f.project(), null));
        assertEquals(1, design.versions(f.actor(), f.project(), api.id()).size());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void omittedOrEmptyParametersRemainSupported(boolean empty) throws Exception {
        var f = fixture(); var payload = new LinkedHashMap<String, Object>();
        payload.put("catalogId", f.catalog()); payload.put("method", "POST"); payload.put("path", "/empty"); payload.put("name", "接口"); payload.put("versionName", "1.0.0");
        if (empty) for (String field : List.of("headers", "query", "pathParameters")) payload.put(field, List.of());
        mvc.perform(post("/api/v1/projects/{p}/api-design/definitions", f.project()).with(user(f.actor())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload)))
                .andExpect(status().isOk());
        assertEquals(1, design.definitions(f.actor(), f.project(), null).size());
    }
    void race(Supplier<?> first, Supplier<?> second) throws Exception {
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var jobs = Stream.of(first, second).map(action -> pool.submit(() -> {
                assertTrue(gate.await(10, TimeUnit.SECONDS));
                try { action.get(); return 200; }
                catch (ResponseStatusException error) { return error.getStatusCode().value(); }
            })).toList();
            gate.countDown();
            assertEquals(List.of(200, 409), List.of(jobs.get(0).get(20, TimeUnit.SECONDS), jobs.get(1).get(20, TimeUnit.SECONDS)).stream().sorted().toList());
        }
    }
    @Test void concurrentRootCreatesCommitOneAndPermitDifferentScopes() throws Exception {
        var f = fixture(); long audit = auditCount(f.project());
        race(() -> design.createCatalog(f.actor(), f.project(), catalog("并发", null)), () -> design.createCatalog(f.actor(), f.project(), catalog("并发", null)));
        assertEquals(1, design.catalogs(f.actor(), f.project()).stream().filter(c -> c.name().equals("并发")).count());
        assertEquals(audit + 1, auditCount(f.project()));
        assertNotNull(design.createCatalog(f.actor(), f.project(), catalog("并发", f.catalog())));
        var other = fixture(); assertNotNull(design.createCatalog(other.actor(), other.project(), catalog("并发", null)));
    }
    @Test void concurrentRootRenamesCommitOneTargetName() throws Exception {
        var f = fixture(); var b = design.createCatalog(f.actor(), f.project(), catalog("另根", null)); long audit = auditCount(f.project());
        race(() -> design.updateCatalog(f.actor(), f.project(), f.catalog(), catalog("共同名称", null)), () -> design.updateCatalog(f.actor(), f.project(), b.id(), catalog("共同名称", null)));
        assertEquals(1, design.catalogs(f.actor(), f.project()).stream().filter(c -> c.name().equals("共同名称")).count());
        assertEquals(audit + 1, auditCount(f.project()));
    }
    @Test void concurrentMovesToRootCommitOneAndKeepTheOtherChild() throws Exception {
        var f = fixture(); var b = design.createCatalog(f.actor(), f.project(), catalog("另根", null));
        var aChild = design.createCatalog(f.actor(), f.project(), catalog("共同名称", f.catalog()));
        var bChild = design.createCatalog(f.actor(), f.project(), catalog("共同名称", b.id())); long audit = auditCount(f.project());
        race(() -> design.updateCatalog(f.actor(), f.project(), aChild.id(), catalog("共同名称", null)), () -> design.updateCatalog(f.actor(), f.project(), bChild.id(), catalog("共同名称", null)));
        var children = design.catalogs(f.actor(), f.project()).stream().filter(c -> c.name().equals("共同名称")).toList();
        assertEquals(1, children.stream().filter(c -> c.parentId() == null).count());
        assertEquals(1, children.stream().filter(c -> c.parentId() != null).count());
        assertEquals(audit + 1, auditCount(f.project()));
    }
    @Test void concurrentCreateAndRenameShareTheSameRootGuard() throws Exception {
        var f = fixture(); long audit = auditCount(f.project());
        race(() -> design.createCatalog(f.actor(), f.project(), catalog("共同名称", null)), () -> design.updateCatalog(f.actor(), f.project(), f.catalog(), catalog("共同名称", null)));
        assertEquals(1, design.catalogs(f.actor(), f.project()).stream().filter(c -> c.name().equals("共同名称")).count());
        assertEquals(audit + 1, auditCount(f.project()));
    }
    @ParameterizedTest @ValueSource(strings = {"\"4.0.0\"", "\"3.2.0\"", "\"2.0.0\"", "\"3.1\"", "\"3.0.0-extra\"", "\"\"", "null", "31", "{}"})
    void invalidOpenApiVersionsAreRejectedAtomically(String version) {
        var f = fixture(); long audit = auditCount(f.project());
        String document = "{\"openapi\":" + version + ",\"paths\":{\"/qa\":{\"get\":{\"responses\":{\"200\":{}}}}}}";
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> interchange.importOpenApi(f.actor(), f.project(), new ApiInterchangeService.OpenApiImportInput(f.catalog(), document))).getStatusCode().value());
        assertTrue(design.definitions(f.actor(), f.project(), null).isEmpty()); assertEquals(audit, auditCount(f.project()));
    }
    @ParameterizedTest @ValueSource(strings = {"3.0.0", "3.0.3", "3.1.0", "3.1.1"})
    void supportedOpenApiVersionsStillImport(String version) {
        var f = fixture(); String document = "{\"openapi\":\"" + version + "\",\"paths\":{\"/qa\":{\"get\":{\"responses\":{\"200\":{}}}}}}";
        assertEquals(1, interchange.importOpenApi(f.actor(), f.project(), new ApiInterchangeService.OpenApiImportInput(f.catalog(), document)).importedCount());
    }
    @Test void swaggerRequires20AndRejectsMixedFormatDeclarations() {
        var f = fixture();
        for (String fields : List.of("\"swagger\":\"2.1\"", "\"swagger\":2.0", "\"swagger\":\"2.0\",\"openapi\":\"4.0.0\"")) {
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> interchange.importOpenApi(f.actor(), f.project(), new ApiInterchangeService.OpenApiImportInput(f.catalog(), "{" + fields + ",\"paths\":{\"/qa\":{\"get\":{}}}}"))).getStatusCode().value());
        }
        assertEquals(1, interchange.importOpenApi(f.actor(), f.project(), new ApiInterchangeService.OpenApiImportInput(f.catalog(), "swagger: '2.0'\npaths:\n  /qa:\n    get: {}\n")).importedCount());
    }
    static Stream<Arguments> methods() { return Stream.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").flatMap(method -> Stream.of(false, true).map(before -> Arguments.of(method, before))); }
    @ParameterizedTest @MethodSource("methods")
    void explicitMethodSurvivesDataInEitherOrder(String method, boolean before) {
        var f = fixture(); String flags = before ? "-X " + method + " --data '{}'" : "--data '{}' --request " + method;
        interchange.importCurl(f.actor(), f.project(), new ApiInterchangeService.CurlImportInput(f.catalog(), "方法顺序", "curl " + flags + " https://api.example.test/method"));
        var api = design.definitions(f.actor(), f.project(), null).getFirst(); assertEquals(method, api.method());
        assertEquals("{}", design.versions(f.actor(), f.project(), api.id()).getFirst().requestBody());
    }
    @Test void implicitCurlMethodStillDefaultsToPostOnlyWithData() {
        var f = fixture();
        interchange.importCurl(f.actor(), f.project(), new ApiInterchangeService.CurlImportInput(f.catalog(), "默认GET", "curl https://api.example.test/get"));
        interchange.importCurl(f.actor(), f.project(), new ApiInterchangeService.CurlImportInput(f.catalog(), "默认POST", "curl --data '{}' https://api.example.test/post"));
        assertTrue(design.definitions(f.actor(), f.project(), null).stream().anyMatch(a -> a.method().equals("GET") && a.path().equals("/get")));
        assertTrue(design.definitions(f.actor(), f.project(), null).stream().anyMatch(a -> a.method().equals("POST") && a.path().equals("/post")));
    }
    @ParameterizedTest @ValueSource(strings = {"O'Reilly", "中文 '单引号' 和 \"双引号\"", "a\\b'c\"d", "first\nsecond'line"})
    void curlExportQuotesUrlHeadersAndBodyAndRoundTrips(String value) throws Exception {
        var source = fixture(); var target = fixture(); String body = json.writeValueAsString(Map.of("text", value));
        var header = new ApiDesignService.ParameterInput("X-'QA", "string", false, value, "");
        var api = design.createDefinition(source.actor(), source.project(), definition(source, "/quote'path", List.of(header), body));
        String command = interchange.exportCurl(source.actor(), source.project(), api.id(), "https://api.example.test");
        if (Files.isExecutable(Path.of("/bin/sh"))) {
            var syntax = new ProcessBuilder("/bin/sh", "-n").start();
            syntax.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8)); syntax.getOutputStream().close();
            assertTrue(syntax.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, syntax.exitValue());
        }
        interchange.importCurl(target.actor(), target.project(), new ApiInterchangeService.CurlImportInput(target.catalog(), "往返", command));
        var copy = design.definitions(target.actor(), target.project(), null).getFirst(); var version = design.versions(target.actor(), target.project(), copy.id()).getFirst();
        assertEquals(api.path(), copy.path()); assertEquals(api.method(), copy.method()); assertEquals(body, version.requestBody());
        var restored = json.readTree(version.requestHeaders()).get(0); assertEquals(header.name(), restored.path("name").asText()); assertEquals(value, restored.path("example").asText());
    }
}
