package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import io.testhub.platform.account.PlatformUserPrincipal;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

class P1SessionRulesFlowTests extends P1FlowTestSupport {
    @Autowired SessionRepository<?> sessions;
    record Target(PlatformUserPrincipal actor, String project, String password) { }
    Target target() {
        String name = unique(), pass = password();
        var actor = new PlatformUserPrincipal(registration.register(name, name + "@example.test", "会话QA", pass));
        return new Target(actor, project(actor), pass);
    }
    Cookie browser(PlatformUserPrincipal actor) { return browser(sessions, actor); }
    <S extends Session> Cookie browser(SessionRepository<S> repository, PlatformUserPrincipal actor) {
        var session = repository.createSession();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(actor, null, actor.getAuthorities());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(authentication));
        repository.save(session);
        return new Cookie("TESTHUB_SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }
    void enabled(PlatformUserPrincipal admin, Target target, boolean enabled) {
        var a = users.findManagedById(target.actor().userId()).orElseThrow();
        accounts.update(admin, a.id(), a.account(), a.displayName(), a.email(), a.phone(), enabled);
    }
    void me(Cookie cookie, int expected) throws Exception { mvc.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().is(expected)); }
    @Test @DisplayName("[P1-D05-01][POS] normal sessions and profile-only updates remain valid")
    void normal() throws Exception {
        var admin = admin(); var target = target(); var a = target.actor().account(); var one = browser(target.actor()); var two = browser(target.actor());
        accounts.update(admin, a.id(), a.account(), "新姓名", a.email(), null, true);
        me(one, 200); me(two, 200);
    }
    @Test @DisplayName("[P1-D05-02][POS] a new password can log in with a stale cookie after reset")
    void freshLogin() throws Exception {
        var admin = admin(); var target = target(); var old = browser(target.actor()); String next = password();
        accounts.updatePassword(admin, target.actor().userId(), next);
        mvc.perform(post("/api/v1/auth/login").cookie(old).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("account", target.actor().account().account(), "password", next))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("account", target.actor().account().account(), "password", target.password()))))
                .andExpect(status().isUnauthorized());
    }
    @Test @DisplayName("[P1-D05-03][POS] resetting another account does not revoke the administrator session")
    void otherAccount() throws Exception {
        var admin = admin(); var target = target(); var adminCookie = browser(admin);
        accounts.updatePassword(admin, target.actor().userId(), password()); me(adminCookie, 200);
    }
    @Test @DisplayName("[P1-D05-04][POS] P0 preserves a PAT after password reset")
    void patSurvivesPasswordReset() throws Exception {
        var admin = admin(); var target = target();
        var token = access.createToken(target.actor(), target.project(), unique(), List.of("project:read"), 1);
        accounts.updatePassword(admin, target.actor().userId(), password());
        mvc.perform(get("/api/v1/projects/" + target.project()).header("Authorization", "Bearer " + token.token())).andExpect(status().isOk());
    }
    @Test @DisplayName("[P1-D05-05][NEG] disabling an account rejects both previously saved browser sessions")
    void disable() throws Exception {
        var admin = admin(); var target = target(); var one = browser(target.actor()); var two = browser(target.actor());
        enabled(admin, target, false); me(one, 401); me(two, 401);
    }
    @Test @DisplayName("[P1-D05-06][NEG] soft deletion rejects both previously saved browser sessions")
    void deleteAccount() throws Exception {
        var admin = admin(); var target = target(); var one = browser(target.actor()); var two = browser(target.actor());
        accounts.delete(admin, target.actor().userId()); me(one, 401); me(two, 401);
    }
    @Test @DisplayName("[P1-D05-07][NEG] re-enabling before the next request cannot resurrect an old cookie")
    void noResurrection() throws Exception {
        var admin = admin(); var target = target(); var old = browser(target.actor());
        enabled(admin, target, false); enabled(admin, target, true); me(old, 401);
        me(browser(new PlatformUserPrincipal(users.findById(target.actor().userId()).orElseThrow())), 200);
    }
    @Test @DisplayName("[P1-D05-08][NEG] reset invalidates protected reads and fresh-CSRF writes")
    void resetRejectsReadAndWrite() throws Exception {
        var admin = admin(); var target = target(); var one = browser(target.actor()); var two = browser(target.actor());
        accounts.updatePassword(admin, target.actor().userId(), password()); me(one, 401);
        mvc.perform(get("/api/v1/projects/" + target.project() + "/tasks").cookie(two)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/projects/" + target.project() + "/tasks").cookie(two).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskType\":\"OTHER\",\"name\":\"must not save\"}")).andExpect(status().isUnauthorized());
    }
    @Test @DisplayName("[P1-D05-09][NEG] administrator self reset logs out the current session after a successful response")
    void selfReset() throws Exception {
        var admin = admin(); var cookie = browser(admin);
        mvc.perform(put("/api/v1/accounts/" + admin.userId() + "/password").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("password", password())))).andExpect(status().isNoContent());
        me(cookie, 401);
    }
    @Test @DisplayName("[P1-D05-10][NEG] disabled or deleted accounts cannot authenticate with PATs")
    void patDisabledOrDeleted() throws Exception {
        var admin = admin();
        for (boolean deletion : List.of(false, true)) {
            var target = target(); var token = access.createToken(target.actor(), target.project(), unique(), List.of("project:read"), 1);
            if (deletion) accounts.delete(admin, target.actor().userId()); else enabled(admin, target, false);
            mvc.perform(get("/api/v1/projects/" + target.project()).header("Authorization", "Bearer " + token.token())).andExpect(status().isUnauthorized());
        }
    }
}
