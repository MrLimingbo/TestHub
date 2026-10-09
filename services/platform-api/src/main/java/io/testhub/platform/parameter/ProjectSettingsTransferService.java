package io.testhub.platform.parameter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Explicit copy of environment definitions and ordinary parameters, without secret values or identities. */
@Service
public class ProjectSettingsTransferService {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD");
    private final AccessService access;
    private final ProjectEnvironmentRepository environments;
    private final ProjectParameterRepository parameters;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ProjectSettingsTransferService(AccessService access, ProjectEnvironmentRepository environments,
            ProjectParameterRepository parameters, AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.access = access; this.environments = environments; this.parameters = parameters;
        this.audit = audit; this.jdbc = jdbc; this.json = json;
    }

    public Map<String, Object> capabilities(AuthenticatedPrincipal actor, String projectId) {
        access.requireProjectParameterWrite(actor, projectId, WRITERS);
        return Map.of("version", 1, "scope", "PROJECT_SETTINGS", "maxBytes", MAX_BYTES,
                "includes", List.of("environments", "parameters"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal actor, String projectId) {
        access.requireProjectParameterWrite(actor, projectId, WRITERS);
        var result = new Snapshot(1, projectId, environments.findByProject(projectId).stream().map(e ->
                new Environment(e.id(), e.name(), e.environmentCode(), e.baseUrl(), e.description(),
                        e.enabled(), e.defaultEnvironment())).toList(),
                parameters.findByProject(projectId, null).stream().filter(p -> !"BASE_URL".equals(p.key())).map(p ->
                        new Parameter(p.id(), p.environmentCode(), p.key(), p.value())).toList(),
                parameters.findSecretsByProject(projectId, null).size());
        validate(result); encoded(result); return result;
    }

    public ImportRequest decode(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BYTES) throw invalid("配置迁移请求不能为空或超过 32 MiB");
        try {
            ImportRequest request = json.readerFor(ImportRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
            if (request == null || request.snapshot() == null) throw invalid("配置快照不能为空");
            return request;
        } catch (java.io.IOException error) { throw invalid("配置迁移 JSON 格式不合法或包含不支持的字段"); }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt transfer(AuthenticatedPrincipal actor, String projectId, ImportRequest request) {
        var project = access.requireProjectParameterWrite(actor, projectId, WRITERS);
        if (request == null || request.requestKey() == null || !request.requestKey().matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid("requestKey 必须是 UUID");
        if (!actor.userId().equals(request.expectedUserId())) throw conflict("登录账号已变化，请重新预览");
        var source = request.snapshot(); validate(source);
        String key = UUID.fromString(request.requestKey()).toString(), hash = hash(encoded(source));
        parameters.lockProject(projectId);
        var prior = jdbc.sql("SELECT payload_sha256,result_json FROM project_settings_transfer_receipt WHERE project_id=:project AND requested_by=:user AND request_key=:key FOR UPDATE")
                .param("project", projectId).param("user", actor.userId()).param("key", key)
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getString(2))).optional();
        if (prior.isPresent()) {
            if (!hash.equals(prior.get().getKey())) throw conflict("该 requestKey 已用于其他配置资料");
            try { return json.readValue(prior.get().getValue(), Receipt.class); }
            catch (java.io.IOException error) { throw new IllegalStateException("项目配置迁移回执损坏", error); }
        }
        if (source.environments().isEmpty() && source.parameters().isEmpty()) throw invalid("来源没有可迁移的环境或普通参数");
        int createdEnvironments = 0, reusedEnvironments = 0, createdParameters = 0, reusedParameters = 0;
        boolean hasDefault = environments.findByProject(projectId).stream().anyMatch(ProjectEnvironment::defaultEnvironment);
        for (var input : source.environments()) {
            var existing = environments.findByCode(projectId, input.environmentCode());
            if (existing.isPresent()) {
                var e = existing.get();
                if (!Objects.equals(e.name(), input.name()) || !Objects.equals(e.baseUrl(), input.baseUrl())
                        || !Objects.equals(e.description(), input.description()) || e.enabled() != input.enabled())
                    throw conflict("目标环境 " + input.environmentCode() + " 配置不同，请调整后重新预览；不会覆盖目标");
                reusedEnvironments++;
            } else {
                boolean makeDefault = !hasDefault && input.defaultEnvironment();
                environments.insert(UlidCreator.getUlid().toString(), project.organizationId(), projectId,
                        input.name(), input.environmentCode(), input.baseUrl(), input.description(), input.enabled(),
                        makeDefault, actor.userId());
                hasDefault |= makeDefault; createdEnvironments++;
            }
            // BASE_URL is a derived ordinary parameter, not an independently editable configuration.
            if (!input.baseUrl().isEmpty()) {
                if (copyParameter(projectId, project.organizationId(), actor.userId(), input.environmentCode(), "BASE_URL", input.baseUrl()))
                    createdParameters++;
                else reusedParameters++;
            }
        }
        for (var input : source.parameters()) {
            if (environments.findByCode(projectId, input.environmentCode()).isEmpty())
                throw conflict("参数所属环境 " + input.environmentCode() + " 尚未定义，请先补齐环境后重新预览");
            if (copyParameter(projectId, project.organizationId(), actor.userId(), input.environmentCode(), input.key(), input.value()))
                createdParameters++;
            else reusedParameters++;
        }
        var result = new Receipt(UlidCreator.getUlid().toString(), createdEnvironments, reusedEnvironments,
                createdParameters, reusedParameters, source.excludedSecrets());
        jdbc.sql("INSERT INTO project_settings_transfer_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:org,:project,:user,:key,:hash,:result)")
                .param("id", result.id()).param("org", project.organizationId()).param("project", projectId).param("user", actor.userId())
                .param("key", key).param("hash", hash).param("result", new String(encoded(result), StandardCharsets.UTF_8)).update();
        audit.record(actor, project.organizationId(), projectId, "PROJECT_SETTINGS_TRANSFERRED", "PROJECT_SETTINGS_TRANSFER", result.id(),
                Map.of("environments", createdEnvironments, "parameters", createdParameters, "excludedSecrets", source.excludedSecrets()));
        return result;
    }

    private boolean copyParameter(String projectId, String org, String user, String environment, String key, String value) {
        var existing = jdbc.sql("SELECT secret,parameter_value FROM project_parameter WHERE project_id=:project AND environment_code=:environment AND parameter_key=:key FOR UPDATE")
                .param("project", projectId).param("environment", environment).param("key", key)
                .query((rs, row) -> new ExistingParameter(rs.getBoolean(1), rs.getString(2))).optional();
        if (existing.isPresent()) {
            if (existing.get().secret() || !Objects.equals(existing.get().value(), value))
                throw conflict("目标参数 " + environment + "/" + key + " 已存在不同配置或密钥；不会覆盖目标");
            return false;
        }
        parameters.upsert(UlidCreator.getUlid().toString(), org, projectId, environment, key, value, user);
        return true;
    }

    private void validate(Snapshot value) {
        if (value == null || value.version() != 1 || !id(value.sourceProjectId()) || value.environments() == null
                || value.parameters() == null || value.environments().size() > 100 || value.parameters().size() > 1000
                || value.excludedSecrets() == null || value.excludedSecrets() < 0) throw invalid("项目配置协议或资料数量不合法");
        Set<String> ids = new HashSet<>(), codes = new HashSet<>(), keys = new HashSet<>(); int defaults = 0;
        for (var e : value.environments()) {
            if (e == null || !id(e.id()) || !ids.add(e.id()) || e.enabled() == null || e.defaultEnvironment() == null
                    || e.name() == null || e.description() == null || e.baseUrl() == null || e.environmentCode() == null
                    || !codes.add(e.environmentCode()) || !e.name().equals(ProjectEnvironmentService.normalizeName(e.name()))
                    || !e.description().equals(ProjectEnvironmentService.normalizeDescription(e.description()))
                    || !e.baseUrl().equals(ProjectEnvironmentService.normalizeBaseUrl(e.baseUrl()))
                    || !e.environmentCode().equals(ProjectParameterService.normalizeEnvironment(e.environmentCode()))
                    || e.defaultEnvironment() && !e.enabled()) throw invalid("环境定义或重复标识不合法");
            if (e.defaultEnvironment()) defaults++;
        }
        if (!value.environments().isEmpty() && defaults != 1) throw invalid("来源配置需要且只能有一个启用的默认环境");
        for (var p : value.parameters()) {
            if (p == null || !id(p.id()) || !ids.add(p.id()) || p.environmentCode() == null || p.key() == null
                    || !p.environmentCode().equals(ProjectParameterService.normalizeEnvironment(p.environmentCode()))
                    || !p.key().equals(ProjectParameterService.normalizeKey(p.key())) || "BASE_URL".equals(p.key())
                    || !keys.add(p.environmentCode() + ":" + p.key()) || p.value() == null || p.value().length() > 20_000)
                throw invalid("普通参数定义或重复标识不合法，BASE_URL 应通过环境基础地址维护");
        }
    }
    private boolean id(String value) { return value != null && value.matches("[0-9A-HJKMNP-TV-Z]{26}"); }
    private byte[] encoded(Object value) {
        try { byte[] result = json.writeValueAsBytes(value); if (result.length > MAX_BYTES) throw invalid("项目配置超过 32 MiB"); return result; }
        catch (java.io.IOException error) { throw invalid("项目配置无法编码"); }
    }
    private String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private record ExistingParameter(boolean secret, String value) { }
    public record Environment(String id, String name, String environmentCode, String baseUrl, String description,
                              Boolean enabled, Boolean defaultEnvironment) { }
    public record Parameter(String id, String environmentCode, String key, String value) { }
    public record Snapshot(int version, String sourceProjectId, List<Environment> environments, List<Parameter> parameters, Integer excludedSecrets) { }
    public record ImportRequest(String requestKey, String expectedUserId, Snapshot snapshot) { }
    public record Receipt(String id, int createdEnvironments, int reusedEnvironments, int createdParameters,
                          int reusedParameters, int excludedSecrets) { }
}
