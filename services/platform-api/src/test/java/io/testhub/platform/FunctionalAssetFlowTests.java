package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class FunctionalAssetFlowTests {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void cleanAcceptanceAssets() {
        jdbc.sql("DELETE FROM functional_case WHERE case_code LIKE 'ACCEPT_%'").update();
        jdbc.sql("DELETE FROM functional_requirement WHERE requirement_code LIKE 'ACCEPT_%'").update();
        jdbc.sql("DELETE FROM functional_catalog WHERE name LIKE '验收目录%'").update();
    }

    @Test
    void projectMemberCanMaintainCatalogRequirementAndCase() throws Exception {
        LoginSession administrator = login("admin@testhub.local");
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);

        MvcResult catalog = mvc.perform(post("/api/v1/projects/{projectId}/functional/catalogs", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"name\":\"验收目录-订单\",\"sortOrder\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("验收目录-订单")).andReturn();
        String catalogId = json(catalog).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult requirement = mvc.perform(post("/api/v1/projects/{projectId}/functional/requirements", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("{\"catalogId\":\"" + catalogId + "\",\"requirementCode\":\"ACCEPT_REQ\",\"title\":\"提交订单\",\"priority\":\"P1\",\"status\":\"DRAFT\",\"description\":\"订单提交后生成交易记录\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.requirementCode").value("ACCEPT_REQ")).andReturn();
        String requirementId = json(requirement).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult testCase = mvc.perform(post("/api/v1/projects/{projectId}/functional/cases", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("{\"catalogId\":\"" + catalogId + "\",\"requirementId\":\"" + requirementId + "\",\"caseCode\":\"ACCEPT_CASE\",\"title\":\"成功提交订单\",\"priority\":\"P1\",\"status\":\"READY\",\"precondition\":\"用户已登录\",\"steps\":\"1. 打开订单页\\n2. 点击提交\",\"expectedResult\":\"展示提交成功\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.caseCode").value("ACCEPT_CASE")).andReturn();
        String caseId = json(testCase).at("/data/id").asText();

        mvc.perform(get("/api/v1/projects/{projectId}/functional/requirements", projectId)
                        .param("catalogId", catalogId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(requirementId));
        mvc.perform(get("/api/v1/projects/{projectId}/functional/cases", projectId)
                        .param("catalogId", catalogId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(caseId));

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/functional/cases/{caseId}", projectId, caseId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("{\"catalogId\":\"" + catalogId + "\",\"requirementId\":\"" + requirementId + "\",\"caseCode\":\"ACCEPT_CASE\",\"title\":\"提交订单-更新\",\"priority\":\"P0\",\"status\":\"READY\",\"precondition\":\"用户已登录\",\"steps\":\"点击提交\",\"expectedResult\":\"展示提交成功\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("提交订单-更新"));

        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/functional/cases/{caseId}", projectId, caseId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());
        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/functional/requirements/{requirementId}", projectId, requirementId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());
        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/functional/catalogs/{catalogId}", projectId, catalogId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());
    }

    private LoginSession login(String email) throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode data = json(csrf).path("data");
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(data.path("headerName").asText(), data.path("token").asText())
                        .content("{\"email\":\"" + email + "\",\"password\":\"TestHub2026!\"}"))
                .andExpect(status().isOk()).andReturn();
        return new LoginSession(login.getResponse().getCookie("TESTHUB_SESSION"));
    }

    private Csrf csrf(LoginSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").cookie(session.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = json(result).path("data");
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"), data.path("headerName").asText(), data.path("token").asText());
    }

    private String projectId(LoginSession session) throws Exception {
        MvcResult context = mvc.perform(get("/api/v1/access/context").cookie(session.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        String organizationId = json(context).at("/data/organizations/0/id").asText();
        return json(context).at("/data/projects/" + organizationId + "/0/id").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record LoginSession(Cookie sessionCookie) { }
    private record Csrf(Cookie cookie, String headerName, String token) { }
}
