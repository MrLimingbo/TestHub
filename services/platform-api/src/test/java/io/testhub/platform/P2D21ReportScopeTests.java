package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.account.PlatformUserPrincipal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "testhub.registration.enabled=true", "testhub.bootstrap.demo-enabled=false"})
class P2D21ReportScopeTests extends P1FlowTestSupport {
    @LocalServerPort int port;
    private final List<HttpClient> clients = new ArrayList<>();

    @AfterEach void closeClients() { clients.forEach(HttpClient::close); }

    record Fixture(PlatformUserPrincipal actor, HttpClient client, String organization, List<String> ids) { }

    Fixture fixture(int count) throws Exception {
        String name = unique(), password = password();
        var actor = new PlatformUserPrincipal(registration.register(name, name + "@example.test", "D21隔离QA", password));
        var context = access.context(actor);
        String organization = context.organizations().getFirst().id();
        List<String> ids = new ArrayList<>(List.of(project(actor)));
        for (int i = 1; i < count; i++) {
            String id = UlidCreator.getUlid().toString();
            memberships.insertProject(id, organization, "D21项目" + i, "d21-" + id.toLowerCase());
            ids.add(id);
        }
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();
        clients.add(client);
        var csrf = json.readTree(send(client, "/api/v1/auth/csrf").body()).path("data");
        var login = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("email", actor.getUsername(), "password", password))))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, login.statusCode(), "Owned fixture login");
        return new Fixture(actor, client, organization, ids);
    }

    URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    HttpResponse<String> send(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    String query(String value) { return "projectIds=" + URLEncoder.encode(value, StandardCharsets.UTF_8); }
    String path(boolean export, String query) {
        return "/api/v1/reports/quality" + (export ? "/export" : "")
                + "?from=2026-11-04&to=2026-11-04" + (query == null ? "" : "&" + query);
    }
    long exports() { return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action='QUALITY_REPORT_EXPORTED'").query(Long.class).single(); }

    void scope(Fixture fixture, String query, List<String> expected) throws Exception {
        long before = exports();
        var report = send(fixture.client(), path(false, query));
        assertEquals(200, report.statusCode(), "query status");
        var data = json.readTree(report.body()).path("data");
        assertEquals(expected.size(), data.path("projectCount").asInt());
        List<String> actual = new ArrayList<>();
        data.path("projects").forEach(row -> actual.add(row.path("projectId").asText()));
        assertEquals(expected, actual, "query scope/order");
        assertEquals(before, exports(), "query is read only");
        var csv = send(fixture.client(), path(true, query));
        assertEquals(200, csv.statusCode(), "CSV status");
        assertTrue(csv.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
        var lines = csv.body().lines().filter(line -> line.startsWith("\"项目健康度\",")).toList();
        assertEquals(expected.size(), lines.size(), "CSV scope");
        for (int i = 0; i < expected.size(); i++) {
            String code = memberships.findProject(expected.get(i)).orElseThrow().code();
            assertTrue(lines.get(i).contains("\"" + code + "\""), "CSV project order");
        }
        assertEquals(before + expected.size(), exports(), "only selected projects get export audit");
    }

    void rejected(Fixture fixture, String query, int status) throws Exception {
        long before = exports();
        for (boolean export : List.of(false, true)) {
            var response = send(fixture.client(), path(export, query));
            assertEquals(status, response.statusCode(), "raw query rejection: " + query + "; export=" + export);
            assertFalse(response.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
            assertFalse(json.readTree(response.body()).has("data"));
            for (String id : fixture.ids()) assertFalse(response.body().contains(id), "no project data in rejection");
        }
        assertEquals(before, exports(), "rejected report/export does not produce success audit");
    }

    @Test void B01_omittedUsesExistingDefault() throws Exception {
        var f = fixture(2);
        var expected = access.context(f.actor()).projects().get(f.organization()).stream().map(p -> p.id()).toList();
        scope(f, null, expected);
    }
    @Test void B02_explicitEmptyAndBlankAreRejected() throws Exception {
        var f = fixture(2);
        for (String q : List.of("projectIds", "projectIds=", query(" "), query("\t"))) rejected(f, q, 400);
    }
    @Test void B03_commaOnlyIsRejected() throws Exception {
        var f = fixture(2);
        for (String value : List.of(",", ",,", ",,,")) {
            rejected(f, query(value), 400);
            rejected(f, query(f.ids().getFirst()) + "&" + query(value), 400);
        }
    }
    @Test void B04_emptyCsvSegmentsAreRejected() throws Exception {
        var f = fixture(2); String a = f.ids().get(0), b = f.ids().get(1);
        for (String value : List.of(a + ",", "," + a, a + ",," + b, a + ", ," + b, a + ",,")) {
            rejected(f, query(value), 400);
            rejected(f, query(value) + "&" + query(b), 400);
        }
    }
    @Test void B05_emptyRepeatedKeysAreRejected() throws Exception {
        var f = fixture(2); String valid = query(f.ids().getFirst());
        for (String q : List.of(valid + "&projectIds=", "projectIds=&" + valid, valid + "&" + query(" "), "projectIds&" + valid)) rejected(f, q, 400);
    }
    @Test void B06_singleIdRestrictsBothOutputs() throws Exception {
        var f = fixture(2); scope(f, query(f.ids().get(1)), List.of(f.ids().get(1)));
    }
    @Test void B07_mixedKeysCsvTrimAndDedupKeepOrder() throws Exception {
        var f = fixture(3); String a = f.ids().get(0), b = f.ids().get(1), c = f.ids().get(2);
        scope(f, query(" " + b + " , " + a + " ") + "&" + query(c + "," + b), List.of(b, a, c));
    }
    @Test void B08_fiftyUniqueIdsAccepted() throws Exception {
        var f = fixture(50); scope(f, query(String.join(",", f.ids())), f.ids());
    }
    @Test void B09_fiftyOneUniqueIdsRejected() throws Exception {
        var f = fixture(51); rejected(f, query(String.join(",", f.ids())), 400);
    }
    @Test void B10_limitIsAppliedAfterDedup() throws Exception {
        var f = fixture(50); scope(f, query(String.join(",", f.ids())) + "&" + query(f.ids().getFirst()), f.ids());
    }
    @Test void B11_defaultOverFiftyIsNotNewlyLimited() throws Exception {
        var f = fixture(51);
        var expected = access.context(f.actor()).projects().get(f.organization()).stream().map(p -> p.id()).toList();
        scope(f, null, expected);
    }
    @Test void B12_crossOrganizationCannotLeakOrAudit() throws Exception {
        var f = fixture(1); var other = fixture(1);
        rejected(f, query(f.ids().getFirst() + "," + other.ids().getFirst()), 403);
    }
    @Test void B13_projectMembershipBoundaryIsPreserved() throws Exception {
        var owner = fixture(2); var viewer = fixture(1);
        memberships.insertMembership(UlidCreator.getUlid().toString(), viewer.actor().userId(), owner.organization(), owner.ids().getFirst(), "VIEWER");
        rejected(viewer, query(owner.ids().getFirst() + "," + owner.ids().get(1)), 403);
    }
}
