package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class FunctionalDeliveryFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void cleanDeliveryAssets() {
        jdbc.sql("DELETE FROM functional_defect WHERE defect_code LIKE 'FLOW_%' OR title LIKE 'FLOW_%'").update();
        jdbc.sql("DELETE FROM functional_case WHERE case_code LIKE 'FLOW_%'").update();
        jdbc.sql("DELETE FROM functional_requirement WHERE requirement_code LIKE 'FLOW_%'").update();
        jdbc.sql("DELETE FROM functional_catalog WHERE name IN ('交付验收目录', '缺陷验收目录')").update();
    }

    @Test
    void defectLinkAndExcelExchangeFormAuditableDeliveryFlow() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String organizationId = jdbc.sql("SELECT organization_id FROM project WHERE id = :id").param("id", projectId).query(String.class).single();
        String userId = jdbc.sql("SELECT id FROM user_account WHERE email = 'admin@testhub.local'").query(String.class).single();
        String catalogId = UlidCreator.getUlid().toString();
        String defectCatalogId = UlidCreator.getUlid().toString();
        String caseId = UlidCreator.getUlid().toString();
        jdbc.sql("INSERT INTO functional_catalog (id, organization_id, project_id, name, created_by) VALUES (:id, :org, :project, '交付验收目录', :user)")
                .param("id", catalogId).param("org", organizationId).param("project", projectId).param("user", userId).update();
        jdbc.sql("""
                INSERT INTO functional_catalog
                    (id, organization_id, project_id, scope, name, created_by)
                VALUES (:id, :org, :project, 'DELIVERY', '缺陷验收目录', :user)
                """).param("id", defectCatalogId).param("org", organizationId).param("project", projectId)
                .param("user", userId).update();
        jdbc.sql("""
                INSERT INTO functional_case (id, organization_id, project_id, catalog_id, case_code, title, priority, status,
                                             precondition, steps, expected_result, created_by, updated_by)
                VALUES (:id, :org, :project, :catalog, 'FLOW_CASE_001', '交付关联用例', 'P1', 'READY', '', '执行步骤', '预期结果', :user, :user)
                """).param("id", caseId).param("org", organizationId).param("project", projectId).param("catalog", catalogId).param("user", userId).update();

        Csrf csrf = csrf(administrator);
        MvcResult defect = mvc.perform(post("/api/v1/projects/{projectId}/functional/defects", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content("{\"catalogId\":\"" + defectCatalogId + "\",\"title\":\"FLOW_订单提交失败\","
                                + "\"dueDate\":\"2026-08-31\",\"bugType\":\"SECURITY\",\"keywords\":\"订单 支付\","
                                + "\"browsers\":[\"CHROME\",\"EDGE\"],\"operatingSystems\":[\"WINDOWS\",\"LINUX\"],"
                                + "\"ccUserIds\":[\"" + userId + "\"],\"severity\":\"MAJOR\",\"priority\":\"P0\","
                                + "\"status\":\"OPEN\",\"confirmed\":true,\"assigneeId\":\"" + userId + "\","
                                + "\"source\":\"TESTHUB\",\"description\":\"<p><strong>[步骤]</strong></p><p>复现后无法生成订单</p>\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.displayId").isNumber())
                .andExpect(jsonPath("$.data.defectCode").isNotEmpty())
                .andExpect(jsonPath("$.data.catalogId").value(defectCatalogId))
                .andExpect(jsonPath("$.data.dueDate").value("2026-08-31"))
                .andExpect(jsonPath("$.data.bugType").value("SECURITY"))
                .andExpect(jsonPath("$.data.keywords").value("订单 支付"))
                .andExpect(jsonPath("$.data.browsers[0]").value("CHROME"))
                .andExpect(jsonPath("$.data.browsers[1]").value("EDGE"))
                .andExpect(jsonPath("$.data.operatingSystems[0]").value("WINDOWS"))
                .andExpect(jsonPath("$.data.ccUserIds[0]").value(userId))
                .andExpect(jsonPath("$.data.priority").value("P0"))
                .andExpect(jsonPath("$.data.confirmed").value(true))
                .andExpect(jsonPath("$.data.assigneeId").value(userId))
                .andExpect(jsonPath("$.data.assignedBy").value(userId))
                .andExpect(jsonPath("$.data.createdByName").isNotEmpty()).andReturn();
        String defectId = json(defect).at("/data/id").asText();
        String defectCode = json(defect).at("/data/defectCode").asText();
        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/functional/defects/{defectId}/links", projectId, defectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content("{\"assetType\":\"CASE\",\"assetId\":\"" + caseId + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.assetType").value("CASE"));
        mvc.perform(get("/api/v1/projects/{projectId}/functional/defects/{defectId}/links", projectId, defectId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].assetLabel").value("交付关联用例"));

        csrf = csrf(administrator);
        mvc.perform(put("/api/v1/projects/{projectId}/functional/defects/{defectId}", projectId, defectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .content("{\"catalogId\":\"" + defectCatalogId + "\",\"defectCode\":\"" + defectCode + "\",\"title\":\"FLOW_订单提交失败\",\"severity\":\"MAJOR\",\"priority\":\"P0\",\"status\":\"RESOLVED\",\"confirmed\":true,\"assigneeId\":\"" + userId + "\",\"resolution\":\"FIXED\",\"source\":\"TESTHUB\",\"description\":\"复现后无法生成订单\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolution").value("FIXED"))
                .andExpect(jsonPath("$.data.resolvedBy").value(userId))
                .andExpect(jsonPath("$.data.resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.dueDate").value("2026-08-31"))
                .andExpect(jsonPath("$.data.bugType").value("SECURITY"))
                .andExpect(jsonPath("$.data.browsers[0]").value("CHROME"))
                .andExpect(jsonPath("$.data.ccUserIds[0]").value(userId));

        csrf = csrf(administrator);
        mvc.perform(multipart("/api/v1/projects/{projectId}/functional/assets/import", projectId)
                        .file(requirementWorkbook()).param("type", "REQUIREMENTS").cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.completed").value(true)).andExpect(jsonPath("$.data.importedCount").value(1));
        mvc.perform(get("/api/v1/projects/{projectId}/functional/assets/export", projectId).param("type", "REQUIREMENTS")
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

        csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/functional/defects/{defectId}", projectId, defectId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());
    }

    private MockMultipartFile requirementWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("需求");
            var header = sheet.createRow(0);
            String[] headers = { "目录名称", "需求编号", "需求标题", "优先级", "状态", "需求说明" };
            for (int index = 0; index < headers.length; index++) header.createCell(index).setCellValue(headers[index]);
            var row = sheet.createRow(1);
            String[] values = { "交付验收目录", "FLOW_REQ_001", "Excel 导入需求", "P1", "DRAFT", "验证标准工作簿导入" };
            for (int index = 0; index < values.length; index++) row.createCell(index).setCellValue(values[index]);
            workbook.write(output);
            return new MockMultipartFile("file", "requirements.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
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
