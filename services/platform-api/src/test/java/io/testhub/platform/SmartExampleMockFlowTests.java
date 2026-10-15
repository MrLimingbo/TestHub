package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
class SmartExampleMockFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM mock_server WHERE name = '智能 Mock 验收服务'").update();
        jdbc.sql("DELETE FROM api_definition WHERE name = '智能 Mock 验收-查询订单'").update();
        jdbc.sql("DELETE FROM api_catalog WHERE name = '智能 Mock 验收目录'").update();
    }

    @Test
    void servesPublishedApiExampleAfterManualRulesAndSupportsPathParameters() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);

        MvcResult catalog = mvc.perform(post("/api/v1/projects/{projectId}/api-design/catalogs", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"name\":\"智能 Mock 验收目录\",\"sortOrder\":1}"))
                .andExpect(status().isOk()).andReturn();
        String catalogId = json(catalog).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult definition = mvc.perform(post("/api/v1/projects/{projectId}/api-design/definitions", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"catalogId":"%s","method":"GET","path":"/smart-orders/{orderId}","name":"智能 Mock 验收-查询订单","versionName":"1.0.0","summary":"智能示例","description":"已发布接口用于 Mock","headers":[],"query":[],"pathParameters":[{"name":"orderId","type":"string","required":true,"example":"OD-1","description":"订单标识"}],"requestBody":"","responseBody":"{\\"source\\":\\"api-example\\",\\"state\\":\\"published\\"}","exampleResponse":{"statusCode":207}}
                                """.formatted(catalogId)))
                .andExpect(status().isOk()).andReturn();
        String apiId = json(definition).at("/data/id").asText();

        MvcResult versions = mvc.perform(get("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/versions", projectId, apiId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        String versionId = json(versions).at("/data/0/id").asText();
        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-design/definitions/{apiId}/versions/{versionId}/publish", projectId, apiId, versionId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        csrf = csrf(administrator);
        MvcResult server = mvc.perform(post("/api/v1/projects/{projectId}/mock/servers", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content("{\"name\":\"智能 Mock 验收服务\"}"))
                .andExpect(status().isOk()).andReturn();
        String serverId = json(server).at("/data/id").asText();
        String publicKey = json(server).at("/data/publicKey").asText();

        MvcResult candidates = mvc.perform(get("/api/v1/projects/{projectId}/mock/servers/{serverId}/smart-examples", projectId, serverId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        JsonNode candidate = null;
        for (JsonNode item : json(candidates).path("data")) {
            if (apiId.equals(item.path("apiId").asText())) {
                candidate = item;
                break;
            }
        }
        assertNotNull(candidate);
        assertEquals("/smart-orders/{orderId}", candidate.path("path").asText());
        assertEquals(207, candidate.path("responseStatus").asInt());

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/mock/servers/{serverId}/smart-example/enabled", projectId, serverId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.smartExampleEnabled").value(true));

        mvc.perform(get("/api/v1/mock/{publicKey}/smart-orders/OD-9", publicKey))
                .andExpect(status().is(207)).andExpect(header().string("X-TestHub-Mock-Source", "api-example"))
                .andExpect(header().string("X-TestHub-Api-Version", "1.0.0"))
                .andExpect(content().json("{\"source\":\"api-example\",\"state\":\"published\"}"));

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/mock/servers/{serverId}/rules", projectId, serverId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"手动规则优先","httpMethod":"GET","path":"/smart-orders/OD-9","priority":0,"responseStatus":200,"responseBody":"{\\"source\\":\\"manual\\"}"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/mock/{publicKey}/smart-orders/OD-9", publicKey))
                .andExpect(status().isOk()).andExpect(content().json("{\"source\":\"manual\"}"));

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/mock/servers/{serverId}/smart-example/enabled", projectId, serverId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.smartExampleEnabled").value(false));
        mvc.perform(get("/api/v1/mock/{publicKey}/smart-orders/OD-10", publicKey))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MOCK_RULE_NOT_FOUND"));
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
