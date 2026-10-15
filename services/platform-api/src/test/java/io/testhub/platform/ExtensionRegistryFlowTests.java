package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ExtensionRegistryFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcClient jdbc;
    private String signingKeyId;
    private String pluginId;
    private String adapterId;

    @AfterEach
    void clean() {
        if (adapterId != null) jdbc.sql("DELETE FROM enterprise_adapter_configuration WHERE id=:id").param("id", adapterId).update();
        if (pluginId != null) {
            jdbc.sql("DELETE FROM platform_plugin_release WHERE plugin_id=:id").param("id", pluginId).update();
            jdbc.sql("DELETE FROM platform_plugin WHERE id=:id").param("id", pluginId).update();
        }
        if (signingKeyId != null) jdbc.sql("DELETE FROM plugin_signing_key WHERE id=:id").param("id", signingKeyId).update();
        jdbc.sql("DELETE FROM audit_log WHERE action LIKE 'PLUGIN_%' OR action LIKE 'ENTERPRISE_ADAPTER_%'").update();
    }

    @Test
    void verifiesSignedPluginPackagesAndKeepsCredentialsEncrypted() throws Exception {
        Login session = login();
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Csrf csrf = csrf(session);
        MvcResult registered = mvc.perform(post("/api/v1/extensions/signing-keys").cookie(session.cookie(), csrf.cookie())
                        .header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "keyId", "flow.release.2026", "displayName", "流程测试发布密钥",
                                "publicKeyBase64", Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true)).andReturn();
        signingKeyId = body(registered).at("/data/id").asText();

        String firstManifest = manifest("1.0.0");
        csrf = csrf(session);
        MvcResult installed = mvc.perform(post("/api/v1/extensions/plugins/packages").cookie(session.cookie(), csrf.cookie())
                        .header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "signerKeyId", "flow.release.2026", "manifestJson", firstManifest,
                                "signatureBase64", signature(keyPair, firstManifest)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISABLED"))
                .andExpect(jsonPath("$.data.permissions[0]").value("integration:outbound")).andReturn();
        pluginId = body(installed).at("/data/id").asText();

        csrf = csrf(session);
        mvc.perform(post("/api/v1/extensions/plugins/{pluginId}/enable", pluginId).cookie(session.cookie(), csrf.cookie())
                        .header(csrf.header(), csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ENABLED"));
        String upgradedManifest = manifest("1.1.0");
        csrf = csrf(session);
        mvc.perform(post("/api/v1/extensions/plugins/packages").cookie(session.cookie(), csrf.cookie()).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("signerKeyId", "flow.release.2026",
                                "manifestJson", upgradedManifest, "signatureBase64", signature(keyPair, upgradedManifest)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeVersion").value("1.1.0"))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
        assertTrue(jdbc.sql("SELECT COUNT(*) FROM platform_plugin_release WHERE plugin_id=:id").param("id", pluginId).query(Integer.class).single() == 2);

        csrf = csrf(session);
        MvcResult adapter = mvc.perform(post("/api/v1/extensions/adapters").cookie(session.cookie(), csrf.cookie())
                        .header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON).content("""
                                {"adapterType":"OIDC","displayName":"企业 SSO","endpoint":"https://login.example.com",
                                 "configuration":{"clientId":"testhub","clientSecret":"top-secret-value","redirectUri":"https://testhub.example.com/login/oauth2/code/oidc"},"enabled":true}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.configuredFields").isArray())
                .andExpect(jsonPath("$.data.configuredFields[?(@ == 'clientSecret')]").exists()).andReturn();
        adapterId = body(adapter).at("/data/id").asText();
        String encrypted = jdbc.sql("SELECT configuration_ciphertext FROM enterprise_adapter_configuration WHERE id=:id")
                .param("id", adapterId).query(String.class).single();
        assertFalse(encrypted.contains("top-secret-value"));
        mvc.perform(get("/api/v1/extensions/adapters").cookie(session.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].configuredFields[?(@ == 'clientSecret')]").exists())
                .andExpect(jsonPath("$.data[0].configuration").doesNotExist());
    }

    private String manifest(String version) throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("pluginKey", "flow.enterprise.adapter");
        value.put("displayName", "流程企业适配器");
        value.put("pluginType", "INTEGRATION");
        value.put("version", version);
        value.put("entrypoint", "adapter/index.js");
        value.put("permissions", List.of("integration:outbound"));
        return json.writeValueAsString(value);
    }

    private String signature(KeyPair keyPair, String payload) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private Login login() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode data = body(csrf).path("data");
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN")).header(data.path("headerName").asText(), data.path("token").asText())
                        .content("{\"email\":\"admin@testhub.local\",\"password\":\"TestHub2026!\"}"))
                .andExpect(status().isOk()).andReturn();
        return new Login(login.getResponse().getCookie("TESTHUB_SESSION"));
    }

    private Csrf csrf(Login session) throws Exception {
        MvcResult response = mvc.perform(get("/api/v1/auth/csrf").cookie(session.cookie())).andExpect(status().isOk()).andReturn();
        JsonNode data = body(response).path("data");
        return new Csrf(response.getResponse().getCookie("XSRF-TOKEN"), data.path("headerName").asText(), data.path("token").asText());
    }

    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private record Login(Cookie cookie) { }
    private record Csrf(Cookie cookie, String header, String token) { }
}
