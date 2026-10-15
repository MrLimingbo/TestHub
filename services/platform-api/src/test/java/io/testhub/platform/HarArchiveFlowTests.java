package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
class HarArchiveFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void cleanHarAssets() {
        jdbc.sql("DELETE FROM api_definition WHERE path LIKE '/api/v1/har-acceptance/%'").update();
        jdbc.sql("DELETE FROM api_catalog WHERE name = 'HAR验收目录'").update();
    }

    @Test
    void importsHar12AndExportsReimportableArchive() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String catalogId = createCatalog(administrator, projectId);
        String archive = """
                {
                  "log": {
                    "version": "1.2",
                    "creator": {"name": "Browser", "version": "1"},
                    "entries": [{
                      "startedDateTime": "2026-07-13T00:00:00.000Z",
                      "time": 12,
                      "request": {
                        "method": "GET",
                        "url": "https://gateway.example.com/api/v1/har-acceptance/orders/42?include=items",
                        "httpVersion": "HTTP/1.1",
                        "headers": [{"name": "Authorization", "value": "Bearer har-token"}],
                        "queryString": [{"name": "include", "value": "items"}],
                        "cookies": [], "headersSize": -1, "bodySize": 0
                      },
                      "response": {
                        "status": 200, "statusText": "OK", "httpVersion": "HTTP/1.1",
                        "headers": [], "cookies": [],
                        "content": {"size": 11, "mimeType": "application/json", "text": "{\\"id\\":42}"},
                        "redirectURL": "", "headersSize": -1, "bodySize": 11
                      },
                      "cache": {}, "timings": {"wait": 12}
                    }, {
                      "startedDateTime": "2026-07-13T00:00:01.000Z",
                      "time": 24,
                      "request": {
                        "method": "POST",
                        "url": "https://gateway.example.com/api/v1/har-acceptance/orders",
                        "httpVersion": "HTTP/1.1",
                        "headers": [{"name": "X-Trace", "value": "har-trace"}], "queryString": [], "cookies": [],
                        "postData": {"mimeType": "application/json", "text": "{\\"amount\\":100}"},
                        "headersSize": -1, "bodySize": 14
                      },
                      "response": {
                        "status": 201, "statusText": "Created", "httpVersion": "HTTP/1.1",
                        "headers": [], "cookies": [],
                        "content": {"size": 11, "mimeType": "application/json", "text": "{\\"ok\\":true}"},
                        "redirectURL": "", "headersSize": -1, "bodySize": 11
                      },
                      "cache": {}, "timings": {"wait": 24}
                    }]
                  }
                }
                """;

        Csrf csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-design/interchange/har/import", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(Map.of("catalogId", catalogId, "document", archive))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.format").value("HAR"))
                .andExpect(jsonPath("$.data.importedCount").value(2));

        MvcResult export = mvc.perform(get("/api/v1/projects/{projectId}/api-design/interchange/har/export", projectId)
                        .param("catalogId", catalogId).param("baseUrl", "https://replay.example.com")
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.log.version").value("1.2"))
                .andExpect(jsonPath("$.log.creator.name").value("TestHub"))
                .andExpect(jsonPath("$.log.entries.length()").value(2)).andReturn();

        JsonNode entries = json(export).path("log").path("entries");
        JsonNode getEntry = entry(entries, "GET");
        JsonNode postEntry = entry(entries, "POST");
        assertEquals("https://replay.example.com/api/v1/har-acceptance/orders/42?include=items", getEntry.at("/request/url").asText());
        assertEquals("Bearer har-token", getEntry.at("/request/headers/0/value").asText());
        assertEquals("{\"amount\":100}", postEntry.at("/request/postData/text").asText());
        assertEquals("application/json", postEntry.at("/request/postData/mimeType").asText());
        assertEquals(201, postEntry.at("/response/status").asInt());
        assertTrue(postEntry.at("/response/content/text").asText().contains("ok"));
    }

    private JsonNode entry(JsonNode entries, String method) {
        for (JsonNode entry : entries) if (entry.at("/request/method").asText().equals(method)) return entry;
        throw new AssertionError("Missing " + method + " HAR entry");
    }

    private String createCatalog(LoginSession session, String projectId) throws Exception {
        Csrf csrf = csrf(session);
        MvcResult catalog = mvc.perform(post("/api/v1/projects/{projectId}/api-design/catalogs", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(session.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("{\"name\":\"HAR验收目录\",\"sortOrder\":1}"))
                .andExpect(status().isOk()).andReturn();
        return json(catalog).at("/data/id").asText();
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

    private Csrf csrf(LoginSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").cookie(session.sessionCookie())).andExpect(status().isOk()).andReturn();
        JsonNode data = json(result).path("data");
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"), data.path("headerName").asText(), data.path("token").asText());
    }

    private String projectId(LoginSession session) throws Exception {
        MvcResult context = mvc.perform(get("/api/v1/access/context").cookie(session.sessionCookie())).andExpect(status().isOk()).andReturn();
        String organizationId = json(context).at("/data/organizations/0/id").asText();
        return json(context).at("/data/projects/" + organizationId + "/0/id").asText();
    }

    private JsonNode json(MvcResult result) throws Exception { return objectMapper.readTree(result.getResponse().getContentAsString()); }
    private record LoginSession(Cookie sessionCookie) { }
    private record Csrf(Cookie cookie, String headerName, String token) { }
}
