package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.storage.FileObjectService;
import io.testhub.platform.storage.ProjectFileReferenceGuard;
import io.testhub.platform.storage.ProjectFileSyncService;
import io.testhub.platform.storage.ProjectFileSyncService.Request;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"testhub.registration.enabled=true","testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles(resolver=FunctionalDatabaseProfile.class)
class ProjectFileSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry, directory, "file-sync-fixture");
        else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "file-sync-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired FileObjectService files;
    @Autowired ProjectFileSyncService sync;
    @Autowired ProjectFileReferenceGuard fileReferences;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;

    record Fixture(PlatformUserPrincipal actor, String project) { }
    Fixture fixture() {
        String name = "file-sync-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(name, name + "@example.test", "文件同步测试", "Sync-fixture-2026!"));
        var context = access.context(actor);
        return new Fixture(actor, context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    ProjectFileSyncService.Snapshot snapshot(Fixture fixture) {
        return sync.snapshot(fixture.actor(), fixture.project(), fixture.actor().userId());
    }
    ProjectFileSyncService.Item upload(Fixture fixture, String name, byte[] bytes) {
        files.upload(fixture.actor(), fixture.project(), new MockMultipartFile("file", name, "text/plain", bytes));
        return snapshot(fixture).items().getLast();
    }
    Request request(Fixture target, ProjectFileSyncService.Item source, byte[] bytes) {
        return new Request(UUID.randomUUID().toString(), target.actor().userId(), snapshot(target).revision(), source.key(),
                source.name(), source.type(), source.size(), source.sha256(), bytes);
    }

    @Test void existingFilesGetStableHashesAndNewCopyPreservesBytesAndOrigin() throws Exception {
        var source = fixture(); var target = fixture(); byte[] bytes = "文件内容\u0000binary".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var original = upload(source, "中文.txt", bytes);
        assertEquals(original, snapshot(source).items().getFirst());
        var receipt = sync.apply(target.actor(), target.project(), request(target, original, bytes));
        var copied = snapshot(target).items().getFirst();
        assertNotEquals(original.id(), copied.id()); assertEquals(original.key(), copied.key());
        assertEquals(original.sha256(), copied.sha256()); assertEquals(original.name(), copied.name());
        assertEquals(receipt.fileId(), copied.id());
        try (var input = files.download(target.actor(), target.project(), copied.id())) { assertArrayEquals(bytes, input.readAllBytes()); }
        assertEquals(1, snapshot(source).items().size());
    }

    @Test void receiptReplayDoesNotDuplicateAfterLaterUploadAndPayloadReuseRejects() {
        var source = fixture(); var target = fixture(); byte[] bytes = "one".getBytes();
        var original = upload(source, "source.txt", bytes); var request = request(target, original, bytes);
        var receipt = sync.apply(target.actor(), target.project(), request);
        upload(target, "later.txt", "later".getBytes());
        assertEquals(receipt, sync.apply(target.actor(), target.project(), request));
        assertEquals(2, snapshot(target).items().size());
        assertThrows(ResponseStatusException.class, () -> sync.apply(target.actor(), target.project(),
                new Request(request.requestKey(), request.expectedUserId(), request.expectedRevision(), request.sourceKey(),
                        "changed.txt", request.type(), request.size(), request.sha256(), request.content())));
    }

    @Test void staleRevisionInvalidDigestAndExistingOriginRejectWithoutExtraFiles() {
        var source = fixture(); var target = fixture(); byte[] bytes = "payload".getBytes();
        var original = upload(source, "source.txt", bytes); var first = request(target, original, bytes);
        upload(target, "other.txt", "other".getBytes());
        assertThrows(ResponseStatusException.class, () -> sync.apply(target.actor(), target.project(), first));
        var fresh = request(target, original, bytes);
        assertThrows(ResponseStatusException.class, () -> sync.apply(target.actor(), target.project(),
                new Request(fresh.requestKey(), fresh.expectedUserId(), fresh.expectedRevision(), fresh.sourceKey(),
                        fresh.name(), fresh.type(), fresh.size(), fresh.sha256(), "tampered".getBytes())));
        sync.apply(target.actor(), target.project(), fresh);
        assertThrows(ResponseStatusException.class, () -> sync.apply(target.actor(), target.project(), request(target, original, bytes)));
        assertEquals(2, snapshot(target).items().size());
    }

    @Test void deletingImportedFileNeverDeletesSourceOrRecreatesItOnSnapshot() {
        var source = fixture(); var target = fixture(); byte[] bytes = "delete".getBytes();
        var original = upload(source, "source.txt", bytes);
        var receipt = sync.apply(target.actor(), target.project(), request(target, original, bytes));
        files.delete(target.actor(), target.project(), receipt.fileId());
        assertTrue(snapshot(target).items().isEmpty());
        assertEquals(original.id(), snapshot(source).items().getFirst().id());
    }

    @Test void embeddedReferencesRequireCanonicalExistingFilesInTheTargetProject() {
        var owner = fixture(); var other = fixture();
        var row = upload(owner, "reference.txt", "content".getBytes());
        String route = "/api/v1/projects/" + owner.project() + "/files/" + row.id() + "/download";
        fileReferences.validate(java.util.Map.of("description", "[附件](" + route + ")"), owner.project());
        assertThrows(ResponseStatusException.class, () -> fileReferences.validate(route, other.project()));
        for (String unsafe : java.util.List.of("https://external.test" + route,
                "https://external.test/?next=" + route,
                route.replace("/api/v1/", "%2Fapi%2Fv1%2F"),
                "/api/v1/projects/" + other.project() + "/files/" + row.id() + "/download",
                "/api/v1/projects/" + owner.project() + "/files/" + "Z".repeat(26) + "/download",
                "testhub-file:" + row.key())) {
            assertThrows(ResponseStatusException.class, () -> fileReferences.validate(unsafe, owner.project()), unsafe);
        }
        files.delete(owner.actor(), owner.project(), row.id());
        assertThrows(ResponseStatusException.class, () -> fileReferences.validate(route, owner.project()));
    }

    @Test void actorProjectAccessCsrfAndBodyLimit() throws Exception {
        var source = fixture(); var other = fixture(); byte[] bytes = "secure".getBytes();
        var original = upload(source, "source.txt", bytes); var request = request(other, original, bytes);
        String url = "/api/v1/projects/" + other.project() + "/files/sync";
        mvc.perform(get(url).param("expectedUserId", source.actor().userId()).with(user(other.actor()))).andExpect(status().isConflict());
        mvc.perform(get(url).param("expectedUserId", other.actor().userId()).with(user(source.actor()))).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user(other.actor())).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request))).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user(other.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request))).andExpect(status().isOk());
        assertEquals(413, assertThrows(ResponseStatusException.class, () -> sync.decode(new byte[ProjectFileSyncService.MAX_BODY + 1])).getStatusCode().value());
    }
}
