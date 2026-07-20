package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ApiRunnerInternalAuthenticationFlowTests {
    private static final String RUNNER_ID = "api-runner-internal-auth-test";
    private static final String MEMBERS = "testhub:runner:api:members:v1";
    private static final String HEARTBEAT = "testhub:runner:api:heartbeat:v1:" + RUNNER_ID;
    private static final String NONCE_PREFIX = "testhub:runner:api:internal-nonce:v1:" + RUNNER_ID + ":";
    private static final String PATH = "/internal/api-runner/v1/capabilities";
    private static final String HEARTBEAT_KEY = "testhub-runner-contract-test-signing-key-2026-000000000000000000000000";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StringRedisTemplate redis;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("testhub.runner.heartbeat-key", () -> HEARTBEAT_KEY);
    }

    @AfterEach
    void clean() {
        redis.delete(HEARTBEAT);
        redis.opsForSet().remove(MEMBERS, RUNNER_ID);
        redis.delete(NONCE_PREFIX + "nonce_0123456789abcdef");
        redis.delete(NONCE_PREFIX + "nonce_abcdef0123456789");
    }

    @Test
    void acceptsOneRecentSignedRequestFromAHeartbeatVerifiedRunnerAndRejectsReplayOrTampering() throws Exception {
        writeVerifiedHeartbeat();
        MvcResult accepted = mvc.perform(get(PATH).headers(headers("nonce_0123456789abcdef", false)))
                .andExpect(status().isOk()).andReturn();
        JsonNode capability = json(accepted).path("data");
        org.junit.jupiter.api.Assertions.assertEquals(RUNNER_ID, capability.path("runnerId").asText());
        org.junit.jupiter.api.Assertions.assertTrue(capability.path("trusted").asBoolean());
        org.junit.jupiter.api.Assertions.assertFalse(capability.path("executionEnabled").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals("DISABLED", capability.path("runtimeConfiguration").asText());
        org.junit.jupiter.api.Assertions.assertEquals("RUNTIME_CONFIGURATION", capability.path("withheldCapabilities").get(0).asText());

        mvc.perform(get(PATH).headers(headers("nonce_0123456789abcdef", false))).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).headers(headers("nonce_abcdef0123456789", true))).andExpect(status().isUnauthorized());
    }

    private void writeVerifiedHeartbeat() throws Exception {
        Map<String, Object> heartbeat = new LinkedHashMap<>();
        heartbeat.put("runnerId", RUNNER_ID);
        heartbeat.put("runnerType", "api");
        heartbeat.put("protocolVersion", "1");
        heartbeat.put("runnerVersion", "0.1.0-test");
        heartbeat.put("executionEnabled", false);
        heartbeat.put("startedAt", Instant.now().minusSeconds(10).toString());
        heartbeat.put("heartbeatAt", Instant.now().toString());
        heartbeat.put("trustVersion", "1");
        heartbeat.put("signature", heartbeatSignature(heartbeat));
        redis.opsForSet().add(MEMBERS, RUNNER_ID);
        redis.opsForValue().set(HEARTBEAT, objectMapper.writeValueAsString(heartbeat), Duration.ofSeconds(60));
    }

    private org.springframework.http.HttpHeaders headers(String nonce, boolean tamper) throws Exception {
        String timestamp = Instant.now().toString();
        String payload = String.join("\n", "testhub.api-runner.internal-auth.v1", "GET", PATH, RUNNER_ID, timestamp, nonce);
        String signature = hmac(payload);
        if (tamper) signature = (signature.charAt(0) == '0' ? "1" : "0") + signature.substring(1);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-TestHub-Runner-Id", RUNNER_ID);
        headers.set("X-TestHub-Runner-Timestamp", timestamp);
        headers.set("X-TestHub-Runner-Nonce", nonce);
        headers.set("X-TestHub-Runner-Signature", signature);
        return headers;
    }

    private String heartbeatSignature(Map<String, Object> heartbeat) throws Exception {
        return hmac(String.join("\n", String.valueOf(heartbeat.get("runnerId")), String.valueOf(heartbeat.get("runnerType")),
                String.valueOf(heartbeat.get("protocolVersion")), String.valueOf(heartbeat.get("runnerVersion")),
                String.valueOf(heartbeat.get("executionEnabled")), String.valueOf(heartbeat.get("startedAt")),
                String.valueOf(heartbeat.get("heartbeatAt"))));
    }

    private String hmac(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HEARTBEAT_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
