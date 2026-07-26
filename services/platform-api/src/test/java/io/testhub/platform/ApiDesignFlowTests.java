package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
class ApiDesignFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void cleanApiAssets() {
        jdbc.sql("DELETE FROM api_definition WHERE name LIKE '接口验收-%'").update();
        jdbc.sql("DELETE FROM api_catalog WHERE name = '接口验收目录'").update();
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
        mvc.perform(get("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/versions", projectId, apiId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
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
