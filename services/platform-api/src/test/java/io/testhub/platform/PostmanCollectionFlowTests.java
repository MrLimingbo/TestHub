package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
        jdbc.sql("DELETE FROM api_definition WHERE name LIKE '%Postman验收-%'").update();
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
                .andExpect(jsonPath("$.data.importedCount").value(2));

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

    private JsonNode json(MvcResult result) throws Exception { return objectMapper.readTree(result.getResponse().getContentAsString()); }
    private record LoginSession(Cookie sessionCookie) { }
    private record Csrf(Cookie cookie, String headerName, String token) { }
}
