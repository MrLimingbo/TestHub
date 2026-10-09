package io.testhub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessRepository;
import io.testhub.platform.account.UserAccountRepository;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "testhub.registration.enabled=true")
@AutoConfigureMockMvc
class RegistrationFlowTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccessRepository access;
    @Autowired UserAccountRepository accounts;
    @Autowired PasswordEncoder passwords;

    @Test
    void registrationCreatesIsolatedWorkspaceAndSecondClientSeesSameData() throws Exception {
        String account = unique();
        MvcResult registered = register(account, account + "@example.test", "Registration2026!", unique())
                .andExpect(status().isCreated()).andReturn();
        String userId = data(registered).path("id").asText();
        assertThat(access.hasRole(userId, "SYSTEM_ADMIN")).isFalse();
        assertThat(passwords.matches("Registration2026!", accounts.findById(userId).orElseThrow().passwordHash())).isTrue();
        Cookie desktopSession = registered.getResponse().getCookie("TESTHUB_SESSION");
        JsonNode context = data(mvc.perform(get("/api/v1/access/context").cookie(desktopSession))
                .andExpect(status().isOk()).andReturn());
        assertThat(context.path("organizations").size()).isEqualTo(1);
        String organizationId = context.path("organizations").get(0).path("id").asText();
        JsonNode project = context.path("projects").path(organizationId).get(0);
        assertThat(project.path("name").asText()).isEqualTo("我的项目");
        String existingOrg = access.findOrganizationByCode("nebula").orElseThrow().id();
        String existingProject = access.findProjectByCode(existingOrg, "trade-v3").orElseThrow().id();
        mvc.perform(get("/api/v1/projects/" + existingProject).cookie(desktopSession)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts").cookie(desktopSession)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts/options").cookie(desktopSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(userId));

        MvcResult secondLogin = postWithCsrf("/api/v1/auth/login", Map.of("account", account, "password", "Registration2026!"), unique())
                .andExpect(status().isOk()).andReturn();
        Cookie webSession = secondLogin.getResponse().getCookie("TESTHUB_SESSION");
        mvc.perform(get("/api/v1/projects/" + project.path("id").asText()).cookie(webSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(project.path("id").asText()));
    }

    @Test
    void duplicateRegistrationCannotCreateAnotherWorkspace() throws Exception {
        String account = unique();
        register(account, account + "@example.test", "Registration2026!", unique()).andExpect(status().isCreated());
        register(account.toUpperCase(), account + "@example.test", "Registration2026!", unique()).andExpect(status().isConflict());
        String id = accounts.findByAccountOrEmail(account).orElseThrow().id();
        assertThat(access.findOrganizationsForUser(id)).hasSize(1);
    }

    @Test
    void validationRejectsInvalidEmailAccountAndBcryptOverflow() throws Exception {
        register(unique(), "not-email", "Registration2026!", unique()).andExpect(status().isBadRequest());
        register("admin@example.test", unique() + "@example.test", "Registration2026!", unique()).andExpect(status().isBadRequest());
        register(unique(), unique() + "@example.test", "短密码", unique()).andExpect(status().isBadRequest());
        register(unique(), unique() + "@example.test", "密".repeat(25), unique()).andExpect(status().isBadRequest());
    }

    @Test
    void csrfIsRequiredForRegistration() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("account", unique(), "email", "guest@example.test", "password", "Registration2026!"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void repeatedAttemptsAreRateLimitedEvenIfClientSpoofsForwardingHeader() throws Exception {
        String remote = unique();
        for (int i = 0; i < 10; i++) {
            register("invalid account", unique() + "@example.test", "Registration2026!", remote).andExpect(status().isBadRequest());
        }
        register(unique(), unique() + "@example.test", "Registration2026!", remote).andExpect(status().isTooManyRequests());
    }

    private ResultActions register(String account, String email, String password, String remote) throws Exception {
        return postWithCsrf("/api/v1/auth/register", Map.of("account", account, "email", email,
                "displayName", "个人测试", "password", password), remote);
    }

    private ResultActions postWithCsrf(String path, Map<String, String> body, String remote) throws Exception {
        MvcResult csrfResult = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode csrf = data(csrfResult);
        return mvc.perform(post(path).with(request -> { request.setRemoteAddr(remote); return request; })
                .header("X-Forwarded-For", unique()).contentType(MediaType.APPLICATION_JSON)
                .cookie(csrfResult.getResponse().getCookie("XSRF-TOKEN"))
                .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .content(json.writeValueAsString(body)));
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private String unique() { return "reg-" + UUID.randomUUID().toString().replace("-", ""); }
}
