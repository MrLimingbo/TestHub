package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.extension.ExtensionRegistryRepository;
import io.testhub.platform.security.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class P2D23AdapterConflictTests extends P1FlowTestSupport {
    static final String URL = "/api/v1/extensions/adapters";
    @Autowired ExtensionRegistryRepository registry;
    @Autowired SecretCipher cipher;

    Map<String, String> config() {
        return Map.of("clientId", unique(), "clientSecret", UUID.randomUUID().toString(), "redirectUri", "https://client.qa.invalid/login");
    }
    Map<String, Object> body(String name) {
        return new LinkedHashMap<>(Map.of("adapterType", "OIDC", "displayName", name, "endpoint", "https://login.qa.invalid", "configuration", config(), "enabled", false));
    }
    MockHttpServletRequestBuilder request(String method, String id, Object body) throws Exception {
        var request = method.equals("PUT") ? put(URL + "/" + id) : post(URL);
        return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
    }
    MockHttpServletResponse send(PlatformUserPrincipal actor, String method, String id, Object body) throws Exception {
        return mvc.perform(request(method, id, body).with(user(actor)).with(csrf())).andReturn().getResponse();
    }
    String create(PlatformUserPrincipal actor, Map<String, Object> body) throws Exception {
        var response = send(actor, "POST", null, body); assertEquals(200, response.getStatus());
        return json.readTree(response.getContentAsByteArray()).path("data").path("id").asText();
    }
    long count() { return jdbc.sql("SELECT COUNT(*) FROM enterprise_adapter_configuration").query(Long.class).single(); }
    long audits(String action) { return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action=:action").param("action", action).query(Long.class).single(); }
    long occupied(String name) {
        return jdbc.sql("SELECT COUNT(*) FROM enterprise_adapter_configuration WHERE adapter_type='OIDC' AND display_name=:name").param("name", name).query(Long.class).single();
    }
    Map<String, String> state() throws Exception {
        Map<String, String> state = new LinkedHashMap<>();
        for (String table : List.of("enterprise_adapter_configuration", "audit_log")) {
            var rows = jdbc.sql("SELECT * FROM " + table + " ORDER BY id").query((rs, n) -> {
                Map<String, String> row = new LinkedHashMap<>(); var metadata = rs.getMetaData();
                for (int i = 1; i <= metadata.getColumnCount(); i++) row.put(metadata.getColumnLabel(i), rs.getString(i));
                return row;
            }).list();
            state.put(table, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(rows))));
        }
        return state;
    }
    void conflict(PlatformUserPrincipal actor, String method, String id, Object body) throws Exception {
        var before = state(); var response = send(actor, method, id, body);
        assertEquals(409, response.getStatus(), "duplicate adapter is a controlled conflict");
        assertEquals(before, state(), "conflict preserves complete rows, ciphertext and success audit");
    }
    void savedConfig(String id, Object expected) throws Exception {
        var actual = json.readTree(cipher.decrypt(registry.findAdapter(id).orElseThrow().configurationCiphertext()));
        assertTrue(json.valueToTree(expected).equals(actual), "saved configuration matches the successful request");
    }

    @Test void C01_freeCombinationCreatesNormally() throws Exception {
        var actor = admin(); var input = body(unique()); long rows = count(), audit = audits("ENTERPRISE_ADAPTER_CREATED");
        String id = create(actor, input); var row = registry.findAdapter(id).orElseThrow();
        assertEquals(input.get("displayName"), row.displayName()); assertEquals(rows + 1, count());
        assertEquals(audit + 1, audits("ENTERPRISE_ADAPTER_CREATED")); savedConfig(id, input.get("configuration"));
    }
    @Test void C02_duplicateCreateWithOrWithoutUnknownFieldsReturns409() throws Exception {
        var actor = admin(); var input = body(unique()); create(actor, input); conflict(actor, "POST", null, input);
        for (Object value : List.of("D23C_UNKNOWN", Map.of("nested", true), List.of("extra"))) {
            var extra = new LinkedHashMap<>(input); extra.put("unknown", value); conflict(actor, "POST", null, extra);
        }
    }
    @Test void C03_conflictingRenamePreservesBothRowsAndReplacementSecret() throws Exception {
        var actor = admin(); String name = unique(); create(actor, body(name)); String id = create(actor, body(unique()));
        for (boolean replacement : List.of(false, true)) {
            var update = body(name); if (!replacement) update.put("configuration", null);
            update.put("endpoint", "https://replacement.qa.invalid"); update.put("enabled", true); update.put("unknown", Map.of("ignored", true));
            conflict(actor, "PUT", id, update);
        }
    }
    @Test void C04_sameRecordCanKeepItsNameAndCiphertext() throws Exception {
        var actor = admin(); var input = body(unique()); String id = create(actor, input); String before = registry.findAdapter(id).orElseThrow().configurationCiphertext();
        input.put("configuration", null); input.put("endpoint", "https://metadata.qa.invalid"); input.put("enabled", true);
        long audit = audits("ENTERPRISE_ADAPTER_UPDATED"); assertEquals(200, send(actor, "PUT", id, input).getStatus());
        var row = registry.findAdapter(id).orElseThrow(); assertEquals(input.get("displayName"), row.displayName());
        assertEquals(before, row.configurationCiphertext()); assertEquals(input.get("endpoint"), row.endpoint()); assertTrue(row.enabled());
        assertEquals(audit + 1, audits("ENTERPRISE_ADAPTER_UPDATED"));
    }
    @Test void C05_freeRenameAndConfigurationReplacementStillWork() throws Exception {
        var actor = admin(); String id = create(actor, body(unique())); var update = body(unique());
        long audit = audits("ENTERPRISE_ADAPTER_UPDATED"); assertEquals(200, send(actor, "PUT", id, update).getStatus());
        assertEquals(update.get("displayName"), registry.findAdapter(id).orElseThrow().displayName()); savedConfig(id, update.get("configuration"));
        assertEquals(audit + 1, audits("ENTERPRISE_ADAPTER_UPDATED"));
    }
    @Test void C06_differentTypesMayUseTheSameName() throws Exception {
        var actor = admin(); String name = unique(); create(actor, body(name)); var jira = body(name);
        jira.put("adapterType", "JIRA"); jira.put("configuration", Map.of("username", unique(), "apiToken", UUID.randomUUID().toString(), "projectKey", unique()));
        long rows = count(); String id = create(actor, jira); assertEquals(rows + 1, count());
        assertEquals("JIRA", registry.findAdapter(id).orElseThrow().adapterType()); assertEquals(name, registry.findAdapter(id).orElseThrow().displayName());
    }
    @Test void C07_existingTrimNormalizationStillProduces409() throws Exception {
        var actor = admin(); String name = unique(); create(actor, body(name)); String id = create(actor, body(unique()));
        var input = body(" \t" + name + " \t"); conflict(actor, "POST", null, input); input.put("configuration", null); conflict(actor, "PUT", id, input);
    }
    @Test void C08_conflictEnvelopeIsActionableWithoutEchoingInputOrSql() throws Exception {
        var actor = admin(); var input = body(unique()); create(actor, input); var response = send(actor, "POST", null, input);
        assertEquals(409, response.getStatus()); var error = json.readTree(response.getContentAsByteArray());
        assertEquals("REQUEST_REJECTED", error.path("code").asText()); assertTrue(error.path("message").asText().contains("名称已存在"));
        assertFalse(error.path("requestId").asText().isBlank()); assertFalse(error.has("data"));
        String content = response.getContentAsString(StandardCharsets.UTF_8);
        for (String forbidden : List.of((String) input.get("displayName"), json.valueToTree(input.get("configuration")).path("clientSecret").asText(), "DuplicateKey", "uk_enterprise_adapter", "INSERT INTO", "UPDATE enterprise"))
            assertFalse(content.contains(forbidden), "conflict does not echo request data or SQL");
    }
    @Test void C09_authenticationAndCsrfStillPrecedeConflictHandling() throws Exception {
        var actor = admin(); var ordinary = actor(); var input = body(unique()); String id = create(actor, input); var before = state();
        for (String method : List.of("POST", "PUT")) {
            assertEquals(403, send(ordinary, method, id, input).getStatus());
            assertEquals(401, mvc.perform(request(method, id, input).with(csrf())).andReturn().getResponse().getStatus());
            assertEquals(403, mvc.perform(request(method, id, input).with(user(actor))).andReturn().getResponse().getStatus());
        }
        assertEquals(before, state());
    }

    List<MockHttpServletResponse> simultaneous(Callable<MockHttpServletResponse> first, Callable<MockHttpServletResponse> second) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<MockHttpServletResponse>> pending = new ArrayList<>();
            for (var action : List.of(first, second)) pending.add(pool.submit(() -> {
                ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Owned fixture start timeout"); return action.call();
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            return List.of(pending.get(0).get(30, TimeUnit.SECONDS), pending.get(1).get(30, TimeUnit.SECONDS));
        } finally { start.countDown(); }
    }
    int winner(List<MockHttpServletResponse> responses) {
        assertEquals(List.of(200, 409), responses.stream().map(MockHttpServletResponse::getStatus).sorted().toList(), "one winner and one controlled conflict");
        return responses.get(0).getStatus() == 200 ? 0 : 1;
    }
    @Test void C10_concurrentCreatesHaveOneWinnerAndOneAudit() throws Exception {
        var actor = admin();
        for (int round = 0; round < 3; round++) {
            String name = unique(); var first = body(name); var second = body(name); second.put("unknown", true);
            long rows = count(), audit = audits("ENTERPRISE_ADAPTER_CREATED");
            var responses = simultaneous(() -> send(actor, "POST", null, first), () -> send(actor, "POST", null, second)); int win = winner(responses);
            assertEquals(rows + 1, count()); assertEquals(1, occupied(name)); assertEquals(audit + 1, audits("ENTERPRISE_ADAPTER_CREATED"));
            String id = json.readTree(responses.get(win).getContentAsByteArray()).path("data").path("id").asText(); savedConfig(id, (win == 0 ? first : second).get("configuration"));
        }
    }
    @Test void C11_concurrentRenamesPreserveTheLosingRecord() throws Exception {
        var actor = admin();
        for (int round = 0; round < 3; round++) {
            List<String> ids = List.of(create(actor, body(unique())), create(actor, body(unique())));
            var before = ids.stream().map(id -> registry.findAdapter(id).orElseThrow()).toList();
            String name = unique(); var first = body(name); var second = body(name); first.put("enabled", true); second.put("enabled", true);
            long rows = count(), audit = audits("ENTERPRISE_ADAPTER_UPDATED");
            var responses = simultaneous(() -> send(actor, "PUT", ids.get(0), first), () -> send(actor, "PUT", ids.get(1), second)); int win = winner(responses), lose = 1 - win;
            assertEquals(rows, count()); assertEquals(1, occupied(name)); assertEquals(audit + 1, audits("ENTERPRISE_ADAPTER_UPDATED"));
            assertEquals(before.get(lose), registry.findAdapter(ids.get(lose)).orElseThrow(), "conflict retains complete original row and ciphertext");
            assertEquals(name, registry.findAdapter(ids.get(win)).orElseThrow().displayName()); savedConfig(ids.get(win), (win == 0 ? first : second).get("configuration"));
        }
    }
}
