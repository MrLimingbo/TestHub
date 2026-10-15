package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
class ApiDesignFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void cleanApiAssets() {
        jdbc.sql("DELETE FROM api_definition WHERE name LIKE '接口验收-%'").update();
        jdbc.sql("DELETE FROM api_catalog WHERE name LIKE '接口验收目录%'").update();
    }

    @Test
    void catalogDefinitionVersionAndPublicationFormApiContractFlow() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MvcResult catalog = mvc.perform(post("/api/v1/projects/{projectId}/api-design/catalogs", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content("{\"name\":\"接口验收目录\",\"sortOrder\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("接口验收目录")).andReturn();
        String catalogId = json(catalog).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult definition = mvc.perform(post("/api/v1/projects/{projectId}/api-design/definitions", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content("""
                                {"catalogId":"%s","method":"GET","path":"/api/v1/acceptance/orders/{orderId}","name":"接口验收-查询订单","versionName":"1.0.0","summary":"查询订单详情","description":"验证接口设计版本化","headers":[{"name":"Authorization","type":"string","required":true,"example":"Bearer token","description":"访问令牌"}],"query":[],"pathParameters":[{"name":"orderId","type":"string","required":true,"example":"OD-1","description":"订单标识"}],"requestBody":"{}","responseBody":"{\\"code\\":0,\\"data\\":{}}","exampleResponse":{"statusCode":200}}
                                """.formatted(catalogId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.versionCount").value(1)).andReturn();
        String apiId = json(definition).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult version = mvc.perform(post("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/versions", projectId, apiId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content("{\"versionName\":\"1.1.0\",\"summary\":\"增加扩展字段\",\"description\":\"第二个版本\",\"headers\":[],\"query\":[],\"pathParameters\":[],\"requestBody\":\"{}\",\"responseBody\":\"{}\",\"exampleResponse\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.versionName").value("1.1.0")).andReturn();
        String versionId = json(version).at("/data/id").asText();

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/versions/{versionId}/publish", projectId, apiId, versionId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/api-design/definitions/{apiId}", projectId, apiId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("""
                                {"catalogId":"%s","method":"POST","path":"/api/v1/acceptance/orders","name":"接口验收-发布后路由不可直改"}
                                """.formatted(catalogId)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/projects/{projectId}/api-design/definitions/{apiId}", projectId, apiId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.method").value("GET"))
                .andExpect(jsonPath("$.data.path").value("/api/v1/acceptance/orders/{orderId}"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        mvc.perform(get("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/versions", projectId, apiId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void updatesCatalogAndDefinitionAndManagesCasesWithSafeCleanupOrder() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MvcResult catalog = mvc.perform(post("/api/v1/projects/{projectId}/api-design/catalogs", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("{\"name\":\"接口验收目录-管理\",\"sortOrder\":2}"))
                .andExpect(status().isOk()).andReturn();
        String catalogId = json(catalog).at("/data/id").asText();

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/api-design/catalogs/{catalogId}", projectId, catalogId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("{\"name\":\"接口验收目录-管理更新\",\"parentId\":null,\"sortOrder\":9}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("接口验收目录-管理更新"))
                .andExpect(jsonPath("$.data.sortOrder").value(9));

        csrf = csrf(administrator);
        MvcResult definition = mvc.perform(post("/api/v1/projects/{projectId}/api-design/definitions", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("""
                                {"catalogId":"%s","method":"GET","path":"/api/v1/acceptance/cases/{caseId}","name":"接口验收-用例管理","versionName":"1.0.0","summary":"调试用例","description":"","headers":[],"query":[],"pathParameters":[],"requestBody":"{}","responseBody":"{}","exampleResponse":{"statusCode":200}}
                                """.formatted(catalogId)))
                .andExpect(status().isOk()).andReturn();
        String apiId = json(definition).at("/data/id").asText();

        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/api-design/catalogs/{catalogId}", projectId, catalogId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isConflict());

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/draft", projectId, apiId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("""
                                {"catalogId":"%s","method":"POST","path":"/api/v1/acceptance/cases/{caseId}","name":"接口验收-不应部分更新","versionName":"1.0.0","summary":"冲突版本","description":"","headers":[],"query":[],"pathParameters":[],"requestBody":"{}","responseBody":"{}","exampleResponse":{"statusCode":201}}
                                """.formatted(catalogId)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/projects/{projectId}/api-design/definitions/{apiId}", projectId, apiId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.method").value("GET"))
                .andExpect(jsonPath("$.data.name").value("接口验收-用例管理"))
                .andExpect(jsonPath("$.data.versionCount").value(1));

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/draft", projectId, apiId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("""
                                {"catalogId":"%s","method":"POST","path":"/api/v1/acceptance/cases/{caseId}","name":"接口验收-参数不合法不应落库","versionName":"1.1.0","summary":"非法参数","description":"","headers":[{"name":"","type":"string","required":true,"example":"","description":""}],"query":[],"pathParameters":[],"requestBody":"{}","responseBody":"{}","exampleResponse":{"statusCode":201}}
                                """.formatted(catalogId)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$['details']['headers[0].name']").exists());
        mvc.perform(get("/api/v1/projects/{projectId}/api-design/definitions/{apiId}", projectId, apiId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.method").value("GET"))
                .andExpect(jsonPath("$.data.versionCount").value(1));

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/draft", projectId, apiId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("""
                                {"catalogId":"%s","method":"POST","path":"/api/v1/acceptance/cases/{caseId}","name":"接口验收-用例管理更新","versionName":"1.1.0","summary":"原子修订","description":"","headers":[],"query":[],"pathParameters":[],"requestBody":"{}","responseBody":"{}","exampleResponse":{"statusCode":201}}
                                """.formatted(catalogId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.method").value("POST"))
                .andExpect(jsonPath("$.data.name").value("接口验收-用例管理更新"))
                .andExpect(jsonPath("$.data.versionCount").value(2))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        Map<String, Object> casePayload = Map.of(
                "name", "正常创建",
                "environmentCode", "test",
                "requestSnapshot", Map.of("method", "POST", "path", "/api/v1/acceptance/cases/CASE-1",
                        "baseUrl", "https://api.example.com/{{resource}}?accessToken={{ACCESS_TOKEN}}",
                        "body", Map.of("amount", 100),
                        "auth", Map.of("token", "plain-token", "password", "{{API_PASSWORD}}", "value", "plain-key",
                                "passwd", "plain-passwd", "session", "plain-session", "jwt", "plain-jwt",
                                "accessToken", "plain-access-token", "refreshToken", "plain-refresh-token"),
                        "headerValues", List.of(
                                Map.of("name", "Authorization", "value", "Bearer plain-token"),
                                Map.of("name", "X-Session-Id", "value", "plain-session"),
                                Map.of("name", "X-Trace", "value", "trace-1")),
                        "queryValues", List.of(
                                Map.of("name", "password", "value", "plain-password"),
                                Map.of("name", "passwd", "value", "plain-passwd"),
                                Map.of("name", "session", "value", "plain-session"),
                                Map.of("name", "jwt", "value", "plain-jwt"),
                                Map.of("name", "accessToken", "value", "plain-access-token"),
                                Map.of("name", "refreshToken", "value", "{{REFRESH_TOKEN}}"),
                                Map.of("name", "trace", "value", "trace-query"))),
                "expectedStatus", 201,
                "responseValidation", "$.code == 0",
                "savedResponseBody", "{\"code\":0,\"id\":\"CASE-1\"}");
        csrf = csrf(administrator);
        MvcResult createdCase = mvc.perform(post("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/cases", projectId, apiId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content(objectMapper.writeValueAsString(casePayload)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("正常创建"))
                .andExpect(jsonPath("$.data.requestSnapshot.body.amount").value(100))
                .andExpect(jsonPath("$.data.requestSnapshot.auth.token").value(""))
                .andExpect(jsonPath("$.data.requestSnapshot.auth.password").value("{{API_PASSWORD}}"))
                .andExpect(jsonPath("$.data.requestSnapshot.auth.value").value(""))
                .andExpect(jsonPath("$.data.requestSnapshot.auth.passwd").value(""))
                .andExpect(jsonPath("$.data.requestSnapshot.auth.session").value(""))
                .andExpect(jsonPath("$.data.requestSnapshot.auth.jwt").value(""))
                .andExpect(jsonPath("$.data.requestSnapshot.auth.accessToken").value(""))
                .andExpect(jsonPath("$.data.requestSnapshot.auth.refreshToken").value(""))
                .andExpect(jsonPath("$.data.requestSnapshot.headerValues[0].value").value(""))
                .andExpect(jsonPath("$.data.requestSnapshot.headerValues[1].value").value(""))
                .andExpect(jsonPath("$.data.requestSnapshot.headerValues[2].value").value("trace-1"))
                .andExpect(jsonPath("$.data.requestSnapshot.queryValues[0].value").value(""))
                .andExpect(jsonPath("$.data.requestSnapshot.queryValues[1].value").value(""))
                .andExpect(jsonPath("$.data.requestSnapshot.queryValues[2].value").value(""))
                .andExpect(jsonPath("$.data.requestSnapshot.queryValues[3].value").value(""))
                .andExpect(jsonPath("$.data.requestSnapshot.queryValues[4].value").value(""))
                .andExpect(jsonPath("$.data.requestSnapshot.queryValues[5].value").value("{{REFRESH_TOKEN}}"))
                .andExpect(jsonPath("$.data.requestSnapshot.queryValues[6].value").value("trace-query"))
                .andExpect(jsonPath("$.data.expectedStatus").value(201)).andReturn();
        String caseId = json(createdCase).at("/data/id").asText();

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/cases", projectId, apiId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content(objectMapper.writeValueAsString(Map.of(
                                "name", "拒绝模板用户信息",
                                "requestSnapshot", Map.of("baseUrl", "https://{{USER}}:plain@example.com/{{resource}}"),
                                "expectedStatus", 200, "responseValidation", "", "savedResponseBody", ""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/cases", projectId, apiId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content(objectMapper.writeValueAsString(Map.of(
                                "name", "拒绝模板敏感查询",
                                "requestSnapshot", Map.of("baseUrl",
                                        "https://api.example.com/{{resource}}?accessToken=plain-access-token"),
                                "expectedStatus", 200, "responseValidation", "", "savedResponseBody", ""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));

        mvc.perform(get("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/cases", projectId, apiId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(caseId));
        mvc.perform(get("/api/v1/projects/{projectId}/api-design/cases", projectId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", hasItem(caseId)));

        Map<String, Object> updatedCasePayload = Map.of(
                "name", "正常创建-更新",
                "environmentCode", "staging",
                "requestSnapshot", Map.of("method", "PUT", "path", "/api/v1/acceptance/cases/CASE-1"),
                "expectedStatus", 202,
                "responseValidation", "$.accepted == true",
                "savedResponseBody", "{\"accepted\":true}");
        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/cases/{caseId}", projectId, apiId, caseId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content(objectMapper.writeValueAsString(updatedCasePayload)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("正常创建-更新"))
                .andExpect(jsonPath("$.data.environmentCode").value("staging"))
                .andExpect(jsonPath("$.data.expectedStatus").value(202));

        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/cases/{caseId}", projectId, apiId, caseId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/cases", projectId, apiId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/cases", projectId, apiId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content(objectMapper.writeValueAsString(Map.of(
                                "name", "级联清理", "requestSnapshot", Map.of("method", "POST"),
                                "expectedStatus", 204, "responseValidation", "", "savedResponseBody", ""))))
                .andExpect(status().isOk());

        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/api-design/definitions/{apiId}", projectId, apiId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM api_definition_version WHERE api_id = :apiId")
                .param("apiId", apiId).query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM api_definition_case WHERE api_id = :apiId")
                .param("apiId", apiId).query(Integer.class).single());

        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/api-design/catalogs/{catalogId}", projectId, catalogId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());
    }

    @Test
    void batchDeleteDefinitionsIsAtomicAndReturnsDeletedCount() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MvcResult catalog = mvc.perform(post("/api/v1/projects/{projectId}/api-design/catalogs", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("{\"name\":\"接口验收目录-批量删除\",\"sortOrder\":3}"))
                .andExpect(status().isOk()).andReturn();
        String catalogId = json(catalog).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult firstDefinition = mvc.perform(post("/api/v1/projects/{projectId}/api-design/definitions", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("""
                                {"catalogId":"%s","method":"GET","path":"/api/v1/acceptance/batch/first","name":"接口验收-批量删除一","versionName":"1.0.0","summary":"批量删除一","description":"","headers":[],"query":[],"pathParameters":[],"requestBody":"{}","responseBody":"{}","exampleResponse":{"statusCode":200}}
                                """.formatted(catalogId)))
                .andExpect(status().isOk()).andReturn();
        String firstApiId = json(firstDefinition).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult secondDefinition = mvc.perform(post("/api/v1/projects/{projectId}/api-design/definitions", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("""
                                {"catalogId":"%s","method":"POST","path":"/api/v1/acceptance/batch/second","name":"接口验收-批量删除二","versionName":"1.0.0","summary":"批量删除二","description":"","headers":[],"query":[],"pathParameters":[],"requestBody":"{}","responseBody":"{}","exampleResponse":{"statusCode":201}}
                                """.formatted(catalogId)))
                .andExpect(status().isOk()).andReturn();
        String secondApiId = json(secondDefinition).at("/data/id").asText();

        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/api-design/definitions", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(Map.of(
                                "ids", List.of(firstApiId, "00000000000000000000000000")))))
                .andExpect(status().isNotFound());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM api_definition WHERE id = :apiId")
                .param("apiId", firstApiId).query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM api_definition WHERE id = :apiId")
                .param("apiId", secondApiId).query(Integer.class).single());

        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/api-design/definitions", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(Map.of("ids", List.of(firstApiId, secondApiId)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deletedCount").value(2));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM api_definition WHERE id = :apiId")
                .param("apiId", firstApiId).query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM api_definition WHERE id = :apiId")
                .param("apiId", secondApiId).query(Integer.class).single());

        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/api-design/catalogs/{catalogId}", projectId, catalogId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());
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
