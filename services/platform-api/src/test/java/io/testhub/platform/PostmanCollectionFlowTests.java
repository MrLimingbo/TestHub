package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class PostmanCollectionFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void cleanApiAssets() {
        jdbc.sql("""
                DELETE FROM api_definition
                WHERE catalog_id IN (SELECT id FROM api_catalog WHERE name = 'Postman验收目录')
                """).update();
        jdbc.sql("DELETE FROM api_catalog WHERE name = 'Postman验收目录'").update();
    }

    @Test
    void importsNestedPostmanCollectionAndExportsV21Collection() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String catalogId = createCatalog(administrator, projectId);
        String collection = """
                {
                  "info": {
                    "name": "Postman 验收 Collection",
                    "version": "1.4.0",
                    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
                  },
                  "item": [{
                    "name": "订单",
                    "item": [{
                      "name": "Postman验收-查询订单",
                      "request": {
                        "method": "GET",
                        "description": "查询 Postman 订单",
                        "header": [{"key": "Authorization", "value": "Bearer token"}],
                        "url": {
                          "raw": "{{baseUrl}}/api/v1/postman/orders/:orderId?verbose=true",
                          "host": ["{{baseUrl}}"],
                          "path": ["api", "v1", "postman", "orders", ":orderId"],
                          "query": [{"key": "verbose", "value": "true"}]
                        }
                      },
                      "response": [{"name": "OK", "code": 200, "body": "{\\"code\\":0}"}]
                    }, {
                      "name": "Postman验收-创建订单",
                      "request": {
                        "method": "POST",
                        "header": [{"key": "X-Trace", "value": "postman"}],
                        "body": {"mode": "raw", "raw": "{\\"amount\\":100}", "options": {"raw": {"language": "json"}}},
                        "url": "{{baseUrl}}/api/v1/postman/orders"
                      }
                    }]
                  }]
                }
                """;

        Csrf csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-design/interchange/postman/import", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(Map.of("catalogId", catalogId, "document", collection))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.format").value("POSTMAN"))
                .andExpect(jsonPath("$.data.importedCount").value(2))
                .andExpect(jsonPath("$.data.caseCount").value(2))
                .andExpect(jsonPath("$.data.skippedCount").value(0))
                .andExpect(jsonPath("$.data.warnings.length()").value(0));

        MvcResult export = mvc.perform(get("/api/v1/projects/{projectId}/api-design/interchange/postman/export", projectId)
                        .param("catalogId", catalogId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.info.schema").value("https://schema.getpostman.com/json/collection/v2.1.0/collection.json"))
                .andExpect(jsonPath("$.variable[0].key").value("baseUrl"))
                .andExpect(jsonPath("$.item.length()").value(2)).andReturn();
        JsonNode exportedItems = json(export).path("item");
        JsonNode getRequest = exportedItems.findValue("request");
        for (JsonNode item : exportedItems) {
            if (item.path("request").path("method").asText().equals("GET")) getRequest = item.path("request");
        }
        JsonNode postRequest = exportedItems.get(0).path("request").path("method").asText().equals("POST") ? exportedItems.get(0).path("request") : exportedItems.get(1).path("request");
        assertEquals("{{baseUrl}}/api/v1/postman/orders/{orderId}?verbose=true", getRequest.path("url").path("raw").asText());
        assertEquals("{\"amount\":100}", postRequest.path("body").path("raw").asText());
        assertEquals(2, jdbc.sql("""
                SELECT COUNT(*) FROM api_definition_case c
                JOIN api_definition d ON d.id = c.api_id
                WHERE d.catalog_id = :catalogId
                """).param("catalogId", catalogId).query(Integer.class).single());
    }

    @Test
    void importsLargeBodiesGroupsDuplicateRoutesSkipsEmptyUrlAndSanitizesSensitiveHeaders() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String catalogId = createCatalog(administrator, projectId);
        String body = "{\"image\":\"" + "A".repeat(720_000) + "\"}";
        String collection = largeDuplicateCollection(body);
        assertTrue(collection.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024);

        Csrf csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-design/interchange/postman/import", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(Map.of("catalogId", catalogId, "document", collection))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.importedCount").value(1))
                .andExpect(jsonPath("$.data.caseCount").value(3))
                .andExpect(jsonPath("$.data.skippedCount").value(1))
                .andExpect(jsonPath("$.data.warnings[0]").value("已跳过“New Request”：缺少 URL"));

        String apiId = jdbc.sql("SELECT id FROM api_definition WHERE catalog_id = :catalogId")
                .param("catalogId", catalogId).query(String.class).single();
        String storedHeaders = jdbc.sql("""
                SELECT v.request_headers FROM api_definition_version v
                JOIN api_definition d ON d.current_version_id = v.id
                WHERE d.id = :apiId
                """).param("apiId", apiId).query(String.class).single();
        assertEquals("", parameterValue(objectMapper.readTree(storedHeaders), "Authorization", "example"));
        String storedQuery = jdbc.sql("""
                SELECT v.request_query FROM api_definition_version v
                JOIN api_definition d ON d.current_version_id = v.id
                WHERE d.id = :apiId
                """).param("apiId", apiId).query(String.class).single();
        assertEquals("", objectMapper.readTree(storedQuery).get(0).path("example").asText());
        assertEquals(body.length(), jdbc.sql("""
                SELECT CHAR_LENGTH(v.request_body) FROM api_definition_version v
                JOIN api_definition d ON d.current_version_id = v.id
                WHERE d.id = :apiId
                """).param("apiId", apiId).query(Integer.class).single());

        assertEquals(3, jdbc.sql("SELECT COUNT(*) FROM api_definition_case WHERE api_id = :apiId")
                .param("apiId", apiId).query(Integer.class).single());
        String firstCase = jdbc.sql("""
                SELECT request_snapshot FROM api_definition_case
                WHERE api_id = :apiId AND name = 'Postman验收-大正文'
                """).param("apiId", apiId).query(String.class).single();
        JsonNode firstSnapshot = objectMapper.readTree(firstCase);
        assertEquals("http://localhost:8000", firstSnapshot.path("baseUrl").asText());
        assertEquals("", parameterValue(firstSnapshot.path("queryValues"), "access_token", "value"));
        assertEquals(body.length(), firstSnapshot.path("body").asText().length());
        String secondCase = jdbc.sql("""
                SELECT request_snapshot FROM api_definition_case
                WHERE api_id = :apiId AND name = 'Postman验收-大正文 (2)'
                """).param("apiId", apiId).query(String.class).single();
        assertEquals("", parameterValue(objectMapper.readTree(secondCase).path("headerValues"),
                "Authorization", "value"));
        List<String> caseNames = jdbc.sql("SELECT name FROM api_definition_case WHERE api_id = :apiId")
                .param("apiId", apiId).query(String.class).list();
        assertEquals(3, caseNames.size());
        assertTrue(caseNames.containsAll(List.of("Postman验收-大正文", "Postman验收-大正文 (2)",
                "Postman验收-大正文 (3)")));
    }

    @Test
    void preflightsEveryRequestBeforeWritingAnyAssets() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String catalogId = createCatalog(administrator, projectId);
        ObjectNode collection = collection("Postman 验收原子性");
        ArrayNode items = collection.withArray("item");
        items.add(postmanItem("Postman验收-先校验", "/api/v1/postman/preflight-ok", "{}"));
        items.add(postmanItem("Postman验收-超限", "/api/v1/postman/preflight-too-large", "X".repeat(2_000_001)));

        Csrf csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-design/interchange/postman/import", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(Map.of("catalogId", catalogId,
                                "document", objectMapper.writeValueAsString(collection)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));

        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM api_definition WHERE catalog_id = :catalogId")
                .param("catalogId", catalogId).query(Integer.class).single());
    }

    @Test
    void rejectsPostmanDocumentOverTenMebibytesByUtf8Bytes() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String catalogId = createCatalog(administrator, projectId);
        String multibyteDocument = "界".repeat(3_600_000);
        assertTrue(multibyteDocument.length() < 10_485_760);
        assertTrue(multibyteDocument.getBytes(StandardCharsets.UTF_8).length > 10 * 1024 * 1024);

        Csrf csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-design/interchange/postman/import", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(Map.of("catalogId", catalogId,
                                "document", multibyteDocument))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Postman Collection 文档不能超过 10 MiB"));
    }

    private String largeDuplicateCollection(String body) throws Exception {
        ObjectNode collection = collection("Postman 验收大文件");
        ArrayNode items = collection.withArray("item");
        for (int index = 0; index < 3; index++) {
            ObjectNode item = postmanItem("Postman验收-大正文", "/v1/chat/completions", body);
            ObjectNode request = (ObjectNode) item.path("request");
            if (index > 0) {
                request.withArray("header").addObject().put("key", "Authorization")
                        .put("value", "Bearer not-a-real-token");
            }
            ObjectNode url = (ObjectNode) request.path("url");
            url.put("raw", "localhost:8000/v1/chat/completions?access_token=not-a-real-token");
            url.remove("protocol");
            url.putArray("host").add("localhost");
            url.put("port", "8000");
            url.putArray("query").addObject().put("key", "access_token")
                    .put("value", "not-a-real-token");
            items.add(item);
        }
        ObjectNode empty = items.addObject();
        empty.put("name", "New Request");
        empty.putObject("request").put("method", "GET");
        return objectMapper.writeValueAsString(collection);
    }

    private ObjectNode collection(String name) {
        ObjectNode collection = objectMapper.createObjectNode();
        ObjectNode info = collection.putObject("info");
        info.put("name", name);
        info.put("version", "1.0.0");
        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        collection.putArray("item");
        return collection;
    }

    private ObjectNode postmanItem(String name, String path, String body) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("name", name);
        ObjectNode request = item.putObject("request");
        request.put("method", "POST");
        request.putArray("header");
        ObjectNode requestBody = request.putObject("body");
        requestBody.put("mode", "raw");
        requestBody.put("raw", body);
        requestBody.putObject("options").putObject("raw").put("language", "json");
        ObjectNode url = request.putObject("url");
        url.put("raw", "https://api.example.com" + path);
        url.put("protocol", "https");
        url.putArray("host").add("api").add("example").add("com");
        ArrayNode pathParts = url.putArray("path");
        for (String part : path.substring(1).split("/")) pathParts.add(part);
        return item;
    }

    private String createCatalog(LoginSession session, String projectId) throws Exception {
        Csrf csrf = csrf(session);
        MvcResult catalog = mvc.perform(post("/api/v1/projects/{projectId}/api-design/catalogs", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(session.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("{\"name\":\"Postman验收目录\",\"sortOrder\":1}"))
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

    private String parameterValue(JsonNode parameters, String name, String field) {
        for (JsonNode parameter : parameters) {
            if (name.equalsIgnoreCase(parameter.path("name").asText())) return parameter.path(field).asText();
        }
        throw new AssertionError("missing parameter: " + name);
    }

    private JsonNode json(MvcResult result) throws Exception { return objectMapper.readTree(result.getResponse().getContentAsString()); }
    private record LoginSession(Cookie sessionCookie) { }
    private record Csrf(Cookie cookie, String headerName, String token) { }
}
