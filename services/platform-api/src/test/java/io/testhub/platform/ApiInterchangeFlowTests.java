package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
class ApiInterchangeFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void cleanApiAssets() {
        jdbc.sql("DELETE FROM api_definition WHERE name LIKE '交换验收-%'").update();
        jdbc.sql("DELETE FROM api_catalog WHERE name = '交换验收目录'").update();
    }

    @Test
    void importsOpenApiAndSwaggerThenExportsOpenApiAndCurl() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String catalogId = createCatalog(administrator, projectId);

        Csrf csrf = csrf(administrator);
        String openApi = """
                openapi: 3.1.0
                info:
                  title: Exchange API
                  version: 1.2.3
                paths:
                  /api/v1/exchange/orders/{orderId}:
                    get:
                      summary: 交换验收-查询订单
                      description: 从 YAML OpenAPI 导入
                      parameters:
                        - in: path
                          name: orderId
                          required: true
                          schema: { type: string }
                          example: OD-100
                        - in: query
                          name: verbose
                          schema: { type: boolean }
                          example: true
                        - in: header
                          name: Authorization
                          schema: { type: string }
                          example: Bearer token
                      responses:
                        '200':
                          description: Success
                          content:
                            application/json:
                              example: { code: 0, data: { id: OD-100 } }
                """;
        mvc.perform(post("/api/v1/projects/{projectId}/api-design/interchange/openapi/import", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(Map.of("catalogId", catalogId, "document", openApi))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.format").value("OPENAPI"))
                .andExpect(jsonPath("$.data.importedCount").value(1));

        csrf = csrf(administrator);
        String swagger = """
                swagger: '2.0'
                info:
                  title: Legacy exchange API
                  version: 2.0.0
                basePath: /api/v1
                paths:
                  /exchange/swagger-orders/{orderId}:
                    post:
                      summary: 交换验收-Swagger订单
                      parameters:
                        - in: path
                          name: orderId
                          required: true
                          type: string
                        - in: body
                          name: body
                          schema: { type: object, example: { amount: 100 } }
                      responses:
                        '201':
                          description: Created
                          schema: { type: object, example: { code: 0 } }
                """;
        mvc.perform(post("/api/v1/projects/{projectId}/api-design/interchange/openapi/import", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(Map.of("catalogId", catalogId, "document", swagger))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.importedCount").value(1));

        csrf = csrf(administrator);
        String command = "curl -X POST 'https://api.example.com/api/v1/exchange/orders?dryRun=true' -H 'Authorization: Bearer unit-token' --data-raw '{\"amount\":100}'";
        mvc.perform(post("/api/v1/projects/{projectId}/api-design/interchange/curl/import", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(Map.of("catalogId", catalogId, "name", "交换验收-创建订单", "command", command))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.format").value("CURL"))
                .andExpect(jsonPath("$.data.items[0]").value("POST /api/v1/exchange/orders"));

        mvc.perform(get("/api/v1/projects/{projectId}/api-design/interchange/openapi/export", projectId)
                        .param("catalogId", catalogId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(jsonPath("$.paths['/api/v1/exchange/orders/{orderId}'].get.summary").value("交换验收-查询订单"))
                .andExpect(jsonPath("$.paths['/api/v1/exchange/swagger-orders/{orderId}'].post").exists());

        MvcResult definitions = mvc.perform(get("/api/v1/projects/{projectId}/api-design/definitions", projectId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        String apiId = "";
        for (JsonNode definition : json(definitions).path("data")) {
            if (definition.path("name").asText().equals("交换验收-创建订单")) apiId = definition.path("id").asText();
        }
        MvcResult curl = mvc.perform(get("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/interchange/curl", projectId, apiId)
                        .param("baseUrl", "https://runner.example.test").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.command").value(org.hamcrest.Matchers.containsString("curl -X POST")))
                .andExpect(jsonPath("$.data.command").value(org.hamcrest.Matchers.containsString("https://runner.example.test/api/v1/exchange/orders?dryRun=true")))
                .andExpect(jsonPath("$.data.command").value(org.hamcrest.Matchers.containsString("Authorization: Bearer unit-token"))).andReturn();
        assertFalse(json(curl).at("/data/command").asText().contains("\n+"));
    }

    private String createCatalog(LoginSession session, String projectId) throws Exception {
        Csrf csrf = csrf(session);
        MvcResult catalog = mvc.perform(post("/api/v1/projects/{projectId}/api-design/catalogs", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(session.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("{\"name\":\"交换验收目录\",\"sortOrder\":1}"))
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

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record LoginSession(Cookie sessionCookie) { }
    private record Csrf(Cookie cookie, String headerName, String token) { }
}
