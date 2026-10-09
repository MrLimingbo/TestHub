package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.apidesign.ApiWorkspacePreferencesService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {"testhub.registration.enabled=true", "testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver = FunctionalDatabaseProfile.class)
@DirtiesContext
class ApiWorkspacePreferencesFlowTests {
    @TempDir static Path directory;

    // Like FunctionalDatabaseProfile, use a fresh file database; never accept a business datasource override.
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) {
            FunctionalDatabaseProfile.configure(registry, directory, "preferences-fixture");
        } else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "preferences-fixture");
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired JdbcClient jdbc;
    @Autowired ApiWorkspacePreferencesService preferences;

    record Fixture(PlatformUserPrincipal actor, String project) { }

    Fixture fixture() {
        String account = "preferences-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test",
                "偏好测试", "Preferences-fixture-2026!"));
        var context = access.context(actor);
        return new Fixture(actor, context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }

    String url(Fixture f) { return "/api/v1/projects/" + f.project() + "/api-design/preferences"; }

    ObjectNode state() {
        ObjectNode state = mapper.createObjectNode();
        state.putArray("folders").addObject().put("id", "folder").putNull("parentId").put("name", "快捷请求");
        state.putArray("hiddenIds").add("historical-request");
        state.putObject("folderIds").put("foreign-or-deleted-request", "folder");
        state.put("displayMode", "URL");
        return state;
    }

    ObjectNode body(long revision, JsonNode state) {
        ObjectNode body = mapper.createObjectNode();
        // Match Jackson's numeric node selection when reading the HTTP JSON response.
        if (revision >= Integer.MIN_VALUE && revision <= Integer.MAX_VALUE) body.put("revision", (int) revision);
        else body.put("revision", revision);
        body.set("state", state);
        return body;
    }

    ResultActions save(Fixture f, ObjectNode body) throws Exception { return saveRaw(f, body.toString()); }

    ResultActions saveRaw(Fixture f, String body) throws Exception {
        return mvc.perform(put(url(f)).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8)));
    }

    JsonNode read(Fixture f) throws Exception {
        return mapper.readTree(mvc.perform(get(url(f)).with(user(f.actor())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).get("data");
    }

    @Test void defaultsRoundTripAndFullReplacementPersistWithScope() throws Exception {
        var f = fixture();
        JsonNode empty = mapper.readTree("{\"revision\":0,\"state\":{\"folders\":[],\"hiddenIds\":[],\"folderIds\":{},\"displayMode\":\"NAME\"}}");
        assertEquals(empty, read(f));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM api_workspace_preferences WHERE project_id = :project")
                .param("project", f.project()).query(Integer.class).single());
        ObjectNode first = state();
        first.withArray("folders").addObject().put("id", "child").put("parentId", "folder").put("name", "子目录");
        save(f, body(0, first)).andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.state").value(mapper.convertValue(first, java.util.Map.class)));
        assertEquals(body(1, first), read(f));
        assertEquals(first, preferences.get(f.actor(), f.project()).state());
        assertEquals(access.project(f.actor(), f.project()).organizationId(), jdbc.sql(
                "SELECT organization_id FROM api_workspace_preferences WHERE project_id = :project AND user_id = :user")
                .param("project", f.project()).param("user", f.actor().userId()).query(String.class).single());
        save(f, body(1, empty.get("state"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2));
        assertEquals(empty.get("state"), read(f).get("state"));
    }

    @Test void isolatesUsersAndProjectsAndAllowsViewerOwnPreferences() throws Exception {
        var owner = fixture();
        var other = fixture();
        save(owner, body(0, state())).andExpect(status().isOk());
        var denied = new Fixture(other.actor(), owner.project());
        mvc.perform(get(url(denied)).with(user(other.actor()))).andExpect(status().isForbidden());
        save(denied, body(0, state())).andExpect(status().isForbidden());
        jdbc.sql("INSERT INTO membership(id,user_id,organization_id,project_id,role_code) VALUES(:id,:user,:org,:project,'VIEWER')")
                .param("id", UlidCreator.getUlid().toString()).param("user", other.actor().userId())
                .param("org", access.project(owner.actor(), owner.project()).organizationId()).param("project", owner.project()).update();
        assertEquals(0, read(denied).get("revision").asLong());
        ObjectNode viewerState = state().put("displayMode", "NAME");
        save(denied, body(0, viewerState)).andExpect(status().isOk());
        assertEquals(state(), read(owner).get("state"));
        assertEquals(viewerState, read(denied).get("state"));
        assertEquals(0, read(other).get("revision").asLong());
        save(other, body(0, state())).andExpect(status().isOk());
        assertEquals(viewerState, read(denied).get("state"));
        jdbc.sql("DELETE FROM membership WHERE user_id=:user AND project_id=:project")
                .param("user", other.actor().userId()).param("project", owner.project()).update();
        save(denied, body(1, viewerState)).andExpect(status().isForbidden());
        mvc.perform(get(url(owner))).andExpect(status().isUnauthorized());
        mvc.perform(put(url(owner)).with(user(owner.actor())).contentType(MediaType.APPLICATION_JSON)
                .content(body(1, state()).toString())).andExpect(status().isForbidden());
    }

    @Test void readOnlyProjectTokensCannotChangePreferences() throws Exception {
        var f = fixture();
        var readToken = new io.testhub.platform.access.ApiTokenPrincipal(f.actor().account(), f.project(), List.of("project:read"));
        mvc.perform(get(url(f)).with(user(readToken))).andExpect(status().isOk());
        mvc.perform(put(url(f)).with(user(readToken)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(0, state()).toString())).andExpect(status().isForbidden());
        assertEquals(0, read(f).get("revision").asLong());
        var writeToken = new io.testhub.platform.access.ApiTokenPrincipal(f.actor().account(), f.project(), List.of("project:read", "project:write"));
        mvc.perform(put(url(f)).with(user(writeToken)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(0, state()).toString())).andExpect(status().isOk());
        assertEquals(state(), read(f).get("state"));
    }

    @Test void rejectsStaleRevisionWithoutChangingState() throws Exception {
        var f = fixture();
        save(f, body(1, state())).andExpect(status().isConflict());
        save(f, body(0, state())).andExpect(status().isOk());
        for (long stale : List.of(0L, 2L)) {
            save(f, body(stale, state().put("displayMode", "NAME"))).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"))
                    .andExpect(jsonPath("$.requestId").isString()).andExpect(jsonPath("$.details").isMap());
        }
        assertEquals(body(1, state()), read(f));
    }

    @Test void concurrentInitialAndExistingWritesHaveExactlyOneWinner() throws Exception {
        var f = fixture();
        for (long revision : List.of(0L, 1L)) {
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var futures = new ArrayList<java.util.concurrent.Future<org.springframework.test.web.servlet.MvcResult>>();
                for (String mode : List.of("NAME", "URL")) {
                    ObjectNode request = body(revision, state().put("displayMode", mode));
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        return save(f, request).andReturn();
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                List<Integer> statuses = new ArrayList<>();
                JsonNode winner = null;
                for (var future : futures) {
                    var response = future.get(15, TimeUnit.SECONDS).getResponse();
                    statuses.add(response.getStatus());
                    if (response.getStatus() == 200) winner = mapper.readTree(response.getContentAsByteArray()).get("data");
                }
                assertEquals(List.of(200, 409), statuses.stream().sorted().toList());
                assertEquals(winner, read(f));
                assertEquals(revision + 1, read(f).get("revision").asLong());
            } finally { start.countDown(); }
        }
    }

    @Test void rejectsInvalidGraphsAtomically() throws Exception {
        var f = fixture();
        save(f, body(0, state())).andExpect(status().isOk());
        List<ObjectNode> invalid = new ArrayList<>();
        ObjectNode duplicate = state();
        duplicate.withArray("folders").add(duplicate.withArray("folders").get(0).deepCopy());
        invalid.add(duplicate);
        for (String parent : List.of("folder", "missing", "")) {
            ObjectNode bad = state(); ((ObjectNode) bad.withArray("folders").get(0)).put("parentId", parent); invalid.add(bad);
        }
        ObjectNode cycle = state();
        ((ObjectNode) cycle.withArray("folders").get(0)).put("parentId", "child");
        cycle.withArray("folders").addObject().put("id", "child").put("parentId", "folder").put("name", "循环");
        invalid.add(cycle);
        ObjectNode dangling = state(); dangling.withObject("folderIds").put("request", "missing"); invalid.add(dangling);
        ObjectNode repeatedHidden = state(); repeatedHidden.withArray("hiddenIds").add("historical-request"); invalid.add(repeatedHidden);
        invalid.add(chain(17));
        for (ObjectNode bad : invalid) {
            save(f, body(1, bad)).andExpect(status().isBadRequest());
            assertEquals(body(1, state()), read(f));
        }
        save(f, body(1, chain(16))).andExpect(status().isOk());
    }

    ObjectNode chain(int depth) {
        ObjectNode state = state(); state.withArray("folders").removeAll(); state.withObject("folderIds").removeAll();
        for (int i = 0; i < depth; i++) {
            var folder = state.withArray("folders").addObject().put("id", "f" + i).put("name", "目录");
            if (i > 0) folder.put("parentId", "f" + (i - 1));
        }
        return state;
    }

    @Test void enforcesStringCollectionAndUtf8BodyBounds() throws Exception {
        var f = fixture();
        ObjectNode max = state();
        ((ObjectNode) max.withArray("folders").get(0)).put("name", "名".repeat(160));
        for (int i = 1; i < 500; i++) max.withArray("folders").addObject().put("id", "f" + i).put("name", "目录");
        max.withArray("hiddenIds").removeAll(); max.withObject("folderIds").removeAll();
        for (int i = 0; i < 2000; i++) {
            max.withArray("hiddenIds").add("h" + i); max.withObject("folderIds").put("r" + i, "folder");
        }
        save(f, body(0, max)).andExpect(status().isOk());
        for (String field : List.of("folders", "hiddenIds", "folderIds")) {
            ObjectNode overflow = max.deepCopy();
            if (field.equals("folders")) overflow.withArray(field).addObject().put("id", "overflow").put("name", "目录");
            else if (field.equals("hiddenIds")) overflow.withArray(field).add("overflow");
            else overflow.withObject(field).put("overflow", "folder");
            save(f, body(1, overflow)).andExpect(status().isBadRequest());
        }
        for (String field : List.of("id", "name", "parentId")) {
            for (String value : List.of(" ", "x".repeat(161), "bad\nvalue")) {
                ObjectNode invalid = state(); ((ObjectNode) invalid.withArray("folders").get(0)).put(field, value);
                save(f, body(1, invalid)).andExpect(status().isBadRequest());
            }
        }
        ObjectNode hidden = state(); hidden.withArray("hiddenIds").add("x".repeat(161));
        save(f, body(1, hidden)).andExpect(status().isBadRequest());
        ObjectNode key = state(); key.withObject("folderIds").put("x".repeat(161), "folder");
        save(f, body(1, key)).andExpect(status().isBadRequest());
        ObjectNode value = state(); value.withObject("folderIds").put("r", "x".repeat(161));
        save(f, body(1, value)).andExpect(status().isBadRequest());
        String request = body(1, state()).toString();
        String boundary = request + " ".repeat(ApiWorkspacePreferencesService.MAX_BODY_BYTES - request.getBytes(StandardCharsets.UTF_8).length);
        saveRaw(f, boundary + " ").andExpect(status().isPayloadTooLarge());
        assertEquals(body(1, max), read(f));
        saveRaw(f, boundary).andExpect(status().isOk());
    }

    @Test void rejectsUnknownFieldsDraftsCopiesUserIdsAndInvalidTypes() throws Exception {
        var f = fixture();
        for (String field : List.of("userId", "organizationId", "projectId", "request", "draft", "copies")) {
            save(f, body(0, state()).put(field, "secret")).andExpect(status().isBadRequest());
            save(f, body(0, state().put(field, "secret"))).andExpect(status().isBadRequest());
        }
        ObjectNode folder = state(); ((ObjectNode) folder.withArray("folders").get(0)).put("requestBody", "secret");
        save(f, body(0, folder)).andExpect(status().isBadRequest());
        for (String raw : List.of("null", "[]", "{}", "{", body(0, state()) + " {}",
                body(0, state()).toString().replace("\"revision\":0", "\"revision\":0,\"revision\":0"))) {
            saveRaw(f, raw).andExpect(status().isBadRequest());
        }
        for (String revision : List.of("null", "-1", "0.5", "\"0\"", "9007199254740991", "9223372036854775808")) {
            saveRaw(f, body(0, state()).toString().replace("\"revision\":0", "\"revision\":" + revision)).andExpect(status().isBadRequest());
        }
        for (String field : List.of("folders", "hiddenIds", "folderIds", "displayMode")) {
            ObjectNode absent = state(); absent.remove(field); save(f, body(0, absent)).andExpect(status().isBadRequest());
            save(f, body(0, state().putNull(field))).andExpect(status().isBadRequest());
            save(f, body(0, state().put(field, 12))).andExpect(status().isBadRequest());
        }
        save(f, body(0, state().put("displayMode", "name"))).andExpect(status().isBadRequest());
        assertEquals(0, read(f).get("revision").asLong());
    }
}
