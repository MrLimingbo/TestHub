package io.testhub.platform.functional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FunctionalGovernanceService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> ASSET_TYPES = Set.of("REQUIREMENT", "CASE", "PLAN");
    private static final Set<String> FIELD_TYPES = Set.of("TEXT", "NUMBER", "DATE", "SELECT");
    private final FunctionalGovernanceRepository governance;
    private final FunctionalRequirementRepository requirements;
    private final FunctionalCaseRepository cases;
    private final FunctionalTestExecutionRepository executions;
    private final AccessService access;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public FunctionalGovernanceService(FunctionalGovernanceRepository governance, FunctionalRequirementRepository requirements,
                                       FunctionalCaseRepository cases, FunctionalTestExecutionRepository executions,
                                       AccessService access, AuditService audit, ObjectMapper objectMapper) {
        this.governance = governance;
        this.requirements = requirements;
        this.cases = cases;
        this.executions = executions;
        this.access = access;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public List<FunctionalTag> listTags(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return governance.findTags(projectId);
    }

    @Transactional
    public FunctionalTag createTag(AuthenticatedPrincipal principal, String projectId, TagInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签参数不能为空");
        String id = UlidCreator.getUlid().toString();
        String name = required(input.name(), 64, "标签名称");
        String color = color(input.color());
        try {
            governance.insertTag(id, project.organizationId(), projectId, name, color, principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一项目内标签名称已存在", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_TAG_CREATED", "FUNCTIONAL_TAG", id,
                Map.of("name", name));
        return governance.findTag(projectId, id).orElseThrow();
    }

    @Transactional
    public void deleteTag(AuthenticatedPrincipal principal, String projectId, String tagId) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (governance.deleteTag(projectId, tagId) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "标签不存在");
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_TAG_DELETED", "FUNCTIONAL_TAG", tagId, Map.of());
    }

    public List<FunctionalTag> listAssetTags(AuthenticatedPrincipal principal, String projectId, String assetType, String assetId) {
        access.requireProjectRead(principal, projectId);
        requireAsset(projectId, assetType, assetId);
        return governance.findAssetTags(projectId, normalizeAssetType(assetType), assetId);
    }

    @Transactional
    public List<FunctionalTag> replaceAssetTags(AuthenticatedPrincipal principal, String projectId, String assetType,
                                                 String assetId, List<String> tagIds) {
        ProjectSummary project = writeAccess(principal, projectId);
        String normalizedAssetType = normalizeAssetType(assetType);
        requireAsset(projectId, normalizedAssetType, assetId);
        List<String> normalizedTagIds = tagIds == null ? List.of() : tagIds.stream()
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        for (String tagId : normalizedTagIds) {
            governance.findTag(projectId, tagId).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签不存在或不属于当前项目"));
        }
        governance.clearAssetTags(projectId, normalizedAssetType, assetId);
        for (String tagId : normalizedTagIds) {
            governance.insertAssetTag(UlidCreator.getUlid().toString(), projectId, normalizedAssetType, assetId, tagId);
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_ASSET_TAGS_UPDATED", "FUNCTIONAL_" + normalizedAssetType,
                assetId, Map.of("tagCount", normalizedTagIds.size()));
        return governance.findAssetTags(projectId, normalizedAssetType, assetId);
    }

    public List<FunctionalCustomField> listFields(AuthenticatedPrincipal principal, String projectId, String assetType) {
        access.requireProjectRead(principal, projectId);
        return governance.findFields(projectId, assetType == null || assetType.isBlank() ? null : normalizeAssetType(assetType));
    }

    @Transactional
    public FunctionalCustomField createField(AuthenticatedPrincipal principal, String projectId, CustomFieldInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        ValidatedField field = validateField(input);
        String id = UlidCreator.getUlid().toString();
        try {
            governance.insertField(id, project.organizationId(), projectId, field.assetType(), field.name(), field.fieldKey(),
                    field.fieldType(), field.required(), optionsJson(field.options()), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一资产类型内字段标识已存在", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_CUSTOM_FIELD_CREATED", "FUNCTIONAL_CUSTOM_FIELD", id,
                Map.of("assetType", field.assetType(), "fieldKey", field.fieldKey()));
        return governance.findField(projectId, id).orElseThrow();
    }

    @Transactional
    public FunctionalCustomField updateField(AuthenticatedPrincipal principal, String projectId, String fieldId, CustomFieldInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        FunctionalCustomField existing = governance.findField(projectId, fieldId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "自定义字段不存在"));
        ValidatedField field = validateField(input);
        if (input.assetType() != null && !input.assetType().isBlank() && !existing.assetType().equals(field.assetType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已创建字段不能变更资产类型");
        }
        if (input.fieldKey() != null && !input.fieldKey().isBlank() && !existing.fieldKey().equals(field.fieldKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已创建字段不能变更字段标识");
        }
        governance.updateField(projectId, fieldId, field.name(), field.fieldType(), field.required(), optionsJson(field.options()), principal.userId());
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_CUSTOM_FIELD_UPDATED", "FUNCTIONAL_CUSTOM_FIELD", fieldId,
                Map.of("fieldKey", existing.fieldKey()));
        return governance.findField(projectId, fieldId).orElseThrow();
    }

    @Transactional
    public void deleteField(AuthenticatedPrincipal principal, String projectId, String fieldId) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (governance.deleteField(projectId, fieldId) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "自定义字段不存在");
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_CUSTOM_FIELD_DELETED", "FUNCTIONAL_CUSTOM_FIELD", fieldId, Map.of());
    }

    private ProjectSummary writeAccess(AuthenticatedPrincipal principal, String projectId) {
        return access.requireProjectParameterWrite(principal, projectId, WRITERS);
    }

    private void requireAsset(String projectId, String assetType, String assetId) {
        String normalized = normalizeAssetType(assetType);
        if (assetId == null || assetId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "资产标识不能为空");
        boolean exists = switch (normalized) {
            case "REQUIREMENT" -> requirements.findById(projectId, assetId).isPresent();
            case "CASE" -> cases.findById(projectId, assetId).isPresent();
            case "PLAN" -> executions.findPlan(projectId, assetId).isPresent();
            default -> false;
        };
        if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资产不存在或不属于当前项目");
    }

    private ValidatedField validateField(CustomFieldInput input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "自定义字段参数不能为空");
        String assetType = normalizeAssetType(input.assetType());
        String fieldKey = normalizeFieldKey(input.fieldKey());
        String fieldType = normalizeFieldType(input.fieldType());
        String name = required(input.name(), 64, "字段名称");
        List<String> options = input.options() == null ? List.of() : input.options().stream().map(item -> required(item, 80, "选项值")).distinct().toList();
        if (fieldType.equals("SELECT") && options.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "下拉选择字段至少需要一个选项");
        if (!fieldType.equals("SELECT") && !options.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只有下拉选择字段可以配置选项");
        return new ValidatedField(assetType, name, fieldKey, fieldType, input.required(), options);
    }

    private String normalizeAssetType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!ASSET_TYPES.contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "资产类型必须为需求、用例或测试计划");
        return normalized;
    }

    private String normalizeFieldType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!FIELD_TYPES.contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字段类型不合法");
        return normalized;
    }

    private String normalizeFieldKey(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z][a-z0-9_]{1,63}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字段标识需以小写字母开头，仅包含小写字母、数字或下划线");
        return normalized;
    }

    private String color(String value) {
        String normalized = value == null || value.isBlank() ? "arcoblue" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z]{3,16}|#[0-9a-f]{6}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签颜色不合法");
        return normalized;
    }

    private String required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空且不能超过" + max + "个字符");
        }
        return value.trim();
    }

    private String optionsJson(List<String> options) {
        try {
            return objectMapper.writeValueAsString(options);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字段选项格式不合法", exception);
        }
    }

    public record TagInput(String name, String color) { }
    public record CustomFieldInput(String assetType, String name, String fieldKey, String fieldType, boolean required,
                                   List<String> options) { }
    private record ValidatedField(String assetType, String name, String fieldKey, String fieldType, boolean required,
                                  List<String> options) { }
}
