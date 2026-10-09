package io.testhub.platform.functional;

import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FunctionalAssetExchangeService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ROWS = 500;
    private final FunctionalCatalogRepository catalogs;
    private final FunctionalRequirementRepository requirements;
    private final FunctionalCaseRepository cases;
    private final FunctionalAssetService assets;
    private final AccessService access;
    private final AuditService audit;

    public FunctionalAssetExchangeService(FunctionalCatalogRepository catalogs, FunctionalRequirementRepository requirements,
                                          FunctionalCaseRepository cases, FunctionalAssetService assets, AccessService access,
                                          AuditService audit) {
        this.catalogs = catalogs;
        this.requirements = requirements;
        this.cases = cases;
        this.assets = assets;
        this.access = access;
        this.audit = audit;
    }

    public ExportWorkbook export(AuthenticatedPrincipal principal, String projectId, AssetKind type, boolean template) {
        access.requireProjectRead(principal, projectId);
        AssetKind safeType = requiredType(type);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(safeType == AssetKind.REQUIREMENTS ? "需求" : "测试用例");
            String[] headers = headers(safeType);
            Row header = sheet.createRow(0);
            for (int index = 0; index < headers.length; index++) header.createCell(index).setCellValue(headers[index]);
            if (!template) writeExportRows(sheet, projectId, safeType);
            for (int index = 0; index < headers.length; index++) sheet.setColumnWidth(index, Math.min(50 * 256, Math.max(14 * 256, headers[index].length() * 3 * 256)));
            workbook.write(output);
            String filename = "TestHub-" + (safeType == AssetKind.REQUIREMENTS ? "requirements" : "cases") + "-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + (template ? "-template" : "") + ".xlsx";
            return new ExportWorkbook(filename, output.toByteArray());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "生成 Excel 文件失败", exception);
        }
    }

    @Transactional
    public ImportResult importWorkbook(AuthenticatedPrincipal principal, String projectId, AssetKind type, MultipartFile file) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        AssetKind safeType = requiredType(type);
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要导入的 Excel 文件");
        if (file.getSize() > MAX_FILE_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Excel 文件不能超过 5 MB");
        List<ImportRow> rows;
        try {
            rows = parse(file, safeType);
        } catch (IOException | RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取 Excel 文件，请使用平台导出的 .xlsx 模板", exception);
        }
        if (rows.size() > MAX_ROWS) return new ImportResult(safeType.name(), false, 0, List.of(new ImportError(0, "单次最多导入 500 行")));
        List<ImportError> errors = validateRows(projectId, safeType, rows);
        if (!errors.isEmpty()) return new ImportResult(safeType.name(), false, 0, errors);
        for (ImportRow row : rows) {
            if (safeType == AssetKind.REQUIREMENTS) {
                assets.createRequirement(principal, projectId, new FunctionalAssetService.RequirementInput(row.catalogId(), row.code(), row.title(),
                        row.priority(), row.status(), row.description()));
            } else {
                assets.createCase(principal, projectId, new FunctionalAssetService.CaseInput(row.catalogId(), row.requirementId(), row.code(), row.title(),
                        row.priority(), row.status(), row.caseType(), row.precondition(), row.steps(), row.expectedResult()));
            }
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_ASSET_IMPORTED", "FUNCTIONAL_" + safeType.name(), projectId,
                Map.of("count", rows.size()));
        return new ImportResult(safeType.name(), true, rows.size(), List.of());
    }

    private void writeExportRows(Sheet sheet, String projectId, AssetKind type) {
        Map<String, String> catalogNames = new HashMap<>();
        catalogs.findByProject(projectId, type.name()).forEach(catalog -> catalogNames.put(catalog.id(), catalog.name()));
        if (type == AssetKind.REQUIREMENTS) {
            int rowNumber = 1;
            for (FunctionalRequirement requirement : requirements.findByProject(projectId, null)) {
                Row row = sheet.createRow(rowNumber++);
                values(row, catalogNames.getOrDefault(requirement.catalogId(), ""), requirement.requirementCode(), requirement.title(),
                        requirement.priority(), requirement.status(), requirement.description());
            }
        } else {
            Map<String, String> requirementCodes = new HashMap<>();
            requirements.findByProject(projectId, null).forEach(requirement -> requirementCodes.put(requirement.id(), requirement.requirementCode()));
            int rowNumber = 1;
            for (FunctionalCase testCase : cases.findByProject(projectId, null)) {
                Row row = sheet.createRow(rowNumber++);
                values(row, catalogNames.getOrDefault(testCase.catalogId(), ""), requirementCodes.getOrDefault(testCase.requirementId(), ""),
                        testCase.caseCode(), testCase.title(), testCase.priority(), testCase.status(), testCase.precondition(), testCase.steps(), testCase.expectedResult(), testCase.caseType());
            }
        }
    }

    private List<ImportRow> parse(MultipartFile file, AssetKind type) throws IOException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(file.getBytes()); Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) throw new IllegalArgumentException("工作簿没有工作表");
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.SIMPLIFIED_CHINESE);
            String[] expected = headers(type);
            verifyHeader(sheet.getRow(0), type == AssetKind.CASES ? java.util.Arrays.copyOf(expected, 9) : expected);
            String typeHeader = type == AssetKind.CASES ? cell(sheet.getRow(0), 9, formatter) : "";
            if (!typeHeader.isEmpty() && !typeHeader.equals("用例类型")) throw new IllegalArgumentException("用例类型列表头不正确");
            boolean hasCaseType = typeHeader.equals("用例类型");
            List<ImportRow> rows = new ArrayList<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || blank(row, formatter)) continue;
                List<String> values = new ArrayList<>();
                for (int column = 0; column < headers(type).length; column++) values.add(cell(row, column, formatter));
                rows.add(type == AssetKind.REQUIREMENTS
                        ? new ImportRow(index + 1, values.get(0), null, values.get(1), values.get(2), values.get(3), values.get(4), values.get(5), "", "", "", null)
                        : new ImportRow(index + 1, values.get(0), values.get(1), values.get(2), values.get(3), values.get(4), values.get(5), "", values.get(6), values.get(7), values.get(8), hasCaseType && !values.get(9).isBlank() ? values.get(9).toUpperCase(Locale.ROOT) : "FUNCTIONAL"));
            }
            return rows;
        }
    }

    private List<ImportError> validateRows(String projectId, AssetKind type, List<ImportRow> rows) {
        List<ImportError> errors = new ArrayList<>();
        if (rows.isEmpty()) return List.of(new ImportError(0, "Excel 中没有可导入的数据行"));
        Map<String, List<FunctionalCatalog>> catalogsByName = new HashMap<>();
        catalogs.findByProject(projectId, type.name()).forEach(catalog -> catalogsByName.computeIfAbsent(catalog.name(), ignored -> new ArrayList<>()).add(catalog));
        Set<String> existingCodes = new HashSet<>();
        if (type == AssetKind.REQUIREMENTS) requirements.findByProject(projectId, null).forEach(item -> existingCodes.add(item.requirementCode()));
        else cases.findByProject(projectId, null).forEach(item -> existingCodes.add(item.caseCode()));
        Map<String, String> requirementIds = new HashMap<>();
        requirements.findByProject(projectId, null).forEach(item -> requirementIds.put(item.requirementCode(), item.id()));
        Set<String> codesInFile = new HashSet<>();
        for (ImportRow row : rows) {
            List<FunctionalCatalog> matchingCatalogs = catalogsByName.getOrDefault(row.catalogName(), List.of());
            if (matchingCatalogs.size() != 1) errors.add(new ImportError(row.rowNumber(), matchingCatalogs.isEmpty() ? "目录不存在，请先创建目录" : "目录名称不唯一，请使用唯一目录名称"));
            else row.catalogId(matchingCatalogs.getFirst().id());
            String code = row.code().toUpperCase(Locale.ROOT);
            if (!code.matches("[A-Z][A-Z0-9_-]{1,39}")) errors.add(new ImportError(row.rowNumber(), "编号需以字母开头，仅包含字母、数字、下划线或连字符"));
            if (!codesInFile.add(code)) errors.add(new ImportError(row.rowNumber(), "文件中存在重复编号：" + code));
            if (existingCodes.contains(code)) errors.add(new ImportError(row.rowNumber(), "编号已存在：" + code));
            row.code(code);
            if (row.title().isBlank() || row.title().length() > 200) errors.add(new ImportError(row.rowNumber(), "标题不能为空且不能超过 200 个字符"));
            if (!Set.of("P0", "P1", "P2", "P3").contains(row.priority().toUpperCase(Locale.ROOT))) errors.add(new ImportError(row.rowNumber(), "优先级必须为 P0、P1、P2 或 P3"));
            if (type == AssetKind.REQUIREMENTS) {
                if (!Set.of("DRAFT", "REVIEWING", "APPROVED", "ARCHIVED").contains(row.status().toUpperCase(Locale.ROOT))) errors.add(new ImportError(row.rowNumber(), "需求状态不合法"));
            } else {
                if (!FunctionalAssetService.CASE_TYPES.contains(row.caseType())) errors.add(new ImportError(row.rowNumber(), "用例类型不合法"));
                if (!Set.of("DRAFT", "READY", "BLOCKED", "ARCHIVED").contains(row.status().toUpperCase(Locale.ROOT))) errors.add(new ImportError(row.rowNumber(), "用例状态不合法"));
                if (row.steps().isBlank() || row.expectedResult().isBlank()) errors.add(new ImportError(row.rowNumber(), "测试步骤和预期结果不能为空"));
                if (!row.requirementCode().isBlank()) {
                    String requirementId = requirementIds.get(row.requirementCode().toUpperCase(Locale.ROOT));
                    if (requirementId == null) errors.add(new ImportError(row.rowNumber(), "关联需求编号不存在：" + row.requirementCode()));
                    else row.requirementId(requirementId);
                }
            }
            row.priority(row.priority().toUpperCase(Locale.ROOT));
            row.status(row.status().toUpperCase(Locale.ROOT));
        }
        return errors;
    }

    private void verifyHeader(Row row, String[] expected) {
        if (row == null) throw new IllegalArgumentException("Excel 缺少表头");
        DataFormatter formatter = new DataFormatter(Locale.SIMPLIFIED_CHINESE);
        for (int index = 0; index < expected.length; index++) {
            if (!expected[index].equals(cell(row, index, formatter))) throw new IllegalArgumentException("Excel 表头与模板不匹配");
        }
    }

    private String[] headers(AssetKind type) {
        return type == AssetKind.REQUIREMENTS
                ? new String[] { "目录名称", "需求编号", "需求标题", "优先级", "状态", "需求说明" }
                : new String[] { "目录名称", "关联需求编号", "用例编号", "用例标题", "优先级", "状态", "前置条件", "测试步骤", "预期结果", "用例类型" };
    }

    private void values(Row row, String... values) { for (int index = 0; index < values.length; index++) row.createCell(index).setCellValue(values[index] == null ? "" : values[index]); }
    private String cell(Row row, int index, DataFormatter formatter) { Cell cell = row.getCell(index); return cell == null ? "" : formatter.formatCellValue(cell).trim(); }
    private boolean blank(Row row, DataFormatter formatter) { for (int index = 0; index < row.getLastCellNum(); index++) if (!cell(row, index, formatter).isBlank()) return false; return true; }
    private AssetKind requiredType(AssetKind type) { if (type == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入导出类型不能为空"); return type; }

    public enum AssetKind { REQUIREMENTS, CASES }
    public record ExportWorkbook(String filename, byte[] bytes) { }
    public record ImportResult(String type, boolean completed, int importedCount, List<ImportError> errors) { }
    public record ImportError(int rowNumber, String message) { }
    private static final class ImportRow {
        private final int rowNumber; private final String catalogName; private final String requirementCode; private String code; private final String title;
        private String priority; private String status; private final String description; private final String precondition; private final String steps; private final String expectedResult;
        private String catalogId; private String requirementId; private final String caseType;
        private ImportRow(int rowNumber, String catalogName, String requirementCode, String code, String title, String priority, String status,
                          String description, String precondition, String steps, String expectedResult, String caseType) { this.caseType = caseType; this.rowNumber = rowNumber; this.catalogName = catalogName; this.requirementCode = requirementCode; this.code = code; this.title = title; this.priority = priority; this.status = status; this.description = description; this.precondition = precondition; this.steps = steps; this.expectedResult = expectedResult; }
        String caseType() { return caseType; }
        int rowNumber() { return rowNumber; } String catalogName() { return catalogName; } String requirementCode() { return requirementCode; } String code() { return code; } String title() { return title; } String priority() { return priority; } String status() { return status; } String description() { return description; } String precondition() { return precondition; } String steps() { return steps; } String expectedResult() { return expectedResult; } String catalogId() { return catalogId; } String requirementId() { return requirementId; }
        void code(String value) { code = value; } void priority(String value) { priority = value; } void status(String value) { status = value; } void catalogId(String value) { catalogId = value; } void requirementId(String value) { requirementId = value; }
    }
}
