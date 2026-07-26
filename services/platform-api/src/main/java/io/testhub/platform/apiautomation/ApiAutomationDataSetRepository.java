package io.testhub.platform.apiautomation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiAutomationDataSetRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ApiAutomationDataSetRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<ApiAutomationDataSet> findByProject(String projectId) {
        return jdbc.sql("""
                SELECT data_set.id, data_set.organization_id, data_set.project_id, data_set.name, data_set.source_format,
                       data_set.columns, data_set.row_count, data_set.checksum, data_set.created_by, data_set.created_at,
                       data_set.updated_at, source_file.id AS source_file_id, source_file.original_name AS source_file_name,
                       source_file.content_type AS source_file_content_type, source_file.size_bytes AS source_file_size_bytes
                FROM api_automation_data_set data_set
                LEFT JOIN file_object source_file ON source_file.id = data_set.source_file_id
                    AND source_file.project_id = data_set.project_id
                WHERE data_set.project_id = :projectId
                ORDER BY data_set.updated_at DESC, data_set.id DESC
                """).param("projectId", projectId).query((rs, row) -> dataSet(rs)).list();
    }

    public Optional<ApiAutomationDataSetContent> findContent(String projectId, String dataSetId) {
        return jdbc.sql("""
                SELECT data_set.id, data_set.organization_id, data_set.project_id, data_set.name, data_set.source_format,
                       data_set.columns, data_set.data_rows, data_set.row_count, data_set.checksum, data_set.created_by,
                       data_set.created_at, data_set.updated_at, source_file.id AS source_file_id,
                       source_file.original_name AS source_file_name, source_file.content_type AS source_file_content_type,
                       source_file.size_bytes AS source_file_size_bytes
                FROM api_automation_data_set data_set
                LEFT JOIN file_object source_file ON source_file.id = data_set.source_file_id
                    AND source_file.project_id = data_set.project_id
                WHERE data_set.project_id = :projectId AND data_set.id = :dataSetId
                """).param("projectId", projectId).param("dataSetId", dataSetId)
                .query((rs, row) -> new ApiAutomationDataSetContent(dataSet(rs), rows(rs.getString("data_rows")))).optional();
    }

    public int countByProject(String projectId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM api_automation_data_set WHERE project_id = :projectId")
                .param("projectId", projectId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    public void insert(String id, String organizationId, String projectId, String name, String sourceFormat,
                       String columns, String rows, int rowCount, String checksum, String sourceFileId, String createdBy) {
        jdbc.sql("""
                INSERT INTO api_automation_data_set
                    (id, organization_id, project_id, name, source_format, columns, data_rows, row_count, checksum, source_file_id,
                     created_by)
                VALUES (:id, :organizationId, :projectId, :name, :sourceFormat, CAST(:columns AS JSON), CAST(:rows AS JSON),
                        :rowCount, :checksum, :sourceFileId, :createdBy)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("name", name).param("sourceFormat", sourceFormat).param("columns", columns).param("rows", rows)
                .param("rowCount", rowCount).param("checksum", checksum).param("sourceFileId", sourceFileId)
                .param("createdBy", createdBy).update();
    }

    public int update(String projectId, String dataSetId, String name, String sourceFormat, String columns, String rows,
                      int rowCount, String checksum) {
        return jdbc.sql("""
                UPDATE api_automation_data_set
                SET name = :name, source_format = :sourceFormat, columns = CAST(:columns AS JSON), data_rows = CAST(:rows AS JSON),
                    row_count = :rowCount, checksum = :checksum, updated_at = CURRENT_TIMESTAMP(3)
                WHERE project_id = :projectId AND id = :dataSetId
                """).param("projectId", projectId).param("dataSetId", dataSetId).param("name", name)
                .param("sourceFormat", sourceFormat).param("columns", columns).param("rows", rows)
                .param("rowCount", rowCount).param("checksum", checksum).update();
    }

    public int delete(String projectId, String dataSetId) {
        return jdbc.sql("DELETE FROM api_automation_data_set WHERE project_id = :projectId AND id = :dataSetId")
                .param("projectId", projectId).param("dataSetId", dataSetId).update();
    }

    public String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("接口自动化数据集无法序列化", exception);
        }
    }

    private ApiAutomationDataSet dataSet(java.sql.ResultSet rs) throws java.sql.SQLException {
        String sourceFileId = rs.getString("source_file_id");
        ApiAutomationDataSetSourceFile sourceFile = sourceFileId == null ? null : new ApiAutomationDataSetSourceFile(sourceFileId,
                rs.getString("source_file_name"), rs.getString("source_file_content_type"), rs.getLong("source_file_size_bytes"));
        return new ApiAutomationDataSet(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("name"), rs.getString("source_format"), columns(rs.getString("columns")),
                rs.getInt("row_count"), rs.getString("checksum"), sourceFile, rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private List<String> columns(String source) {
        try {
            return List.copyOf(json.readValue(source, new TypeReference<List<String>>() { }));
        } catch (Exception exception) {
            throw new IllegalStateException("已保存的接口自动化数据集字段格式不合法", exception);
        }
    }

    private List<Map<String, String>> rows(String source) {
        try {
            List<Map<String, String>> parsed = json.readValue(source, new TypeReference<List<Map<String, String>>>() { });
            return parsed.stream().map(row -> Map.copyOf(new LinkedHashMap<>(row))).toList();
        } catch (Exception exception) {
            throw new IllegalStateException("已保存的接口自动化数据集行格式不合法", exception);
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
