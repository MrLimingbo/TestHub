package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.testhub.platform.shared.runtime.RunnerRegistry;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ApiRunnerTrustFlowTests {
    private static final String VALID_RUNNER = "api-runner-signed-test";
    private static final String TAMPERED_RUNNER = "api-runner-tampered-test";
    private static final String MEMBERS = "testhub:runner:api:members:v1";
    private static final String HEARTBEAT_PREFIX = "testhub:runner:api:heartbeat:v1:";
    private static final String HEARTBEAT_KEY = "testhub-runner-contract-test-signing-key-2026-000000000000000000000000";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RunnerRegistry registry;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("testhub.runner.heartbeat-key", () -> HEARTBEAT_KEY);
    }

    @AfterEach
    void clean() {
        registry.delete(HEARTBEAT_PREFIX + VALID_RUNNER);
        registry.delete(HEARTBEAT_PREFIX + TAMPERED_RUNNER);
        registry.remove(MEMBERS, VALID_RUNNER, TAMPERED_RUNNER);
    }

    @Test
    void marksOnlyHmacVerifiedRunnerHeartbeatsAsTrusted() throws Exception {
        writeHeartbeat(VALID_RUNNER, false);
        writeHeartbeat(TAMPERED_RUNNER, true);
        LoginSession administrator = login();
        String projectId = projectId(administrator);

        MvcResult response = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/runner-status", projectId)
                        .cookie(administrator.sessionCookie())).andExpect(status().isOk()).andReturn();
        JsonNode statuses = json(response).path("data");
        JsonNode valid = find(statuses, VALID_RUNNER);
        JsonNode tampered = find(statuses, TAMPERED_RUNNER);
        org.junit.jupiter.api.Assertions.assertTrue(valid.path("trusted").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals("VERIFIED", valid.path("trustStatus").asText());
        org.junit.jupiter.api.Assertions.assertFalse(tampered.path("trusted").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals("UNVERIFIED", tampered.path("trustStatus").asText());
    }

    private void writeHeartbeat(String runnerId, boolean tamper) throws Exception {
        Map<String, Object> heartbeat = new LinkedHashMap<>();
        heartbeat.put("runnerId", runnerId);
        heartbeat.put("runnerType", "api");
        heartbeat.put("protocolVersion", "1");
        heartbeat.put("runnerVersion", "0.1.0-test");
        heartbeat.put("executionEnabled", false);
        heartbeat.put("startedAt", "2026-07-14T08:00:00Z");
        heartbeat.put("heartbeatAt", "2026-07-14T08:00:10Z");
        heartbeat.put("trustVersion", "1");
        String signature = sign(heartbeat);
        heartbeat.put("signature", tamper ? (signature.charAt(0) == '0' ? "1" : "0") + signature.substring(1) : signature);
        registry.publish(MEMBERS, HEARTBEAT_PREFIX + runnerId, runnerId, objectMapper.writeValueAsString(heartbeat), Duration.ofSeconds(60));
    }

    private String sign(Map<String, Object> heartbeat) throws Exception {
        String payload = String.join("\n", String.valueOf(heartbeat.get("runnerId")), String.valueOf(heartbeat.get("runnerType")),
                String.valueOf(heartbeat.get("protocolVersion")), String.valueOf(heartbeat.get("runnerVersion")),
                String.valueOf(heartbeat.get("executionEnabled")), String.valueOf(heartbeat.get("startedAt")),
                String.valueOf(heartbeat.get("heartbeatAt")));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HEARTBEAT_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
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

    private JsonNode find(JsonNode values, String runnerId) {
        return java.util.stream.StreamSupport.stream(values.spliterator(), false)
                .filter(value -> runnerId.equals(value.path("runnerId").asText())).findFirst().orElseThrow();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record LoginSession(Cookie sessionCookie) {
    }
}
