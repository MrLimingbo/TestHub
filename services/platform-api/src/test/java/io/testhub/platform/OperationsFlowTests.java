package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class OperationsFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private org.springframework.core.env.Environment environment;

    @Test
    void exposesDetailFreeProbesMetricsAndAdministratorOperationsOverview() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/plain")));

        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy", org.hamcrest.Matchers.containsString("camera=()")))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'self'")))
                .andReturn();
        JsonNode csrfData = body(csrf).path("data");
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN")).header(csrfData.path("headerName").asText(), csrfData.path("token").asText())
                        .content("{\"email\":\"admin@testhub.local\",\"password\":\"TestHub2026!\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie session = login.getResponse().getCookie("TESTHUB_SESSION");
        mvc.perform(get("/api/v1/operations/overview").cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.components.length()").value(3))
                .andExpect(jsonPath("$.data.components[?(@.key == 'database' && @.status == 'UP')]").exists())
                .andExpect(jsonPath("$.data.components[?(@.key == 'cache' && @.status == 'UP')]").exists())
                .andExpect(jsonPath("$.data.components[?(@.key == 'storage' && @.status == 'UP')]").exists())
                .andExpect(jsonPath("$.data.controls[?(@.key == 'metrics' && @.status == 'ENABLED')]").exists())
                .andExpect(jsonPath("$.data.dataProtection.migrationStatus").value("UP"))
                .andExpect(jsonPath("$.data.dataProtection.migrationVersion").value(org.hamcrest.Matchers.startsWith("V")))
                .andExpect(jsonPath("$.data.dataProtection.controls[?(@.key == 'restore-guard' && @.status == 'ENABLED')]").exists())
                .andExpect(jsonPath("$.data.capacity.status").value("ATTENTION"))
                .andExpect(jsonPath("$.data.capacity.controls[?(@.key == 'capacity-baseline' && @.status == 'ENABLED')]").exists())
                .andExpect(jsonPath("$.data.capacity.controls[?(@.key == 'resource-alerts')]").exists())
                .andExpect(jsonPath("$.data.capacity.controls[?(@.key == 'recovery-drill' && @.status == 'ATTENTION')]").exists())
                .andExpect(jsonPath("$.data.controls[?(@.key == 'secret-rekey-window' && @.status == 'ENABLED')]").exists())
                .andExpect(jsonPath("$.data.components[0].detail").value("连接与基础读写检查正常。"));

        boolean desktop = environment.acceptsProfiles(org.springframework.core.env.Profiles.of("desktop"));
        mvc.perform(get("/api/v1/operations/overview").cookie(session))
                .andExpect(jsonPath("$.data.runtimeMode").value(desktop ? "DESKTOP" : "SERVER"))
                .andExpect(jsonPath("$.data.capacity.controls[?(@.key == 'resource-alerts' && @.status == '" + (desktop ? "ATTENTION" : "ENABLED") + "')]").exists());
        if (desktop) {
            mvc.perform(get("/api/v1/operations/overview").cookie(session))
                    .andExpect(jsonPath("$.data.controls[?(@.key == 'helm-chart')]").isEmpty())
                    .andExpect(jsonPath("$.data.controls[?(@.key == 'desktop-distribution' && @.status == 'ATTENTION')]").exists());
        } else {
            mvc.perform(get("/api/v1/operations/overview").cookie(session))
                    .andExpect(jsonPath("$.data.controls[?(@.key == 'helm-chart' && @.status == 'ENABLED')]").exists())
                    .andExpect(jsonPath("$.data.controls[?(@.key == 'helm-rollback' && @.status == 'ENABLED')]").exists())
                    .andExpect(jsonPath("$.data.controls[?(@.key == 'release-acceptance' && @.status == 'ENABLED')]").exists())
                    .andExpect(jsonPath("$.data.controls[?(@.key == 'production-drill' && @.status == 'ATTENTION')]").exists());
        }

        MvcResult rekeyCsrf = mvc.perform(get("/api/v1/auth/csrf").cookie(session)).andExpect(status().isOk()).andReturn();
        JsonNode rekeyCsrfData = body(rekeyCsrf).path("data");
        mvc.perform(post("/api/v1/operations/secret-rekey").contentType(MediaType.APPLICATION_JSON)
                        .cookie(session, rekeyCsrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(rekeyCsrfData.path("headerName").asText(), rekeyCsrfData.path("token").asText())
                        .content("{\"confirmation\":\"REKEY_PERSISTED_SECRETS\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
    }

    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
}
