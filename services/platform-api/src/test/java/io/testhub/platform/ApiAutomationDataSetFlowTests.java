package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ApiAutomationDataSetFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM api_automation_execution WHERE scenario_name LIKE '数据集验收 %'").update();
        jdbc.sql("DELETE FROM api_automation_data_set WHERE name LIKE '数据集验收 %'").update();
        jdbc.sql("DELETE FROM api_automation_scenario WHERE name LIKE '数据集验收 %'").update();
        jdbc.sql("DELETE FROM audit_log WHERE detail LIKE '%数据集验收%'").update();
        jdbc.sql("DELETE FROM file_object WHERE original_name LIKE 'data-set-acceptance%'").update();
    }

    @Test
    void createsProjectScopedJsonDataSetAndFreezesOneRedactedExecutionPerRow() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String dataValueOne = "dataset-private-order-a";
        String dataValueTwo = "dataset-private-order-b";
        String dataSetContent = objectMapper.writeValueAsString(java.util.List.of(
                Map.of("orderId", dataValueOne, "region", "east"),
                Map.of("orderId", dataValueTwo, "region", "west")));
        String dataSetId = createDataSet(administrator, projectId, "数据集验收 订单参数", "JSON", dataSetContent);
        String scenarioId = createScenario(administrator, projectId, "数据集验收 逐行执行");

        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/data-sets", projectId)
                .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(dataSetId))
                .andExpect(jsonPath("$.data[0].rowCount").value(2))
                .andExpect(jsonPath("$.data[0].columns.length()").value(2));
        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/data-sets/{dataSetId}", projectId, dataSetId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.previewRows[0].orderId").value(dataValueOne))
                .andExpect(jsonPath("$.data.previewRows[1].region").value("west"));

        Csrf csrf = csrf(administrator);
        MvcResult submitted = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/data-sets/{dataSetId}/scenarios/{scenarioId}/executions",
                        projectId, dataSetId, scenarioId).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeoutSeconds\":300}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.dataSetId").value(dataSetId))
                .andExpect(jsonPath("$.data.rowCount").value(2)).andExpect(jsonPath("$.data.executions.length()").value(2))
                .andExpect(jsonPath("$.data.executions[0].dataSetRowNumber").value(1))
                .andExpect(jsonPath("$.data.executions[1].dataSetRowNumber").value(2)).andReturn();
        String firstExecutionId = json(submitted).at("/data/executions/0/id").asText();

        MvcResult manifest = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/manifest",
                        projectId, firstExecutionId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        String renderedManifest = manifest.getResponse().getContentAsString();
        assertTrue(renderedManifest.contains("orderId"));
        assertTrue(renderedManifest.contains("DATA_SET"));
        assertFalse(renderedManifest.contains(dataValueOne));
        assertFalse(renderedManifest.contains(dataValueTwo));

        MvcResult runtimeSummary = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/runtime-snapshot",
                        projectId, firstExecutionId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        String renderedSummary = runtimeSummary.getResponse().getContentAsString();
        assertTrue(renderedSummary.contains("DATA_SET"));
        assertFalse(renderedSummary.contains(dataValueOne));
        assertFalse(renderedSummary.contains(dataValueTwo));

        String taskPayload = jdbc.sql("SELECT payload FROM api_runner_outbox WHERE execution_id = :executionId AND message_type = 'TASK'")
                .param("executionId", firstExecutionId).query(String.class).single();
        assertFalse(taskPayload.contains(dataValueOne));
        assertFalse(taskPayload.contains(dataValueTwo));
        String frozenRow = jdbc.sql("SELECT content FROM api_automation_execution_runtime_snapshot WHERE execution_id = :executionId")
                .param("executionId", firstExecutionId).query(String.class).single();
        assertTrue(frozenRow.contains(dataValueOne));
        String audit = jdbc.sql("SELECT detail FROM audit_log WHERE resource_id = :executionId AND action = 'API_AUTOMATION_EXECUTION_QUEUED'")
                .param("executionId", firstExecutionId).query(String.class).single();
        assertFalse(audit.contains(dataValueOne));
    }

    @Test
    void acceptsCsvWithQuotedFieldsAndRejectsMalformedRowsBeforePersisting() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String dataSetId = createDataSet(administrator, projectId, "数据集验收 CSV", "CSV",
                "orderId,region\n1001,\"east, premium\"\n1002,west");
        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/data-sets/{dataSetId}", projectId, dataSetId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.dataSet.rowCount").value(2))
                .andExpect(jsonPath("$.data.previewRows[0].region").value("east, premium"));

        Csrf csrf = csrf(administrator);
        String invalid = objectMapper.writeValueAsString(Map.of("name", "数据集验收 非法 CSV", "sourceFormat", "CSV",
                "content", "orderId,region\n1001"));
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/data-sets", projectId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
        Integer count = jdbc.sql("SELECT COUNT(*) FROM api_automation_data_set WHERE name = '数据集验收 非法 CSV'")
                .query(Integer.class).single();
        assertEquals(0, count);
    }

    @Test
    void importsUtf8CsvIntoMinioAndOnlyReturnsProjectScopedSourceFileMetadata() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        byte[] source = "orderId,region\nimport-order-1001,east\nimport-order-1002,west".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "data-set-acceptance.csv", "text/csv", source);
        Csrf csrf = csrf(administrator);

        MvcResult imported = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                        "/api/v1/projects/{projectId}/api-automation/data-sets/import", projectId).file(file)
                        .param("name", "数据集验收 文件导入").param("sourceFormat", "CSV")
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.dataSet.sourceFormat").value("CSV"))
                .andExpect(jsonPath("$.data.dataSet.rowCount").value(2))
                .andExpect(jsonPath("$.data.dataSet.sourceFile.originalName").value("data-set-acceptance.csv"))
                .andExpect(jsonPath("$.data.previewRows[0].orderId").value("import-order-1001")).andReturn();
        String dataSetId = json(imported).at("/data/dataSet/id").asText();
        String fileId = json(imported).at("/data/dataSet/sourceFile/id").asText();

        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/data-sets", projectId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(dataSetId))
                .andExpect(jsonPath("$.data[0].sourceFile.id").value(fileId))
                .andExpect(jsonPath("$.data[0].sourceFile.sizeBytes").value(source.length));
        mvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/download", projectId, fileId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(source));

        Csrf invalidCsrf = csrf(administrator);
        MockMultipartFile invalid = new MockMultipartFile("file", "data-set-acceptance.txt", "text/plain", source);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                        "/api/v1/projects/{projectId}/api-automation/data-sets/import", projectId).file(invalid)
                        .param("name", "数据集验收 错误扩展名").param("sourceFormat", "CSV")
                        .cookie(administrator.sessionCookie(), invalidCsrf.cookie()).header(invalidCsrf.headerName(), invalidCsrf.token()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
        Integer storedFiles = jdbc.sql("SELECT COUNT(*) FROM file_object WHERE original_name = 'data-set-acceptance.txt'")
                .query(Integer.class).single();
        assertEquals(0, storedFiles);
    }

    private String createDataSet(LoginSession session, String projectId, String name, String format, String content) throws Exception {
        Csrf csrf = csrf(session);
        String payload = objectMapper.writeValueAsString(Map.of("name", name, "sourceFormat", format, "content", content));
        MvcResult response = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/data-sets", projectId)
                        .cookie(session.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andReturn();
        return json(response).at("/data/dataSet/id").asText();
    }

    private String createScenario(LoginSession session, String projectId, String name) throws Exception {
        Csrf csrf = csrf(session);
        MvcResult response = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .cookie(session.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"%s","environmentCode":"test","failureStrategy":"STOP",
                                "variables":{"baseUrl":"https://api.example.test"},"steps":[
                                {"id":"request-order","type":"REQUEST","name":"读取订单","config":{"method":"GET","url":"{{baseUrl}}/orders/{{orderId}}"}}]}
                                """.formatted(name)))
                .andExpect(status().isOk()).andReturn();
        return json(response).at("/data/id").asText();
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
