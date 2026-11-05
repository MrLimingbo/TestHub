package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.extension.ExtensionRegistryRepository;
import io.testhub.platform.notification.NotificationSyncService;
import io.testhub.platform.notification.NotificationTemplateService;
import io.testhub.platform.notification.NotificationTransferService;
import io.testhub.platform.security.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class P2D23JsonPolicyTests extends P1FlowTestSupport {
    @Autowired ExtensionRegistryRepository registry;
    @Autowired NotificationTemplateService templates;
    @Autowired NotificationTransferService transfer;
    @Autowired NotificationSyncService sync;
    @Autowired SecretCipher cipher;

    static final String MARKER = "D23_UNKNOWN_SENTINEL";
    static final String EXT = "/api/v1/extensions/";
    static final List<String> TABLES = List.of("notification_template", "enterprise_adapter_configuration",
            "plugin_signing_key", "platform_plugin", "platform_plugin_release", "notification_transfer_receipt",
            "notification_sync_receipt", "audit_log", "membership");
    String route(String project) { return "/api/v1/projects/" + project + "/notification-templates"; }
    List<Object> extras() { return List.of(MARKER, Map.of("nested", MARKER), List.of(MARKER)); }
    Map<String, Object> extra(Map<String, Object> body, Object value) {
        var result = new LinkedHashMap<>(body); result.put("d23Unknown", value); return result;
    }
    Map<String, Object> template() {
        return new LinkedHashMap<>(Map.of("name", unique(), "eventCode", "quality.report.ready", "channel", "EMAIL",
                "subjectTemplate", "隔离QA主题", "bodyTemplate", "隔离QA正文", "enabled", false));
    }
    Map<String, String> configuration() {
        return new LinkedHashMap<>(Map.of("clientId", unique(), "clientSecret", UUID.randomUUID().toString(),
                "redirectUri", "https://testhub.qa.invalid/login"));
    }
    Map<String, Object> adapter() {
        return new LinkedHashMap<>(Map.of("adapterType", "OIDC", "displayName", unique(), "endpoint", "https://login.qa.invalid",
                "configuration", configuration(), "enabled", false));
    }
    Map<String, Object> key() throws Exception {
        return new LinkedHashMap<>(Map.of("keyId", "qa." + UUID.randomUUID(), "displayName", "D23隔离临时公钥",
                "publicKeyBase64", Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded())));
    }
    Map<String, Object> unsignedPackage() {
        return new LinkedHashMap<>(Map.of("signerKeyId", "qa.unregistered", "manifestJson", manifest("qa." + UUID.randomUUID(), "1.0.0"),
                "signatureBase64", "untrusted-fixture"));
    }
    String manifest(String plugin, String version) {
        return " \n{\"pluginKey\":\"" + plugin + "\",\"displayName\":\"QA插件\",\"pluginType\":\"INTEGRATION\","
                + "\"version\":\"" + version + "\",\"entrypoint\":\"adapter/index.js\",\"permissions\":[\"integration:outbound\"]}\n\t ";
    }
    record Signer(KeyPair pair, String keyId) { }
    Signer signer(PlatformUserPrincipal actor) throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); String id = "qa." + UUID.randomUUID();
        ok(actor, "POST", EXT + "signing-keys", Map.of("keyId", id, "displayName", "D23隔离签名夹具",
                "publicKeyBase64", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())));
        return new Signer(pair, id);
    }
    Map<String, Object> signed(Signer signer, String raw) throws Exception {
        var signature = Signature.getInstance("Ed25519"); signature.initSign(signer.pair().getPrivate());
        signature.update(raw.getBytes(StandardCharsets.UTF_8));
        return new LinkedHashMap<>(Map.of("signerKeyId", signer.keyId(), "manifestJson", raw,
                "signatureBase64", Base64.getEncoder().encodeToString(signature.sign())));
    }
    MockHttpServletRequestBuilder request(String method, String url, Object body) throws Exception {
        var request = method.equals("PUT") ? put(url) : post(url);
        return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
    }
    MockHttpServletResponse send(PlatformUserPrincipal actor, String method, String url, Object body) throws Exception {
        return mvc.perform(request(method, url, body).with(user(actor)).with(csrf())).andReturn().getResponse();
    }
    JsonNode ok(PlatformUserPrincipal actor, String method, String url, Object body) throws Exception {
        var response = send(actor, method, url, body); assertEquals(200, response.getStatus(), "owned request status");
        assertFalse(response.getContentAsString().contains(MARKER));
        return json.readTree(response.getContentAsByteArray()).path("data");
    }
    String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    Map<String, String> state() throws Exception {
        var result = new LinkedHashMap<String, String>();
        for (String table : TABLES) result.put(table, digest(json.writeValueAsBytes(rows(table))));
        return result;
    }
    List<Map<String, String>> rows(String table) {
        if (!TABLES.contains(table)) throw new IllegalArgumentException("Not an owned fixture table");
        return jdbc.sql("SELECT * FROM " + table + " ORDER BY id").query((rs, row) -> {
            Map<String, String> values = new LinkedHashMap<>(); var metadata = rs.getMetaData();
            for (int column = 1; column <= metadata.getColumnCount(); column++) values.put(metadata.getColumnLabel(column), rs.getString(column));
            return values;
        }).list();
    }
    void noMarkerSaved() throws Exception {
        for (String table : TABLES) assertFalse(json.writeValueAsString(rows(table)).contains(MARKER), "unknown field not stored in " + table);
    }
    void reject(PlatformUserPrincipal actor, String method, String url, Object body, int status) throws Exception {
        var before = state(); var response = send(actor, method, url, body);
        assertEquals(status, response.getStatus(), "rejected request status"); assertFalse(response.getContentAsString().contains(MARKER));
        assertEquals(before, state(), "rejected request leaves business, trust, roles and audit unchanged");
    }
    JsonNode projection(JsonNode input) {
        var value = input.deepCopy(); ((ObjectNode) value).remove(List.of("id", "updatedAt", "createdAt")); return value;
    }

    @Test void B01_templateCreateIgnoresScalarObjectAndArray() throws Exception {
        var actor = actor(); String url = route(project(actor)); var body = template();
        var baseline = projection(ok(actor, "POST", url, body));
        for (var extra : extras()) assertEquals(baseline, projection(ok(actor, "POST", url, extra(body, extra))));
        noMarkerSaved();
    }
    @Test void B02_templateUpdateIgnoresUnknownFields() throws Exception {
        var actor = actor(); String url = route(project(actor)); var body = template();
        String id = ok(actor, "POST", url, body).path("id").asText(); body.put("bodyTemplate", "更新正文");
        var baseline = projection(ok(actor, "PUT", url + "/" + id, body));
        for (var extra : extras()) assertEquals(baseline, projection(ok(actor, "PUT", url + "/" + id, extra(body, extra))));
        noMarkerSaved();
    }
    @Test void B03_adapterCreateIgnoresOuterFields() throws Exception {
        var actor = admin(); var body = adapter(); var created = ok(actor, "POST", EXT + "adapters", body); var baseline = projection(created);
        // Each comparison needs the same valid uniqueness precondition.
        registry.deleteAdapter(created.path("id").asText());
        for (var value : extras()) {
            var row = ok(actor, "POST", EXT + "adapters", extra(body, value)); assertEquals(baseline, projection(row));
            var saved = registry.findAdapter(row.path("id").asText()).orElseThrow();
            assertEquals(json.valueToTree(body.get("configuration")), json.readTree(cipher.decrypt(saved.configurationCiphertext())));
            registry.deleteAdapter(row.path("id").asText());
        }
        noMarkerSaved();
    }
    @Test void B04_adapterUpdatePreservesEncryptedConfiguration() throws Exception {
        var actor = admin(); var body = adapter(); String id = ok(actor, "POST", EXT + "adapters", body).path("id").asText();
        String encrypted = registry.findAdapter(id).orElseThrow().configurationCiphertext(); body.put("configuration", null); body.put("displayName", "D23更新名称");
        var baseline = projection(ok(actor, "PUT", EXT + "adapters/" + id, body));
        for (var value : extras()) {
            assertEquals(baseline, projection(ok(actor, "PUT", EXT + "adapters/" + id, extra(body, value))));
            assertEquals(encrypted, registry.findAdapter(id).orElseThrow().configurationCiphertext());
        }
        noMarkerSaved();
    }
    @Test void B05_signingKeyUnknownFieldsCannotChangeTrustOrCreator() throws Exception {
        var actor = admin();
        for (var value : extras()) {
            var body = key(); var input = extra(body, value); input.put("enabled", false); input.put("createdBy", "forged");
            var row = ok(actor, "POST", EXT + "signing-keys", input); assertTrue(row.path("enabled").asBoolean());
            var saved = jdbc.sql("SELECT created_by,public_key_base64 FROM plugin_signing_key WHERE id=:id").param("id", row.path("id").asText()).query().singleRow();
            assertEquals(actor.userId(), saved.get("created_by")); assertEquals(body.get("publicKeyBase64"), saved.get("public_key_base64"));
        }
        noMarkerSaved();
    }
    @Test void B06_packageInstallAndUpgradeIgnoreOuterFields() throws Exception {
        var actor = admin(); var signer = signer(actor); String plugin = "qa." + UUID.randomUUID();
        String id = ok(actor, "POST", EXT + "plugins/packages", signed(signer, manifest(plugin, "1.0.0"))).path("id").asText();
        int version = 2;
        for (var value : extras()) {
            String release = version++ + ".0.0", raw = manifest(plugin, release); var input = extra(signed(signer, raw), value); input.put("status", "ENABLED");
            var row = ok(actor, "POST", EXT + "plugins/packages", input); assertEquals(id, row.path("id").asText());
            assertEquals(release, row.path("activeVersion").asText()); assertEquals("DISABLED", row.path("status").asText());
            assertEquals(digest(raw.getBytes(StandardCharsets.UTF_8)), row.path("packageChecksum").asText());
        }
        assertEquals(4, jdbc.sql("SELECT COUNT(*) FROM platform_plugin_release WHERE plugin_id=:id").param("id", id).query(Integer.class).single()); noMarkerSaved();
    }
    List<Map.Entry<String, Map<String, Object>>> bodies(PlatformUserPrincipal actor) throws Exception {
        return List.of(Map.entry(route(project(actor)), template()), Map.entry(EXT + "adapters", adapter()),
                Map.entry(EXT + "signing-keys", key()), Map.entry(EXT + "plugins/packages", unsignedPackage()));
    }
    @Test void B07_misspelledMissingNullAndBlankRequiredFieldsStillFail() throws Exception {
        var actor = admin();
        for (var item : bodies(actor)) for (var field : item.getValue().entrySet()) {
            if (!(field.getValue() instanceof String)) continue;
            for (int kind = 0; kind < 3; kind++) {
                var input = extra(item.getValue(), MARKER); input.remove(field.getKey()); input.put(field.getKey() + "Typo", field.getValue());
                if (kind == 1) input.put(field.getKey(), null); if (kind == 2) input.put(field.getKey(), " ");
                reject(actor, "POST", item.getKey(), input, 400);
            }
        }
    }
    @Test void B08_wrongKnownStringTypesStillFail() throws Exception {
        var actor = admin();
        for (var item : bodies(actor)) for (var field : item.getValue().entrySet()) {
            if (!(field.getValue() instanceof String)) continue;
            for (Object bad : List.of(Map.of("invalid", true), List.of("invalid"))) {
                var input = extra(item.getValue(), MARKER); input.put(field.getKey(), bad); reject(actor, "POST", item.getKey(), input, 400);
            }
        }
    }
    @Test void B09_knownBusinessRulesStillFail() throws Exception {
        var actor = admin(); var items = bodies(actor);
        var template = extra(items.get(0).getValue(), MARKER); template.put("channel", "SMS"); reject(actor, "POST", items.get(0).getKey(), template, 400);
        var adapter = extra(items.get(1).getValue(), MARKER); adapter.put("adapterType", "UNKNOWN"); reject(actor, "POST", items.get(1).getKey(), adapter, 400);
        var key = extra(items.get(2).getValue(), MARKER); key.put("publicKeyBase64", "invalid"); reject(actor, "POST", items.get(2).getKey(), key, 400);
        var pkg = extra(items.get(3).getValue(), MARKER); pkg.put("manifestJson", ((String) pkg.get("manifestJson")).replace("integration:outbound", "root:all"));
        pkg.put("signatureValid", true); reject(actor, "POST", items.get(3).getKey(), pkg, 400);
    }
    @Test void B10_dynamicConfigurationKeysAreNotOuterUnknownFields() throws Exception {
        var actor = admin(); var body = adapter(); var config = configuration(); config.put("customOption", "合法自定义值"); body.put("configuration", config);
        var row = ok(actor, "POST", EXT + "adapters", extra(body, MARKER)); String id = row.path("id").asText();
        assertEquals(json.valueToTree(config), json.readTree(cipher.decrypt(registry.findAdapter(id).orElseThrow().configurationCiphertext())));
        assertTrue(row.path("configuredFields").toString().contains("customOption")); assertFalse(row.has("configuration")); noMarkerSaved();
    }
    @Test void B11_dynamicMapValidationIsNotWeakened() throws Exception {
        var actor = admin();
        for (Object value : List.of(Map.of("clientId", Map.of("bad", true)), Map.of("clientId", List.of("bad")), Map.of("clientId", "only-one-key"))) {
            var body = extra(adapter(), MARKER); body.put("configuration", value); reject(actor, "POST", EXT + "adapters", body, 400);
        }
    }
    @Test void B12_forgedScopeAndActorCannotOverrideServerValues() throws Exception {
        var actor = actor(); String project = project(actor); var other = actor(); var body = extra(template(), MARKER);
        body.put("organizationId", access.context(other).organizations().getFirst().id()); body.put("projectId", project(other));
        body.put("userId", other.userId()); body.put("updatedBy", other.userId()); body.put("admin", true); body.put("roles", List.of("SYSTEM_ADMIN"));
        var row = ok(actor, "POST", route(project), body);
        assertEquals(project, row.path("projectId").asText()); assertEquals(access.context(actor).organizations().getFirst().id(), row.path("organizationId").asText());
        assertEquals(actor.userId(), jdbc.sql("SELECT updated_by FROM notification_template WHERE id=:id").param("id", row.path("id").asText()).query(String.class).single());
        assertFalse(memberships.hasRole(actor.userId(), "SYSTEM_ADMIN")); assertTrue(templates.list(other, project(other)).isEmpty()); noMarkerSaved();
    }
    @Test void B13_ordinaryUserCannotCreateTrustAdaptersOrPackages() throws Exception {
        var actor = actor();
        // No administrator or trusted-key fixture is created for this permission test.
        for (var item : bodies(actor).subList(1, 4)) {
            var body = extra(item.getValue(), MARKER); body.put("admin", true); body.put("roles", List.of("SYSTEM_ADMIN"));
            reject(actor, "POST", item.getKey(), body, 403);
            if (item.getKey().endsWith("adapters")) reject(actor, "PUT", EXT + "adapters/unowned", body, 403);
        }
        assertFalse(memberships.hasRole(actor.userId(), "SYSTEM_ADMIN"));
    }
    @Test void B14_viewerCannotForgeTemplateWriterRole() throws Exception {
        var owner = actor(); var viewer = actor(); String project = project(owner); var body = template();
        String id = ok(owner, "POST", route(project), body).path("id").asText();
        access.upsertMember(owner, project, viewer.getUsername(), "VIEWER"); var input = extra(body, MARKER); input.put("roles", List.of("PROJECT_ADMIN"));
        reject(viewer, "POST", route(project), input, 403); reject(viewer, "PUT", route(project) + "/" + id, input, 403);
    }
    @Test void B15_loginAndCsrfChecksRemainRequired() throws Exception {
        var actor = actor();
        for (var item : bodies(actor)) {
            var before = state(); var body = extra(item.getValue(), MARKER);
            assertEquals(401, mvc.perform(request("POST", item.getKey(), body).with(csrf())).andReturn().getResponse().getStatus());
            assertEquals(403, mvc.perform(request("POST", item.getKey(), body).with(user(actor))).andReturn().getResponse().getStatus());
            assertEquals(before, state());
        }
    }
    @Test void B16_transferRejectsUnknownAtEveryPayloadLevel() throws Exception {
        var actor = actor(); String project = project(actor);
        templates.create(actor, project, new NotificationTemplateService.TemplateInput(unique(), "quality.report.ready", "EMAIL", "主题", "正文", null, null, false));
        var input = new NotificationTransferService.ImportRequest(UUID.randomUUID().toString(), actor.userId(), transfer.export(actor, project));
        for (String level : List.of("root", "snapshot", "template")) {
            ObjectNode body = json.valueToTree(input);
            var target = level.equals("root") ? body : level.equals("snapshot") ? (ObjectNode) body.path("snapshot") : (ObjectNode) body.path("snapshot").path("templates").get(0);
            target.put("d23Unknown", MARKER); reject(actor, "POST", route(project) + "/transfer", body, 400);
        }
    }
    @Test void B17_syncRejectsUnknownAtEveryPayloadLevel() throws Exception {
        var actor = actor(); String project = project(actor); var snapshot = sync.snapshot(actor, project, actor.userId());
        var input = new NotificationSyncService.Request(UUID.randomUUID().toString(), actor.userId(), snapshot.revision(),
                List.of(new NotificationSyncService.Operation(UUID.randomUUID().toString(), new NotificationSyncService.Item(unique(), "quality.report.ready", "EMAIL", "主题", "正文"))));
        for (String level : List.of("root", "operation", "item")) {
            ObjectNode body = json.valueToTree(input); ObjectNode operation = (ObjectNode) body.path("operations").get(0);
            var target = level.equals("root") ? body : level.equals("operation") ? operation : (ObjectNode) operation.path("item");
            target.put("d23Unknown", MARKER); reject(actor, "POST", route(project) + "/sync", body, 400);
        }
    }
    @Test void B18_manifestOriginalCharactersAndInnerProjectionArePreserved() throws Exception {
        var actor = admin(); var signer = signer(actor); String raw = manifest("qa." + UUID.randomUUID(), "1.0.0");
        var row = ok(actor, "POST", EXT + "plugins/packages", extra(signed(signer, raw), MARKER));
        assertEquals(digest(raw.getBytes(StandardCharsets.UTF_8)), row.path("packageChecksum").asText());
        String stored = jdbc.sql("SELECT manifest_json FROM platform_plugin WHERE id=:id").param("id", row.path("id").asText()).query(String.class).single();
        assertEquals(json.readTree(raw), json.readTree(stored)); assertEquals("DISABLED", row.path("status").asText()); noMarkerSaved();
    }
    @Test void B19_outerFieldsCannotBypassOriginalSignature() throws Exception {
        var actor = admin(); var signer = signer(actor); String raw = manifest("qa." + UUID.randomUUID(), "1.0.0");
        var body = extra(signed(signer, raw), MARKER); body.put("manifestJson", raw.trim()); body.put("signatureValid", true);
        reject(actor, "POST", EXT + "plugins/packages", body, 400);
    }
    @Test void B20_openApiDescribesTheFourDtosAndContentBoundaries() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andReturn().getResponse(); assertEquals(200, response.getStatus());
        var schemas = json.readTree(response.getContentAsByteArray()).path("components").path("schemas");
        assertAll(List.of("TemplateRequest", "AdapterRequest", "SigningKeyRequest", "PackageRequest").stream().map(name ->
                (org.junit.jupiter.api.function.Executable) () -> assertTrue(schemas.path(name).path("description").asText().contains("外层未知字段兼容忽略"), name)));
        assertTrue(schemas.path("AdapterRequest").path("properties").path("configuration").path("description").asText().contains("动态Map"));
        assertTrue(schemas.path("PackageRequest").path("properties").path("manifestJson").path("description").asText().contains("签名原文"));
    }

    @Test void X01_approvedDuplicateRuleReturns409WithoutWrites() throws Exception {
        var actor = admin(); var body = adapter(); ok(actor, "POST", EXT + "adapters", body); var before = state();
        int plain = send(actor, "POST", EXT + "adapters", body).getStatus();
        int extra = send(actor, "POST", EXT + "adapters", extra(body, MARKER)).getStatus();
        assertEquals(409, plain); assertEquals(409, extra);
        assertEquals(before, state(), "duplicate attempts do not change rows or success audit");
        System.out.println("D23_DUPLICATE_DIAGNOSTIC " + json.writeValueAsString(Map.of("withoutUnknownStatus", plain, "withUnknownStatus", extra, "writesUnchanged", true)));
    }
}
