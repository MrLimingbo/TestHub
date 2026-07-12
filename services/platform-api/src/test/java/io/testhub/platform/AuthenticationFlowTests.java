package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationFlowTests {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void localAccountCanLoginReadProfileAndLogout() throws Exception {
        MvcResult csrfResult = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andReturn();
        JsonNode csrf = objectMapper.readTree(csrfResult.getResponse().getContentAsString()).path("data");
        String csrfToken = csrf.path("token").asText();
        String csrfHeader = csrf.path("headerName").asText();

        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(csrfHeader, csrfToken)
                        .cookie(csrfResult.getResponse().getCookie("XSRF-TOKEN"))
                        .content("{\"email\":\"admin@testhub.local\",\"password\":\"TestHub2026!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("admin@testhub.local"))
                .andExpect(cookie().exists("TESTHUB_SESSION"))
                .andReturn();

        Cookie sessionCookie = login.getResponse().getCookie("TESTHUB_SESSION");
        mvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("系统管理员"));

        MvcResult refreshCsrf = mvc.perform(get("/api/v1/auth/csrf").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode refreshed = objectMapper.readTree(refreshCsrf.getResponse().getContentAsString()).path("data");
        mvc.perform(post("/api/v1/auth/logout")
                        .cookie(sessionCookie)
                        .header(refreshed.path("headerName").asText(), refreshed.path("token").asText())
                        .cookie(refreshCsrf.getResponse().getCookie("XSRF-TOKEN")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    }

    @Test
    void invalidPasswordIsRejected() throws Exception {
        MvcResult csrfResult = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        JsonNode csrf = objectMapper.readTree(csrfResult.getResponse().getContentAsString()).path("data");
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                        .cookie(csrfResult.getResponse().getCookie("XSRF-TOKEN"))
                        .content("{\"email\":\"admin@testhub.local\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }
}
