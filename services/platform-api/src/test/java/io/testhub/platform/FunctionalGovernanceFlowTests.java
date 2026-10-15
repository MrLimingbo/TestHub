package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasItem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
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
class FunctionalGovernanceFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void cleanGovernanceAssets() {
        jdbc.sql("DELETE FROM functional_asset_tag WHERE asset_id IN (SELECT id FROM functional_case WHERE case_code LIKE 'GOV_%')").update();
        jdbc.sql("DELETE FROM functional_custom_field WHERE field_key LIKE 'gov_%'").update();
        jdbc.sql("DELETE FROM functional_tag WHERE name LIKE '治理验收-%'").update();
        jdbc.sql("DELETE FROM functional_case WHERE case_code LIKE 'GOV_%'").update();
        jdbc.sql("DELETE FROM functional_catalog WHERE name = '治理验收目录'").update();
    }

    @Test
    void projectGovernanceMaintainsTagsFieldsAndCaseBindings() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String organizationId = jdbc.sql("SELECT organization_id FROM project WHERE id = :id").param("id", projectId).query(String.class).single();
        String userId = jdbc.sql("SELECT id FROM user_account WHERE email = 'admin@testhub.local'").query(String.class).single();
        String catalogId = UlidCreator.getUlid().toString();
        String caseId = UlidCreator.getUlid().toString();
        jdbc.sql("INSERT INTO functional_catalog (id, organization_id, project_id, name, created_by) VALUES (:id, :org, :project, '治理验收目录', :user)")
                .param("id", catalogId).param("org", organizationId).param("project", projectId).param("user", userId).update();
        jdbc.sql("""
                INSERT INTO functional_case (id, organization_id, project_id, catalog_id, case_code, title, priority, status,
                                             precondition, steps, expected_result, created_by, updated_by)
                VALUES (:id, :org, :project, :catalog, 'GOV_CASE_001', '治理验收用例', 'P1', 'READY', '', '执行步骤', '预期结果', :user, :user)
                """).param("id", caseId).param("org", organizationId).param("project", projectId).param("catalog", catalogId).param("user", userId).update();

        Csrf csrf = csrf(administrator);
        MvcResult tag = mvc.perform(post("/api/v1/projects/{projectId}/functional/tags", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content("{\"name\":\"治理验收-核心\",\"color\":\"arcoblue\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("治理验收-核心")).andReturn();
        String tagId = json(tag).at("/data/id").asText();

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/functional/asset-tags/CASE/{caseId}", projectId, caseId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content("{\"tagIds\":[\"" + tagId + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(tagId));
        mvc.perform(get("/api/v1/projects/{projectId}/functional/asset-tags", projectId).param("assetType", "CASE").param("assetId", caseId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("治理验收-核心"));

        csrf = csrf(administrator);
        MvcResult field = mvc.perform(post("/api/v1/projects/{projectId}/functional/custom-fields", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content("{\"assetType\":\"CASE\",\"name\":\"业务域\",\"fieldKey\":\"gov_business_domain\",\"fieldType\":\"SELECT\",\"required\":true,\"options\":[\"交易\",\"会员\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fieldKey").value("gov_business_domain"))
                .andExpect(jsonPath("$.data.optionsJson").exists()).andReturn();
        String fieldId = json(field).at("/data/id").asText();
        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/functional/custom-fields/{fieldId}", projectId, fieldId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content("{\"assetType\":\"CASE\",\"name\":\"业务域（更新）\",\"fieldKey\":\"gov_business_domain\",\"fieldType\":\"SELECT\",\"required\":false,\"options\":[\"交易\",\"会员\",\"营销\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("业务域（更新）"));
        mvc.perform(get("/api/v1/projects/{projectId}/functional/custom-fields", projectId).param("assetType", "CASE")
                        .cookie(administrator.sessionCookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].fieldKey", hasItem("gov_business_domain")));
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
