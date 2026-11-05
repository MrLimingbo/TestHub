package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.UserAccountRepository.ManagedAccount;
import io.testhub.platform.account.UserAccountRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class P2D06AccountSearchTests extends P1FlowTestSupport {
    @Autowired Environment environment;
    ManagedAccount create(PlatformUserPrincipal admin, String display, String phone, boolean enabled) {
        String name = "d06-" + UUID.randomUUID();
        return accounts.create(admin, name, display, name + "@example.test", phone, enabled);
    }

    Set<String> fourFields(PlatformUserPrincipal admin, String needle) {
        Set<String> ids = new HashSet<>();
        for (int field = 0; field < 4; field++) {
            String tag = UUID.randomUUID().toString().substring(0, 8);
            String account = field == 0 ? "a" + needle + tag : "d06-" + tag;
            String display = field == 1 ? "x" + needle + "z" : "QA";
            String email = field == 2 ? "x" + needle + tag + "@example.test" : tag + "@example.test";
            String phone = field == 3 ? "0" + needle + "9" : null;
            ids.add(accounts.create(admin, account, display, email, phone, true).id());
        }
        return ids;
    }

    void matches(PlatformUserPrincipal admin, String keyword, Set<String> expected) {
        var page = accounts.list(admin, keyword, 1, 100);
        assertEquals(expected.size(), page.total(), "total must use literal matching");
        assertEquals(expected, page.items().stream().map(ManagedAccount::id).collect(Collectors.toSet()));
    }

    List<Map<String, Object>> rows() {
        return jdbc.sql("SELECT id,account,display_name,email,phone,enabled,security_version,deleted_at FROM user_account ORDER BY id")
                .query().listOfRows();
    }

    @Test void B01_plainTrimCaseAndUnicode() {
        var admin = admin(); var ids = fourFields(admin, "AlphaMix");
        matches(admin, "  ALPHAMIX  ", ids); matches(admin, "pham", ids);
        var chinese = create(admin, "中文定位测试", null, true);
        matches(admin, "定位测试", Set.of(chinese.id()));
    }

    @Test void B02_percentIsLiteralAcrossFields() {
        var admin = admin(); var ids = fourFields(admin, "%"); create(admin, "ordinary", null, true);
        matches(admin, "%", ids);
    }

    @Test void B03_underscoreIsLiteralAcrossFields() {
        var admin = admin(); var ids = fourFields(admin, "_"); create(admin, "ordinary-x", null, true);
        matches(admin, "_", ids);
    }

    @Test void B04_backslashAndTrailingBackslash() {
        var admin = admin(); matches(admin, "\\", fourFields(admin, "\\"));
        matches(admin, "ends\\", fourFields(admin, "ends\\"));
    }

    @Test void B05_combinedRepeatedAndEscapeCharacter() {
        var admin = admin();
        for (String special : List.of("%%", "__", "\\\\", "%_\\!", "!", "!!", "%\\_!!")) {
            String needle = "edge" + special + "tail";
            var ids = fourFields(admin, needle); create(admin, needle.replaceAll("[%_\\\\!]", "x"), null, true);
            matches(admin, needle, ids);
        }
    }

    @Test void B06_quoteAndSqlTextRemainData() {
        var admin = admin(); String keyword = "' OR 1=1 --";
        var target = create(admin, "left" + keyword + "right", null, true);
        var before = rows(); long audit = auditCount();
        matches(admin, keyword, Set.of(target.id())); matches(admin, "'; DROP TABLE user_account; --", Set.of());
        assertEquals(before, rows()); assertEquals(audit, auditCount());
    }

    @Test void B07_emptyDisabledDeletedAndNullPhone() throws Exception {
        var admin = admin(); var expected = new HashSet<>(jdbc.sql("SELECT id FROM user_account WHERE deleted_at IS NULL").query(String.class).list());
        var enabled = create(admin, "active", null, true);
        var disabled = create(admin, "disabled", null, false);
        var deleted = create(admin, "deleted", null, true);
        expected.add(enabled.id()); expected.add(disabled.id()); accounts.delete(admin, deleted.id());
        matches(admin, null, expected);
        for (String blank : List.of("", "   ", "\t\n", "　")) matches(admin, blank, expected);
        matches(admin, deleted.account(), Set.of()); matches(admin, "disabled", Set.of(disabled.id()));
        assertEquals(200, mvc.perform(get("/api/v1/accounts").with(user(admin))).andReturn().getResponse().getStatus());
    }

    @Test void B08_listCountAndPageBoundaries() {
        var admin = admin(); String keyword = "page%_\\!"; List<String> ids = new ArrayList<>();
        for (int n = 0; n < 13; n++) {
            String name = "d06-page-" + String.format(Locale.ROOT, "%02d", n);
            var row = accounts.create(admin, name, keyword, name + "@example.test", null, true); ids.add(row.id());
            jdbc.sql("UPDATE user_account SET created_at=:time WHERE id=:id").param("time", java.sql.Timestamp.valueOf("2026-11-04 00:00:00")).param("id", row.id()).update();
        }
        create(admin, "pageXXXX", null, true);
        List<String> found = new ArrayList<>();
        for (int page = 1; page <= 4; page++) {
            var result = accounts.list(admin, keyword, page, 5);
            assertEquals(13, result.total()); assertEquals(List.of(5, 5, 3, 0).get(page - 1).intValue(), result.items().size());
            found.addAll(result.items().stream().map(ManagedAccount::id).toList());
        }
        assertEquals(ids, found);
    }

    @Test void B09_originalPageClamping() {
        var admin = admin(); create(admin, "bound%_\\!", null, true);
        var small = accounts.list(admin, "bound%_\\!", -1, 0);
        assertEquals(1, small.page()); assertEquals(1, small.pageSize()); assertEquals(1, small.total());
        var big = accounts.list(admin, "bound%_\\!", Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(100_000, big.page()); assertEquals(100, big.pageSize()); assertEquals(1, big.total()); assertTrue(big.items().isEmpty());
    }

    @Test void B10_unauthorizedAndProjectTokensCannotSearch() throws Exception {
        var admin = admin(); var ordinary = actor();
        var token = access.createToken(admin, project(admin), unique(), List.of("project:read"), 1);
        var before = rows(); long audit = auditCount();
        assertEquals(401, mvc.perform(get("/api/v1/accounts").param("keyword", "%")).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(get("/api/v1/accounts").param("keyword", "%").with(user(ordinary))).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(get("/api/v1/accounts").param("keyword", "%").header("Authorization", "Bearer " + token.token())).andReturn().getResponse().getStatus());
        assertEquals(before, rows()); assertEquals(audit, auditCount());
    }

    @Test void B11_explicitEscapeAcrossDatabaseModesAndLongInput() throws Exception {
        var admin = admin(); String keyword = "mode\\%_!end"; var ids = fourFields(admin, keyword);
        matches(admin, keyword, ids); matches(admin, "q".repeat(300), Set.of());
        if (FunctionalDatabaseProfile.mysql()) {
            String previous = jdbc.sql("SELECT @@SESSION.sql_mode").query(String.class).single();
            String modified = previous.contains("NO_BACKSLASH_ESCAPES") ? previous : previous + ",NO_BACKSLASH_ESCAPES";
            boolean midSessionRoundTrip;
            try {
                jdbc.sql("SET SESSION sql_mode=:mode").param("mode", modified).update();
                midSessionRoundTrip = keyword.equals(jdbc.sql("SELECT :value").param("value", keyword).query(String.class).single());
            } finally { jdbc.sql("SET SESSION sql_mode=:mode").param("mode", previous).update(); }
            String base = Arrays.stream(previous.split(",")).filter(mode -> !mode.equals("NO_BACKSLASH_ESCAPES")).collect(Collectors.joining(","));
            freshMysqlMode(keyword, base, false);
            freshMysqlMode(keyword, base.isEmpty() ? "NO_BACKSLASH_ESCAPES" : base + ",NO_BACKSLASH_ESCAPES", true);
            System.out.println("D06_MODE_DIAGNOSTIC " + json.writeValueAsString(Map.of("midSessionParameterRoundTripUnchanged", midSessionRoundTrip, "freshConnectionModesVerified", 2)));
        }
    }

    private void freshMysqlMode(String keyword, String mode, boolean noBackslashEscapes) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", "root");
        properties.setProperty("password", System.getenv("TESTHUB_TEST_MYSQL_PASSWORD"));
        // Configure the mode while connecting so Connector/J and the server agree on parameter encoding.
        properties.setProperty("sessionVariables", "sql_mode='" + mode + "'");
        try (var connection = java.sql.DriverManager.getConnection(environment.getRequiredProperty("spring.datasource.url"), properties)) {
            connection.setAutoCommit(false);
            try {
                var isolated = JdbcClient.create(new SingleConnectionDataSource(connection, true));
                assertEquals(noBackslashEscapes, isolated.sql("SELECT @@SESSION.sql_mode").query(String.class).single().contains("NO_BACKSLASH_ESCAPES"));
                assertEquals(keyword, isolated.sql("SELECT :value").param("value", keyword).query(String.class).single());
                Set<String> expected = new HashSet<>();
                for (int field = 0; field < 4; field++) {
                    String tag = UUID.randomUUID().toString().substring(0, 8), id = com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();
                    isolated.sql("INSERT INTO user_account(id,account,display_name,email,phone,password_hash,enabled) VALUES(:id,:account,:display,:email,:phone,:hash,TRUE)")
                            .param("id", id).param("account", field == 0 ? "a" + keyword + tag : "d06-mode-" + tag)
                            .param("display", field == 1 ? "x" + keyword + "z" : "QA")
                            .param("email", field == 2 ? "x" + keyword + tag + "@example.test" : tag + "@example.test")
                            .param("phone", field == 3 ? "0" + keyword + "9" : null)
                            .param("hash", "qa-unusable-authentication-hash").update();
                    expected.add(id);
                }
                var repository = new UserAccountRepository(isolated);
                assertEquals(4, repository.count(keyword));
                assertEquals(expected, repository.findPage(keyword, 0, 100).stream().map(ManagedAccount::id).collect(Collectors.toSet()));
            } finally { connection.rollback(); }
        }
    }

    @Test void B12_encodedHttpResponseAndReadOnlyFields() throws Exception {
        var admin = admin(); String keyword = "edge%_\\!+'tail";
        var target = create(admin, keyword, "555", true); var before = rows(); long audit = auditCount();
        URI uri = URI.create("/api/v1/accounts?keyword=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8));
        var response = mvc.perform(get(uri).with(user(admin))).andReturn().getResponse(); assertEquals(200, response.getStatus());
        var page = json.readTree(response.getContentAsByteArray()).path("data"); assertEquals(1, page.path("total").asInt());
        var row = page.path("items").get(0); assertEquals(target.id(), row.path("id").asText());
        Set<String> fields = new HashSet<>(); row.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id", "account", "displayName", "email", "phone", "enabled", "createdAt", "updatedAt"), fields);
        assertEquals(before, rows()); assertEquals(audit, auditCount());
    }
}
