package io.testhub.platform.apiautomation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.storage.FileObject;
import io.testhub.platform.storage.FileObjectService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiAutomationDataSetService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> FORMATS = Set.of("JSON", "CSV");
    private static final int MAX_DATA_SETS_PER_PROJECT = 100;
    private static final int MAX_ROWS = 200;
    private static final int MAX_COLUMNS = 50;
    private static final int MAX_CONTENT_LENGTH = 512 * 1024;
    private static final int MAX_VALUE_LENGTH = 10_000;
    private static final int PREVIEW_ROWS = 5;
    private final ApiAutomationDataSetRepository dataSets;
    private final ApiAutomationExecutionService executions;
    private final AccessService access;
    private final AuditService audit;
    private final ObjectMapper json;
    private final FileObjectService files;

    public ApiAutomationDataSetService(ApiAutomationDataSetRepository dataSets, ApiAutomationExecutionService executions,
                                       AccessService access, AuditService audit, ObjectMapper json, FileObjectService files) {
        this.dataSets = dataSets;
        this.executions = executions;
        this.access = access;
        this.audit = audit;
        this.json = json;
        this.files = files;
    }

    public List<ApiAutomationDataSet> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return dataSets.findByProject(projectId);
    }

    public ApiAutomationDataSetDetail detail(AuthenticatedPrincipal principal, String projectId, String dataSetId) {
        access.requireProjectRead(principal, projectId);
        return detail(projectId, dataSetId);
    }

    @Transactional
    public ApiAutomationDataSetDetail create(AuthenticatedPrincipal principal, String projectId, DataSetInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (dataSets.countByProject(projectId) >= MAX_DATA_SETS_PER_PROJECT) {
            throw conflict("每个项目最多创建 100 个接口自动化数据集");
        }
        ParsedDataSet parsed = parse(input);
        String id = UlidCreator.getUlid().toString();
        try {
            dataSets.insert(id, project.organizationId(), projectId, parsed.name(), parsed.format(), dataSets.json(parsed.columns()),
                    dataSets.json(parsed.rows()), parsed.rows().size(), checksum(parsed), null, principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前项目已存在同名接口自动化数据集");
        }
        ApiAutomationDataSetDetail result = detail(projectId, id);
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_DATA_SET_CREATED",
                "API_AUTOMATION_DATA_SET", id, auditDetails(result.dataSet()));
        return result;
    }

    /**
     * Imports a small, validated source file. The source bytes remain a project file in MinIO; only normalized rows are
     * used by the data-driven execution chain.
     */
    public ApiAutomationDataSetDetail importFile(AuthenticatedPrincipal principal, String projectId, DataSetInput input,
                                                  MultipartFile file) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (dataSets.countByProject(projectId) >= MAX_DATA_SETS_PER_PROJECT) {
            throw conflict("每个项目最多创建 100 个接口自动化数据集");
        }
        String format = format(input);
        validateImportedFile(file, format);
        ParsedDataSet parsed = parse(new DataSetInput(input == null ? null : input.name(), format, fileContent(file)));
        FileObject sourceFile = files.upload(principal, projectId, file);
        String id = UlidCreator.getUlid().toString();
        try {
            dataSets.insert(id, project.organizationId(), projectId, parsed.name(), parsed.format(), dataSets.json(parsed.columns()),
                    dataSets.json(parsed.rows()), parsed.rows().size(), checksum(parsed), sourceFile.id(), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            removeImportedFile(principal, projectId, sourceFile.id());
            throw conflict("当前项目已存在同名接口自动化数据集");
        } catch (RuntimeException exception) {
            removeImportedFile(principal, projectId, sourceFile.id());
            throw exception;
        }
        ApiAutomationDataSetDetail result = detail(projectId, id);
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_DATA_SET_IMPORTED",
                "API_AUTOMATION_DATA_SET", id, auditDetails(result.dataSet()));
        return result;
    }

    @Transactional
    public ApiAutomationDataSetDetail update(AuthenticatedPrincipal principal, String projectId, String dataSetId,
                                             DataSetInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        ParsedDataSet parsed = parse(input);
        try {
            int updated = dataSets.update(projectId, dataSetId, parsed.name(), parsed.format(), dataSets.json(parsed.columns()),
                    dataSets.json(parsed.rows()), parsed.rows().size(), checksum(parsed));
            if (updated == 0) throw notFound();
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前项目已存在同名接口自动化数据集");
        }
        ApiAutomationDataSetDetail result = detail(projectId, dataSetId);
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_DATA_SET_UPDATED",
                "API_AUTOMATION_DATA_SET", dataSetId, auditDetails(result.dataSet()));
        return result;
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, String projectId, String dataSetId) {
        ProjectSummary project = writeAccess(principal, projectId);
        ApiAutomationDataSetContent content = content(projectId, dataSetId);
        if (dataSets.delete(projectId, dataSetId) == 0) throw notFound();
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_DATA_SET_DELETED",
                "API_AUTOMATION_DATA_SET", dataSetId, auditDetails(content.dataSet()));
    }

    @Transactional
    public ApiAutomationDataSetExecutionBatch createExecutions(AuthenticatedPrincipal principal, String projectId,
                                                                 String dataSetId, String scenarioId, Integer timeoutSeconds) {
        writeAccess(principal, projectId);
        ApiAutomationDataSetContent content = content(projectId, dataSetId);
        ApiAutomationDataSetExecutionBatch batch = executions.createDataSetExecutions(principal, projectId, scenarioId,
                content, timeoutSeconds);
        ProjectSummary project = access.requireProjectRead(principal, projectId);
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_DATA_SET_EXECUTIONS_QUEUED",
                "API_AUTOMATION_DATA_SET", dataSetId, Map.of("scenarioId", scenarioId, "rowCount", batch.rowCount(),
                        "executionCount", batch.executions().size()));
        return batch;
    }

    private ApiAutomationDataSetDetail detail(String projectId, String dataSetId) {
        ApiAutomationDataSetContent content = content(projectId, dataSetId);
        return new ApiAutomationDataSetDetail(content.dataSet(), content.rows().stream().limit(PREVIEW_ROWS).toList());
    }

    private ApiAutomationDataSetContent content(String projectId, String dataSetId) {
        return dataSets.findContent(projectId, dataSetId).orElseThrow(this::notFound);
    }

    private ProjectSummary writeAccess(AuthenticatedPrincipal principal, String projectId) {
        return access.requireProjectParameterWrite(principal, projectId, WRITERS);
    }

    private ParsedDataSet parse(DataSetInput input) {
        if (input == null) throw rejected("数据集参数不能为空");
        String name = required(input.name(), "数据集名称", 160);
        String format = format(input);
        String content = input.content() == null ? "" : input.content().trim();
        if (content.isEmpty() || content.length() > MAX_CONTENT_LENGTH) throw rejected("数据集内容长度应在 1 到 524288 字符之间");
        return "JSON".equals(format) ? parseJson(name, content) : parseCsv(name, content);
    }

    private String format(DataSetInput input) {
        String format = input == null || input.sourceFormat() == null ? "" : input.sourceFormat().trim().toUpperCase(Locale.ROOT);
        if (!FORMATS.contains(format)) throw rejected("数据集格式仅支持 JSON 或 CSV");
        return format;
    }

    private void validateImportedFile(MultipartFile file, String format) {
        if (file == null || file.isEmpty()) throw rejected("请选择非空 CSV 或 JSON 文件");
        if (file.getSize() > MAX_CONTENT_LENGTH) throw rejected("数据集原始文件不能超过 524288 字节");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim().toLowerCase(Locale.ROOT);
        String expectedExtension = "JSON".equals(format) ? ".json" : ".csv";
        if (!name.endsWith(expectedExtension)) {
            throw rejected("来源格式为 " + format + " 时，只能上传 " + expectedExtension + " 文件");
        }
    }

    private String fileContent(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0 || bytes.length > MAX_CONTENT_LENGTH) {
                throw rejected("数据集原始文件不能超过 524288 字节");
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw rejected("数据集原始文件必须使用 UTF-8 编码");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "数据集原始文件读取失败", exception);
        }
    }

    private void removeImportedFile(AuthenticatedPrincipal principal, String projectId, String fileId) {
        try {
            files.delete(principal, projectId, fileId);
        } catch (RuntimeException ignored) {
            // The original data-set persistence error remains the actionable response. Orphan cleanup is best effort.
        }
    }

    private ParsedDataSet parseJson(String name, String content) {
        try {
            JsonNode root = json.readTree(content);
            if (root == null || !root.isArray() || root.isEmpty()) throw rejected("JSON 数据集必须是非空对象数组");
            if (root.size() > MAX_ROWS) throw rejected("数据集最多支持 200 行");
            LinkedHashSet<String> columns = new LinkedHashSet<>();
            List<Map<String, String>> rows = new ArrayList<>();
            for (int index = 0; index < root.size(); index++) {
                JsonNode item = root.get(index);
                if (!item.isObject() || item.isEmpty()) throw rejected("JSON 数据集第 " + (index + 1) + " 行必须是非空对象");
                LinkedHashMap<String, String> row = new LinkedHashMap<>();
                item.fields().forEachRemaining(entry -> {
                    String field = validateColumn(entry.getKey());
                    JsonNode value = entry.getValue();
                    if (!value.isValueNode()) throw rejected("JSON 数据集字段仅支持字符串、数值、布尔值或 null：" + field);
                    String text = value.isNull() ? "" : value.asText();
                    validateValue(text, field);
                    columns.add(field);
                    row.put(field, text);
                });
                rows.add(row);
            }
            return normalize(name, "JSON", columns, rows);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw rejected("JSON 数据集格式不合法");
        }
    }

    private ParsedDataSet parseCsv(String name, String content) {
        List<List<String>> records = csvRecords(content);
        if (records.size() < 2) throw rejected("CSV 数据集至少需要标题行和一行数据");
        List<String> header = records.getFirst();
        if (header.isEmpty() || header.size() > MAX_COLUMNS) throw rejected("CSV 数据集字段数量应在 1 到 50 之间");
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        for (int index = 0; index < header.size(); index++) {
            String field = validateColumn(index == 0 ? removeBom(header.get(index)) : header.get(index));
            if (!columns.add(field)) throw rejected("CSV 数据集字段名重复：" + field);
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (int index = 1; index < records.size(); index++) {
            List<String> values = records.get(index);
            if (values.stream().allMatch(String::isEmpty)) continue;
            if (values.size() != columns.size()) throw rejected("CSV 数据集第 " + (index + 1) + " 行字段数量与标题不一致");
            LinkedHashMap<String, String> row = new LinkedHashMap<>();
            int column = 0;
            for (String field : columns) {
                String value = values.get(column++);
                validateValue(value, field);
                row.put(field, value);
            }
            rows.add(row);
        }
        if (rows.isEmpty()) throw rejected("CSV 数据集至少需要一行非空数据");
        return normalize(name, "CSV", columns, rows);
    }

    private ParsedDataSet normalize(String name, String format, LinkedHashSet<String> columns,
                                    List<Map<String, String>> sourceRows) {
        if (columns.isEmpty() || columns.size() > MAX_COLUMNS) throw rejected("数据集字段数量应在 1 到 50 之间");
        if (sourceRows.isEmpty() || sourceRows.size() > MAX_ROWS) throw rejected("数据集行数应在 1 到 200 之间");
        List<Map<String, String>> rows = sourceRows.stream().map(source -> {
            LinkedHashMap<String, String> row = new LinkedHashMap<>();
            for (String field : columns) row.put(field, source.getOrDefault(field, ""));
            return Map.copyOf(row);
        }).toList();
        return new ParsedDataSet(name, format, List.copyOf(columns), rows);
    }

    private List<List<String>> csvRecords(String content) {
        List<List<String>> records = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < content.length() && content.charAt(index + 1) == '"') {
                        current.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(character);
                }
                continue;
            }
            if (character == '"') {
                if (current.length() != 0) throw rejected("CSV 双引号只能用于字段起始位置");
                quoted = true;
            } else if (character == ',') {
                currentRow.add(current.toString());
                current.setLength(0);
            } else if (character == '\n' || character == '\r') {
                if (character == '\r' && index + 1 < content.length() && content.charAt(index + 1) == '\n') index++;
                currentRow.add(current.toString());
                records.add(List.copyOf(currentRow));
                currentRow = new ArrayList<>();
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (quoted) throw rejected("CSV 存在未闭合的双引号");
        currentRow.add(current.toString());
        records.add(List.copyOf(currentRow));
        return records;
    }

    private String required(String value, String label, int max) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty() || result.length() > max) throw rejected(label + "长度应在 1 到 " + max + " 字符之间");
        return result;
    }

    private String validateColumn(String value) {
        String field = value == null ? "" : value.trim();
        if (!field.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw rejected("数据集字段名仅支持字母、数字和下划线，且不能以数字开头：" + field);
        }
        return field;
    }

    private void validateValue(String value, String field) {
        if (value == null || value.length() > MAX_VALUE_LENGTH) {
            throw rejected("数据集字段值不能超过 10000 字符：" + field);
        }
    }

    private String removeBom(String value) {
        return value != null && value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private String checksum(ParsedDataSet dataSet) {
        try {
            String source = dataSets.json(Map.of("format", dataSet.format(), "columns", dataSet.columns(), "rows", dataSet.rows()));
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    private Map<String, Object> auditDetails(ApiAutomationDataSet dataSet) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", dataSet.name());
        details.put("sourceFormat", dataSet.sourceFormat());
        details.put("columnCount", dataSet.columns().size());
        details.put("rowCount", dataSet.rowCount());
        details.put("checksum", dataSet.checksum());
        if (dataSet.sourceFile() != null) {
            details.put("sourceFileId", dataSet.sourceFile().id());
            details.put("sourceFileName", dataSet.sourceFile().originalName());
            details.put("sourceFileSizeBytes", dataSet.sourceFile().sizeBytes());
        }
        return Map.copyOf(details);
    }

    private ResponseStatusException rejected(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "接口自动化数据集不存在");
    }

    public record DataSetInput(String name, String sourceFormat, String content) {
    }

    private record ParsedDataSet(String name, String format, List<String> columns, List<Map<String, String>> rows) {
    }
}
