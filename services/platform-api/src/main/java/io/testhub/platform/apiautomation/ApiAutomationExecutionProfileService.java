package io.testhub.platform.apiautomation;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.util.LinkedHashMap;
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
public class ApiAutomationExecutionProfileService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final ApiAutomationExecutionProfileRepository profiles;
    private final AccessService access;
    private final AuditService audit;

    public ApiAutomationExecutionProfileService(ApiAutomationExecutionProfileRepository profiles, AccessService access,
                                                AuditService audit) {
        this.profiles = profiles;
        this.access = access;
        this.audit = audit;
    }

    public List<ApiAutomationExecutionProfile> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return profiles.findByProject(projectId);
    }

    public ApiAutomationExecutionProfileDetail detail(AuthenticatedPrincipal principal, String projectId, String profileId) {
        access.requireProjectRead(principal, projectId);
        return require(projectId, profileId);
    }

    @Transactional
    public ApiAutomationExecutionProfileDetail create(AuthenticatedPrincipal principal, String projectId, ProfileInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (profiles.countByProject(projectId) >= 100) throw rejected("每个项目最多创建 100 个执行配置集");
        Payload payload = payload(input);
        String id = UlidCreator.getUlid().toString();
        try { profiles.insert(id, project.organizationId(), projectId, payload.name(), payload.environmentCode(),
                profiles.json(payload.variables()), principal.userId()); }
        catch (DataIntegrityViolationException exception) { throw conflict("当前项目已存在同名执行配置集"); }
        ApiAutomationExecutionProfileDetail result = require(projectId, id);
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_EXECUTION_PROFILE_CREATED",
                "API_AUTOMATION_EXECUTION_PROFILE", id, auditDetails(result));
        return result;
    }

    @Transactional
    public ApiAutomationExecutionProfileDetail update(AuthenticatedPrincipal principal, String projectId, String profileId,
                                                       ProfileInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (input == null || input.expectedVersion() == null || input.expectedVersion() < 1) throw rejected("保存执行配置集时必须提供当前版本号");
        Payload payload = payload(input);
        int updated;
        try { updated = profiles.update(projectId, profileId, input.expectedVersion(), payload.name(), payload.environmentCode(),
                profiles.json(payload.variables())); }
        catch (DataIntegrityViolationException exception) { throw conflict("当前项目已存在同名执行配置集"); }
        if (updated == 0) throw conflict("执行配置集已被其他成员更新，请刷新后再保存");
        ApiAutomationExecutionProfileDetail result = require(projectId, profileId);
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_EXECUTION_PROFILE_UPDATED",
                "API_AUTOMATION_EXECUTION_PROFILE", profileId, auditDetails(result));
        return result;
    }

    ApiAutomationExecutionProfileDetail requireForExecution(String projectId, String profileId) { return require(projectId, profileId); }

    private Payload payload(ProfileInput input) {
        if (input == null) throw rejected("执行配置集参数不能为空");
        String name = required(input.name(), "执行配置集名称", 160);
        String environmentCode = environment(input.environmentCode());
        Map<String, String> variables = variables(input.variables());
        if (profiles.json(variables).length() > 256 * 1024) throw rejected("执行配置集变量不能超过 256 KB");
        return new Payload(name, environmentCode, variables);
    }

    static Map<String, String> validateVariables(Map<String, String> source, String label) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : (source == null ? Map.<String, String>of() : source).entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || value == null || value.length() > 10_000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "变量不合法：" + key);
            }
            result.put(key, value);
        }
        if (result.size() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "最多支持 100 个变量");
        return Map.copyOf(result);
    }

    private Map<String, String> variables(Map<String, String> source) { return validateVariables(source, "执行配置集"); }
    private String environment(String value) {
        String normalized = value == null || value.isBlank() ? "default" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw rejected("环境标识不合法");
        return normalized;
    }
    private ApiAutomationExecutionProfileDetail require(String projectId, String profileId) {
        return profiles.findDetail(projectId, profileId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "执行配置集不存在"));
    }
    private ProjectSummary writeAccess(AuthenticatedPrincipal principal, String projectId) { return access.requireProjectParameterWrite(principal, projectId, WRITERS); }
    private Map<String, Object> auditDetails(ApiAutomationExecutionProfileDetail value) { return Map.of("name", value.name(), "environmentCode", value.environmentCode(), "version", value.version(), "variableCount", value.variables().size()); }
    private String required(String value, String label, int maximum) { if (value == null || value.isBlank()) throw rejected(label + "不能为空"); String result = value.trim(); if (result.length() > maximum) throw rejected(label + "不能超过 " + maximum + " 个字符"); return result; }
    private ResponseStatusException rejected(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record ProfileInput(String name, String environmentCode, Map<String, String> variables, Integer expectedVersion) { }
    private record Payload(String name, String environmentCode, Map<String, String> variables) { }
}
