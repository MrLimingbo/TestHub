package io.testhub.platform.parameter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Add/update definitions only; execution preferences, secrets and deletions remain per workspace. */
@Service
public class ProjectSettingsSyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD");
    private final AccessService access;
    private final ProjectEnvironmentService environmentService;
    private final ProjectParameterService parameterService;
    private final ProjectEnvironmentRepository environments;
    private final ProjectParameterRepository parameters;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ProjectSettingsSyncService(AccessService access, ProjectEnvironmentService environmentService,
            ProjectParameterService parameterService, ProjectEnvironmentRepository environments,
            ProjectParameterRepository parameters, AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.access = access; this.environmentService = environmentService; this.parameterService = parameterService;
        this.environments = environments; this.parameters = parameters; this.audit = audit; this.jdbc = jdbc; this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor, String projectId, String expectedUserId) {
        access.requireProjectParameterWrite(actor, projectId, WRITERS); requireActor(actor, expectedUserId);
        return read(projectId, actor.userId());
    }

    private Snapshot read(String projectId, String actorId) {
        for (String table : List.of("project_environment", "project_parameter")) {
            int count = jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id=:project")
                    .param("project", projectId).query(Integer.class).single();
            if (count > (table.equals("project_environment") ? 100 : 2100)) throw invalid("配置同步最多支持 100 个环境、1000 个普通参数及 1000 个密钥定义");
        }
        var envs = environments.findByProject(projectId).stream().sorted(Comparator.comparing(ProjectEnvironment::environmentCode)).toList();
        var params = parameters.findByProject(projectId, null).stream().filter(p -> !p.key().equals("BASE_URL")).toList();
        var secrets = parameters.findSecretsByProject(projectId, null);
        if (params.size() > 1000 || secrets.size() > 1000) throw invalid("配置同步最多支持 1000 个普通参数及 1000 个密钥定义");
        var items = new ArrayList<Item>();
        for (var e : envs) items.add(new Item("ENVIRONMENT", e.environmentCode(), null, e.name(), e.baseUrl(), e.description(), null));
        for (var p : params) items.add(new Item("PARAMETER", p.environmentCode(), p.key(), null, null, null, p.value()));
        items.sort(Comparator.comparing(ProjectSettingsSyncService::key));
        var keys = new HashSet<String>();
        for (var item : items) { validate(item); if (!keys.add(key(item))) throw invalid("同一环境存在仅大小写不同的参数键，请先统一名称"); }
        var blocked = secrets.stream().map(s -> s.key().equalsIgnoreCase("BASE_URL") ? "E:" + s.environmentCode()
                : "P:" + s.environmentCode() + ":" + s.key().toLowerCase(Locale.ROOT)).distinct().sorted().toList();
        // Include row identity and local preferences: even delete/recreate or a default change invalidates a pending write.
        String revision = hash(encoded(List.of(envs, params, secrets)));
        var result = new Snapshot(1, "PROJECT_SETTINGS_SYNC", projectId, actorId, revision, List.copyOf(items), blocked, secrets.size());
        if (encoded(result).length > 3 * 1024 * 1024) throw invalid("项目环境和普通参数超过 3 MiB 同步范围，请缩减资料");
        return result;
    }

    public Request decode(byte[] body) {
        if (body == null || body.length == 0) throw invalid("配置同步请求不能为空");
        if (body.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "配置同步请求不能超过 4 MiB。");
        try { return json.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body); }
        catch (java.io.IOException error) { throw invalid("配置同步 JSON 格式不合法或包含不支持的字段"); }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor, String projectId, Request request) {
        var project = access.requireProjectParameterWrite(actor, projectId, WRITERS);
        if (request == null) throw invalid("配置同步请求不能为空");
        requireActor(actor, request.expectedUserId()); String requestKey = uuid(request.requestKey());
        if (request.expectedRevision() == null || !request.expectedRevision().matches("[a-f0-9]{64}") || request.operations() == null
                || request.operations().isEmpty() || request.operations().size() > 1100) throw invalid("配置同步版本或操作数量无效");
        var operationIds = new HashSet<String>(); var keys = new HashSet<String>();
        for (var op : request.operations()) {
            if (op == null || !operationIds.add(uuid(op.operationId()))) throw invalid("同步操作标识重复或无效");
            validate(op.item()); if (!keys.add(key(op.item()))) throw invalid("同一环境或参数不能重复提交");
        }
        byte[] payload = encoded(request);
        if (payload.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "配置同步请求不能超过 4 MiB。");
        String payloadHash = hash(payload);
        // All environment/ordinary parameter/secret writers share this lock, including the existing transfer service.
        parameters.lockProject(projectId);
        var prior = jdbc.sql("SELECT payload_sha256,result_json FROM project_settings_sync_receipt WHERE project_id=:project AND requested_by=:user AND request_key=:key")
                .param("project", projectId).param("user", actor.userId()).param("key", requestKey)
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getString(2))).optional();
        if (prior.isPresent()) {
            if (!payloadHash.equals(prior.get().getKey())) throw conflict("同步请求标识已用于其他配置内容");
            try { return json.readValue(prior.get().getValue(), Receipt.class); }
            catch (java.io.IOException error) { throw new IllegalStateException("项目配置同步回执损坏", error); }
        }
        var current = read(projectId, actor.userId());
        if (!request.expectedRevision().equals(current.revision())) throw conflict("目标配置已变化，请重新同步并核对冲突");
        var all = new HashMap<String, Item>(); current.items().forEach(item -> all.put(key(item), item));
        for (var op : request.operations()) {
            String key = key(op.item());
            if (current.blockedKeys().contains(key)) throw conflict("目标存在同名密钥，请在各端自行维护；同步不会覆盖密钥");
            var existing = all.get(key);
            if (existing != null && !Objects.equals(existing.key(), op.item().key())) throw conflict("两端参数键大小写不同，请先统一名称");
            all.put(key, op.item());
        }
        if (all.values().stream().filter(i -> i.kind().equals("ENVIRONMENT")).count() > 100
                || all.values().stream().filter(i -> i.kind().equals("PARAMETER")).count() > 1000) throw invalid("同步后将超过环境或普通参数数量限制");
        for (var op : request.operations()) if (op.item().kind().equals("PARAMETER") && !all.containsKey("E:" + op.item().environmentCode()))
            throw conflict("参数所属环境尚未定义，请先补齐环境");
        for (var op : request.operations()) if (op.item().kind().equals("ENVIRONMENT")) {
            var item = op.item(); var existing = environments.findByCode(projectId, item.environmentCode());
            if (existing.isEmpty()) environmentService.create(actor, projectId, new ProjectEnvironmentService.CreateInput(
                    item.name(), item.environmentCode(), item.baseUrl(), item.description(), true, false));
            else {
                var e = existing.get();
                environmentService.update(actor, projectId, e.id(), new ProjectEnvironmentService.UpdateInput(
                        item.name(), item.baseUrl(), item.description(), e.enabled(), e.version()));
            }
        }
        for (var op : request.operations()) if (op.item().kind().equals("PARAMETER")) {
            var item = op.item(); parameterService.upsert(actor, projectId, item.environmentCode(), item.key(), item.value());
        }
        var after = read(projectId, actor.userId()); var byKey = new HashMap<String, Item>(); after.items().forEach(i -> byKey.put(key(i), i));
        var receipt = new Receipt(requestKey, request.operations().stream().map(op -> new Result(op.operationId(), byKey.get(key(op.item())))).toList());
        jdbc.sql("INSERT INTO project_settings_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:org,:project,:user,:key,:hash,:result)")
                .param("id", UlidCreator.getUlid().toString()).param("org", project.organizationId()).param("project", projectId)
                .param("user", actor.userId()).param("key", requestKey).param("hash", payloadHash)
                .param("result", new String(encoded(receipt), StandardCharsets.UTF_8)).update();
        audit.record(actor, project.organizationId(), projectId, "PROJECT_SETTINGS_SYNCED", "PROJECT_SETTINGS_SYNC", requestKey,
                Map.of("operations", request.operations().size()));
        return receipt;
    }

    private void validate(Item item) {
        if (item == null || item.environmentCode() == null || !item.environmentCode().equals(ProjectParameterService.normalizeEnvironment(item.environmentCode()))) throw invalid("环境编码无效");
        if ("ENVIRONMENT".equals(item.kind())) {
            if (item.key() != null || item.value() != null || item.name() == null || item.baseUrl() == null || item.description() == null
                    || !item.name().equals(ProjectEnvironmentService.normalizeName(item.name()))
                    || !item.baseUrl().equals(ProjectEnvironmentService.normalizeBaseUrl(item.baseUrl()))
                    || !item.description().equals(ProjectEnvironmentService.normalizeDescription(item.description()))) throw invalid("同步环境定义无效");
        } else if ("PARAMETER".equals(item.kind())) {
            if (item.name() != null || item.baseUrl() != null || item.description() != null || item.key() == null
                    || !item.key().equals(ProjectParameterService.normalizeKey(item.key())) || item.key().equalsIgnoreCase("BASE_URL")
                    || item.value() == null || item.value().length() > 20000) throw invalid("同步普通参数无效，BASE_URL 应通过环境维护");
        } else throw invalid("同步配置类型无效");
    }
    public static String key(Item item) { return item.kind().equals("ENVIRONMENT") ? "E:" + item.environmentCode()
            : "P:" + item.environmentCode() + ":" + item.key().toLowerCase(Locale.ROOT); }
    private void requireActor(AuthenticatedPrincipal actor, String expected) { if (!actor.userId().equals(expected)) throw conflict("登录账号已变化，同步已停止"); }
    private String uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid("同步请求标识必须是 UUID");
        return UUID.fromString(value).toString();
    }
    private byte[] encoded(Object value) {
        try { return json.writeValueAsBytes(value); }
        catch (java.io.IOException error) { throw new IllegalStateException("项目配置同步序列化失败", error); }
    }
    private String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    public record Item(String kind, String environmentCode, String key, String name, String baseUrl, String description, String value) { }
    public record Snapshot(int version, String scope, String projectId, String actorId, String revision, List<Item> items, List<String> blockedKeys, int excludedSecrets) { }
    public record Operation(String operationId, Item item) { }
    public record Request(String requestKey, String expectedUserId, String expectedRevision, List<Operation> operations) { }
    public record Result(String operationId, Item item) { }
    public record Receipt(String requestKey, List<Result> results) { }
}
