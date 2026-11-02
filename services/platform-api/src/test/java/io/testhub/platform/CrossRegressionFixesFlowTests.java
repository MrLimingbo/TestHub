package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.parameter.EnvironmentSecretService;
import io.testhub.platform.parameter.ProjectEnvironmentService;
import io.testhub.platform.parameter.ProjectParameterService;
import io.testhub.platform.storage.FileObject;
import io.testhub.platform.storage.FileObjectService;
import io.testhub.platform.storage.FileTransferStorage;
import io.testhub.platform.storage.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {"testhub.registration.enabled=true", "testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver = FunctionalDatabaseProfile.class)
@DirtiesContext
@Import(CrossRegressionFixesFlowTests.StorageConfiguration.class)
class CrossRegressionFixesFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        FunctionalDatabaseProfile.configure(registry, directory, "cross-regression-fixture");
        if (!FunctionalDatabaseProfile.mysql()) {
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa");
        }
    }

    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired ProjectParameterService parameters;
    @Autowired ProjectEnvironmentService environments;
    @Autowired EnvironmentSecretService secrets;
    @Autowired FileObjectService files;
    @Autowired FileTransferStorage transfer;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ControlledStorage storage;
    @Value("${testhub.storage.bucket}") String bucket;
    private static final byte[] BYTES = "中文QA\r\nfile\0".getBytes(StandardCharsets.UTF_8);

    @TestConfiguration
    static class StorageConfiguration {
        @Bean @Primary ControlledStorage qaControlledStorage(ApplicationContext context, @Value("${testhub.storage.type}") String type) {
            return new ControlledStorage(context.getBean(type.equals("filesystem") ? "filesystemObjectStorage" : "minioObjectStorage", ObjectStorage.class));
        }
    }
    static class ControlledStorage implements ObjectStorage {
        final ObjectStorage delegate;
        boolean failRemove;
        String lastWrittenKey;
        ControlledStorage(ObjectStorage delegate) { this.delegate = delegate; }
        @Override public boolean bucketExists(String bucket) throws Exception { return delegate.bucketExists(bucket); }
        @Override public void ensureBucket(String bucket) throws Exception { delegate.ensureBucket(bucket); }
        @Override public void put(String bucket, String key, InputStream input, long size, String type) throws Exception {
            lastWrittenKey = key; delegate.put(bucket, key, input, size, type);
        }
        @Override public InputStream get(String bucket, String key) throws Exception { return delegate.get(bucket, key); }
        @Override public void remove(String bucket, String key) throws Exception {
            if (failRemove) throw new IOException("owned cleanup failure");
            delegate.remove(bucket, key);
        }
        @Override public String label() { return delegate.label(); }
    }

    record Fixture(PlatformUserPrincipal actor, String project) { }
    Fixture fixture() {
        String account = "cross-fix-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test", "横向回归", UUID.randomUUID() + "Aa1!"));
        var context = access.context(actor);
        return new Fixture(actor, context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    String route(Fixture f) { return "/api/v1/projects/" + f.project() + "/files"; }
    FileObject upload(Fixture f) { return files.upload(f.actor(), f.project(), new MockMultipartFile("file", "qa.txt", "text/plain", BYTES)); }
    long count(String table, Fixture f) { return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id=:p").param("p", f.project()).query(Long.class).single(); }
    long auditCount(Fixture f, String action) { return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE project_id=:p AND action=:a").param("p", f.project()).param("a", action).query(Long.class).single(); }
    void assertBytes(Fixture f, FileObject file) throws Exception {
        try (var input = files.download(f.actor(), f.project(), file.id())) { assertArrayEquals(BYTES, input.readAllBytes()); }
    }
    String blockAudit(Fixture f, String action) {
        String name = "qa_audit_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("ALTER TABLE audit_log ADD CONSTRAINT " + name + " CHECK (action <> '" + action + "' OR project_id <> '" + f.project() + "')").update();
        return name;
    }
    void unblockAudit(String name) { jdbc.sql("ALTER TABLE audit_log DROP " + (FunctionalDatabaseProfile.mysql() ? "CHECK " : "CONSTRAINT ") + name).update(); }

    static Stream<Arguments> parameterBoundaries() {
        return Stream.of(127, 128, 129).flatMap(length -> Stream.of(4, 64).flatMap(envLength -> Stream.of(false, true)
                .map(secret -> Arguments.of(length, envLength, secret))));
    }
    @ParameterizedTest @MethodSource("parameterBoundaries")
    void publicNameBoundariesRetainFullAuditIdentifierAndStableRecord(int length, int envLength, boolean secret) {
        var f = fixture(); String env = "e".repeat(envLength), key = "K".repeat(length);
        environments.create(f.actor(), f.project(), new ProjectEnvironmentService.CreateInput("参数边界环境", env, "", "", true, false));
        if (length > 128) {
            var error = assertThrows(ResponseStatusException.class, () -> {
                if (secret) secrets.upsert(f.actor(), f.project(), env, key, "qa-value");
                else parameters.upsert(f.actor(), f.project(), env, key, "qa-value");
            });
            assertEquals(400, error.getStatusCode().value()); assertEquals(0, count("project_parameter", f)); return;
        }
        String action = secret ? "ENVIRONMENT_SECRET_UPSERTED" : "PROJECT_PARAMETER_UPSERTED";
        for (String value : List.of("first", "updated")) {
            if (secret) secrets.upsert(f.actor(), f.project(), env, key, value);
            else parameters.upsert(f.actor(), f.project(), env, key, value);
        }
        assertEquals(1, count("project_parameter", f)); assertEquals(2, auditCount(f, action));
        assertTrue(jdbc.sql("SELECT resource_id FROM audit_log WHERE project_id=:p AND action=:a")
                .param("p", f.project()).param("a", action).query(String.class).list().stream().allMatch((env + ":" + key)::equals));
        if (secret) assertFalse(jdbc.sql("SELECT parameter_value FROM project_parameter WHERE project_id=:p").param("p", f.project()).query(String.class).single().contains("updated"));
        else assertEquals("updated", parameters.list(f.actor(), f.project(), env).parameters().getFirst().value());
    }

    @ParameterizedTest @ValueSource(strings = {"BASE_URL", "base_url", "Base_Url"})
    void reservedBaseUrlCannotBeWrittenAsOrdinaryParameterOrSecret(String key) {
        var f = fixture(); var environment = environments.list(f.actor(), f.project()).stream().filter(e -> e.environmentCode().equals("test")).findFirst().orElseThrow();
        environments.update(f.actor(), f.project(), environment.id(), new ProjectEnvironmentService.UpdateInput(environment.name(), "https://base.example.test", "", true, environment.version()));
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> parameters.upsert(f.actor(), f.project(), "test", key, "overwrite")).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> secrets.upsert(f.actor(), f.project(), "test", key, "overwrite")).getStatusCode().value());
        assertEquals("https://base.example.test", parameters.list(f.actor(), f.project(), "test").parameters().getFirst().value());
        assertTrue(secrets.list(f.actor(), f.project(), "test").secrets().isEmpty());
    }

    @Test void baseUrlDeletionIsProtectedWhileOrdinaryDeleteAndEnvironmentClearStillWork() {
        var f = fixture(); var environment = environments.list(f.actor(), f.project()).stream().filter(e -> e.environmentCode().equals("test")).findFirst().orElseThrow();
        var updated = environments.update(f.actor(), f.project(), environment.id(), new ProjectEnvironmentService.UpdateInput(environment.name(), "https://base.example.test", "", true, environment.version()));
        var base = parameters.list(f.actor(), f.project(), "test").parameters().getFirst();
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> parameters.delete(f.actor(), f.project(), base.id())).getStatusCode().value());
        assertEquals("https://base.example.test", parameters.list(f.actor(), f.project(), "test").parameters().getFirst().value());
        parameters.upsert(f.actor(), f.project(), "test", "ORDINARY", "value");
        var ordinary = parameters.list(f.actor(), f.project(), "test").parameters().stream().filter(p -> p.key().equals("ORDINARY")).findFirst().orElseThrow();
        parameters.delete(f.actor(), f.project(), ordinary.id());
        environments.update(f.actor(), f.project(), environment.id(), new ProjectEnvironmentService.UpdateInput(environment.name(), "", "", true, updated.version()));
        assertTrue(parameters.list(f.actor(), f.project(), "test").parameters().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"application/json", "text/plain", "application/xml"})
    void wrongUploadBodyTypeReturns415WithoutSideEffects(String type) throws Exception {
        var f = fixture();
        mvc.perform(post(route(f)).with(user(f.actor())).with(csrf()).contentType(type).content("{\"notAFile\":true}"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
        assertEquals(0, count("file_object", f)); assertEquals(0, count("file_transfer_intent", f));
    }
    @Test void missingFileAndEmptyFileRemainClientErrors() throws Exception {
        var f = fixture();
        mvc.perform(multipart(route(f)).file(new MockMultipartFile("wrong", "qa.txt", "text/plain", BYTES)).with(user(f.actor())).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart(route(f)).file(new MockMultipartFile("file", "qa.txt", "text/plain", new byte[0])).with(user(f.actor())).with(csrf())).andExpect(status().isBadRequest());
        assertEquals(0, count("file_object", f));
    }
    static Stream<String> invalidTypes() { return Stream.of("not a media type", "text/plain\r\nInjected: yes", "*/*", "text/*", "text/plain;v=" + "a".repeat(250)); }
    @ParameterizedTest @MethodSource("invalidTypes")
    void invalidMediaTypeIsRejectedBeforeStorage(String type) {
        var f = fixture();
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> files.upload(f.actor(), f.project(), new MockMultipartFile("file", "qa.txt", type, BYTES))).getStatusCode().value());
        assertEquals(0, count("file_object", f)); assertEquals(0, count("file_transfer_intent", f));
    }
    @Test void validAndLegacyMediaTypesBothDownloadOriginalBytes() throws Exception {
        var f = fixture();
        var file = files.upload(f.actor(), f.project(), new MockMultipartFile("file", "中文.txt", "text/plain; charset=UTF-8", BYTES));
        mvc.perform(get(route(f) + "/" + file.id() + "/download").with(user(f.actor())))
                .andExpect(status().isOk()).andExpect(content().contentType("text/plain;charset=UTF-8")).andExpect(content().bytes(BYTES));
        jdbc.sql("UPDATE file_object SET content_type='not a media type' WHERE id=:id").param("id", file.id()).update();
        mvc.perform(get(route(f) + "/" + file.id() + "/download").with(user(f.actor())))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM)).andExpect(content().bytes(BYTES));
    }
    @Test void absentMediaTypeDefaultsToBinary() {
        var f = fixture(); var file = files.upload(f.actor(), f.project(), new MockMultipartFile("file", "qa.bin", null, BYTES));
        assertEquals("application/octet-stream", file.contentType());
    }

    @Test void auditConstraintFailureRollsBackUploadMetadataAndReclaimsObject() throws Exception {
        var f = fixture(); String constraint = blockAudit(f, "FILE_UPLOADED");
        try { assertThrows(RuntimeException.class, () -> upload(f)); }
        finally { unblockAudit(constraint); }
        assertEquals(0, count("file_object", f)); assertEquals(0, count("file_transfer_intent", f)); assertEquals(0, auditCount(f, "FILE_UPLOADED"));
        String key = storage.lastWrittenKey;
        assertNotNull(key); assertThrows(Exception.class, () -> storage.get(bucket, key));
        assertNotNull(upload(f)); assertEquals(1, count("file_object", f));
    }
    @Test void failedUploadCleanupKeepsDurableIntentAndCanRecover() throws Exception {
        var f = fixture(); String constraint = blockAudit(f, "FILE_UPLOADED");
        storage.failRemove = true;
        try { assertThrows(RuntimeException.class, () -> upload(f)); }
        finally { unblockAudit(constraint); storage.failRemove = false; }
        assertEquals(0, count("file_object", f)); assertEquals(1, count("file_transfer_intent", f));
        var row = jdbc.sql("SELECT id,object_key FROM file_transfer_intent WHERE project_id=:p").param("p", f.project()).query().singleRow();
        transfer.recover((String) row.get("id")); assertEquals(0, count("file_transfer_intent", f));
        assertThrows(Exception.class, () -> storage.get(bucket, (String) row.get("object_key")));
    }
    @Test void metadataDeleteFailurePreservesObjectAndRetryCompletes() throws Exception {
        var f = fixture(); var file = upload(f); String table = "qa_file_ref_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("CREATE TABLE " + table + "(file_id CHAR(26), FOREIGN KEY(file_id) REFERENCES file_object(id))").update();
        jdbc.sql("INSERT INTO " + table + "(file_id) VALUES(:id)").param("id", file.id()).update();
        try { assertEquals(502, assertThrows(ResponseStatusException.class, () -> files.delete(f.actor(), f.project(), file.id())).getStatusCode().value()); }
        finally { jdbc.sql("DROP TABLE " + table).update(); }
        assertEquals(file.id(), files.metadata(f.actor(), f.project(), file.id()).id()); assertBytes(f, file);
        assertEquals(0, count("file_transfer_intent", f)); assertEquals(0, auditCount(f, "FILE_DELETED"));
        files.delete(f.actor(), f.project(), file.id()); assertTrue(files.list(f.actor(), f.project()).isEmpty());
        assertThrows(Exception.class, () -> storage.get(bucket, file.objectKey())); assertEquals(1, auditCount(f, "FILE_DELETED"));
    }
    @Test void deleteAuditFailureAlsoPreservesMetadataAndBytes() throws Exception {
        var f = fixture(); var file = upload(f); String constraint = blockAudit(f, "FILE_DELETED");
        try { assertThrows(RuntimeException.class, () -> files.delete(f.actor(), f.project(), file.id())); }
        finally { unblockAudit(constraint); }
        assertEquals(file.id(), files.metadata(f.actor(), f.project(), file.id()).id()); assertBytes(f, file);
        assertEquals(0, count("file_transfer_intent", f)); assertEquals(0, auditCount(f, "FILE_DELETED"));
    }
    @Test void committedDeleteWithUnavailableCleanupRetainsIntentForRetry() throws Exception {
        var f = fixture(); var file = upload(f);
        storage.failRemove = true;
        try { files.delete(f.actor(), f.project(), file.id()); }
        finally { storage.failRemove = false; }
        assertTrue(files.list(f.actor(), f.project()).isEmpty()); assertEquals(1, count("file_transfer_intent", f));
        try (var input = storage.get(bucket, file.objectKey())) { assertArrayEquals(BYTES, input.readAllBytes()); }
        transfer.recover(file.id()); transfer.recover(file.id()); assertEquals(0, count("file_transfer_intent", f));
        assertThrows(Exception.class, () -> storage.get(bucket, file.objectKey()));
    }
    @Test void enclosingRollbackReclaimsUploadButPreservesDeletedFile() throws Exception {
        var f = fixture(); var transaction = new TransactionTemplate(manager); FileObject[] staged = new FileObject[1];
        transaction.executeWithoutResult(status -> { staged[0] = upload(f); status.setRollbackOnly(); });
        assertTrue(files.list(f.actor(), f.project()).isEmpty());
        assertThrows(Exception.class, () -> storage.get(bucket, staged[0].objectKey()));
        var file = upload(f);
        transaction.executeWithoutResult(status -> { files.delete(f.actor(), f.project(), file.id()); status.setRollbackOnly(); });
        assertEquals(file.id(), files.metadata(f.actor(), f.project(), file.id()).id()); assertBytes(f, file);
        assertEquals(0, count("file_transfer_intent", f)); assertEquals(0, auditCount(f, "FILE_DELETED"));
    }
}
