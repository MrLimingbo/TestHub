package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
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
class PlatformFoundationAcceptanceTests {
    private static final String PASSWORD = "TestHub2026!";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void unauthenticatedRequestReturnsStandardApiError() throws Exception {
        mvc.perform(get("/api/v1/access/context"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.details").isMap());
    }

    @Test
    void projectDataCannotCrossOrganizationBoundary() throws Exception {
        String organizationId = UlidCreator.getUlid().toString();
        String projectId = UlidCreator.getUlid().toString();
        jdbc.sql("INSERT INTO organization (id, name, code) VALUES (:id, :name, :code)")
                .param("id", organizationId).param("name", "隔离验收组织").param("code", "isolation-" + organizationId.substring(0, 8))
                .update();
        jdbc.sql("INSERT INTO project (id, organization_id, name, code) VALUES (:id, :organizationId, :name, :code)")
                .param("id", projectId).param("organizationId", organizationId).param("name", "隔离验收项目")
                .param("code", "isolation-" + projectId.substring(0, 8)).update();
        try {
            LoginSession tester = login("tester@testhub.local");
            mvc.perform(get("/api/v1/projects/{projectId}/tasks", projectId).cookie(tester.sessionCookie()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        } finally {
            jdbc.sql("DELETE FROM project WHERE id = :id").param("id", projectId).update();
            jdbc.sql("DELETE FROM organization WHERE id = :id").param("id", organizationId).update();
        }
    }

    @Test
    void projectFileLifecycleStoresBytesInMinioAndAuditMetadataInMySql() throws Exception {
        LoginSession administrator = login("admin@testhub.local");
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        byte[] bytes = "foundation-file-acceptance".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "../foundation-acceptance.txt", MediaType.TEXT_PLAIN_VALUE, bytes);

        MvcResult uploaded = mvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(file).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalName").value("foundation-acceptance.txt"))
                .andExpect(jsonPath("$.data.sizeBytes").value(bytes.length))
                .andReturn();
        String fileId = json(uploaded).at("/data/id").asText();
        try {
            mvc.perform(get("/api/v1/projects/{projectId}/files", projectId).cookie(administrator.sessionCookie()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(fileId));
            mvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/download", projectId, fileId)
                            .cookie(administrator.sessionCookie()))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.TEXT_PLAIN))
                    .andExpect(content().bytes(bytes));
            mvc.perform(get("/api/v1/audit-logs").param("projectId", projectId).param("action", "FILE_UPLOADED")
                            .cookie(administrator.sessionCookie()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].resourceId").value(fileId));

            Csrf deleteCsrf = csrf(administrator);
            mvc.perform(delete("/api/v1/projects/{projectId}/files/{fileId}", projectId, fileId)
                            .cookie(administrator.sessionCookie(), deleteCsrf.cookie())
                            .header(deleteCsrf.headerName(), deleteCsrf.token()))
                    .andExpect(status().isNoContent());
        } finally {
            jdbc.sql("DELETE FROM file_object WHERE id = :id").param("id", fileId).update();
        }
    }

    private LoginSession login(String email) throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode data = json(csrf).path("data");
        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(data.path("headerName").asText(), data.path("token").asText())
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
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

    private record LoginSession(Cookie sessionCookie) {
    }

    private record Csrf(Cookie cookie, String headerName, String token) {
    }
}
