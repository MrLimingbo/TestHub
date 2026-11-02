package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.apidebug.ApiDebugService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class P1HistoryPermissionFlowTests extends P1FlowTestSupport {
    @Autowired ApiDebugService debug;
    String path(String project, String tail) { return "/api/v1/projects/" + project + "/api-debug/" + tail; }
    String token(PlatformUserPrincipal actor, String project, String... scopes) {
        return access.createToken(actor, project, unique(), List.of(scopes), 1).token();
    }
    void request(String token, String path, int expected) throws Exception {
        mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().is(expected));
    }
    @Test @DisplayName("[P1-D11-01][POS] explicit read scope reads descriptors while decrypted history stays protected")
    void readScope() throws Exception {
        var a = actor(); String p = project(a), t = token(a, p, "project:read");
        request(t, path(p, "grpc/descriptors"), 200); request(t, path(p, "history"), 403);
    }
    @Test @DisplayName("[P1-D11-02][POS] write scope reads the authorized replay without implying read scope")
    void writeScope() throws Exception {
        var a = actor(); String p = project(a), marker = unique();
        debug.saveDraft(a, p, new ApiDebugService.DebugRequest("GET", "https://8.8.8.8/qa", List.of(new ApiDebugService.HeaderInput("X-QA", marker)), "", 1000, null, null, null, "QA"));
        var result = mvc.perform(get(path(p, "history")).header("Authorization", "Bearer " + token(a, p, "project:write")))
                .andExpect(status().isOk()).andReturn();
        assertTrue(result.getResponse().getContentAsString().contains(marker));
    }
    @Test @DisplayName("[P1-D11-03][POS] combined read and write explicitly grants both existing capabilities")
    void bothScopes() throws Exception {
        var a = actor(); String p = project(a), t = token(a, p, "project:read", "project:write");
        request(t, path(p, "history"), 200); request(t, path(p, "grpc/descriptors"), 200);
    }
    @Test @DisplayName("[P1-D11-04][NEG] write scope and run-execute do not silently acquire unrelated read permissions")
    void scopesDoNotImplyOthers() throws Exception {
        var a = actor(); String p = project(a);
        request(token(a, p, "project:write"), path(p, "grpc/descriptors"), 403);
        String run = token(a, p, "run:execute"); request(run, path(p, "history"), 403); request(run, path(p, "grpc/descriptors"), 403);
    }
    @Test @DisplayName("[P1-D11-05][NEG] read scope is denied whether replay data exists or not")
    void historyShapeDoesNotChangePermission() throws Exception {
        var a = actor(); String p = project(a), t = token(a, p, "project:read"), marker = unique();
        request(t, path(p, "history"), 403);
        debug.saveDraft(a, p, new ApiDebugService.DebugRequest("GET", "https://8.8.8.8/qa", List.of(), marker, 1000, null, null, null, "QA"));
        var result = mvc.perform(get(path(p, "history")).header("Authorization", "Bearer " + t)).andExpect(status().isForbidden()).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains(marker));
    }
    @Test @DisplayName("[P1-D11-06][NEG] cross-project and revoked tokens cannot read plaintext replay")
    void tokenBoundary() throws Exception {
        var a = actor(); var b = actor(); String p = project(a);
        var token = access.createToken(a, p, unique(), List.of("project:read", "project:write"), 1);
        request(token.token(), path(project(b), "history"), 403);
        access.revokeToken(a, p, token.id()); request(token.token(), path(p, "history"), 401);
        request("invalid-" + unique(), path(p, "history"), 401);
    }
    @Test @DisplayName("[P1-D11-07][POS] existing browser writer roles retain history access without adding a new summary API")
    void browserRoleMatrix() throws Exception {
        var owner = actor(); String p = project(owner), org = access.context(owner).organizations().getFirst().id();
        for (String role : List.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER")) {
            var member = actor();
            memberships.insertMembership(com.github.f4b6a3.ulid.UlidCreator.getUlid().toString(), member.userId(), org,
                    role.equals("SYSTEM_ADMIN") || role.equals("ORG_ADMIN") ? null : p, role);
            mvc.perform(get(path(p, "history")).with(user(member)))
                    .andExpect(status().isOk());
        }
    }
    @Test @DisplayName("[P1-D11-08][NEG] current project membership is still required after a write-scoped PAT was issued")
    void roleRevocation() throws Exception {
        var a = actor(); String p = project(a), t = token(a, p, "project:write");
        jdbc.sql("DELETE FROM membership WHERE user_id=:u").param("u", a.userId()).update();
        request(t, path(p, "history"), 403);
    }
}
