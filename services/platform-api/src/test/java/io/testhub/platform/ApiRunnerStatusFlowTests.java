package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import io.testhub.platform.shared.runtime.RunnerRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ApiRunnerStatusFlowTests {
    private static final String RUNNER_ID = "api-runner-contract-test";
    private static final String MEMBERS = "testhub:runner:api:members:v1";
    private static final String HEARTBEAT = "testhub:runner:api:heartbeat:v1:" + RUNNER_ID;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RunnerRegistry registry;

    @AfterEach
    void clean() {
        registry.delete(HEARTBEAT);
        registry.remove(MEMBERS, RUNNER_ID);
    }

    @Test
    void exposesOnlyValidOnlineApiRunnerHeartbeatsToAuthorizedProjectMembers() throws Exception {
        registry.publish(MEMBERS, HEARTBEAT, RUNNER_ID, objectMapper.writeValueAsString(Map.of(
                "runnerId", RUNNER_ID, "runnerType", "api", "protocolVersion", "1", "runnerVersion", "0.1.0-test",
                "executionEnabled", false, "startedAt", "2026-07-14T08:00:00Z", "heartbeatAt", "2026-07-14T08:00:10Z"
        )), Duration.ofSeconds(60));
        LoginSession administrator = login();
        String projectId = projectId(administrator);

        MvcResult result = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/runner-status", projectId)
                        .cookie(administrator.sessionCookie())).andExpect(status().isOk()).andReturn();
        JsonNode statuses = json(result).path("data");
        JsonNode runner = java.util.stream.StreamSupport.stream(statuses.spliterator(), false)
                .filter(node -> RUNNER_ID.equals(node.path("runnerId").asText())).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("api", runner.path("runnerType").asText());
        org.junit.jupiter.api.Assertions.assertFalse(runner.path("executionEnabled").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals("0.1.0-test", runner.path("runnerVersion").asText());
    }

    private LoginSession login() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode data = json(csrf).path("data");
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN")).header(data.path("headerName").asText(), data.path("token").asText())
                        .content("{\"email\":\"admin@testhub.local\",\"password\":\"TestHub2026!\"}"))
                .andExpect(status().isOk()).andReturn();
        return new LoginSession(login.getResponse().getCookie("TESTHUB_SESSION"));
    }

    private String projectId(LoginSession session) throws Exception {
        MvcResult context = mvc.perform(get("/api/v1/access/context").cookie(session.sessionCookie())).andExpect(status().isOk()).andReturn();
        String organizationId = json(context).at("/data/organizations/0/id").asText();
        return json(context).at("/data/projects/" + organizationId + "/0/id").asText();
    }

    private JsonNode json(MvcResult result) throws Exception { return objectMapper.readTree(result.getResponse().getContentAsString()); }
    private record LoginSession(Cookie sessionCookie) { }
}
