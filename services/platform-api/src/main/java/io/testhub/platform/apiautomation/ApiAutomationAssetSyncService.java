package io.testhub.platform.apiautomation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.storage.FileObjectService;
import io.testhub.platform.storage.ProjectFileReferenceGuard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Syncs editable supporting assets. Existing pinned revisions and execution records remain local. */
@Service
public class ApiAutomationAssetSyncService {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> KINDS = Set.of("REUSABLE_STEP", "DATA_SET", "EXECUTION_PROFILE");
    private static final Pattern DOWNLOAD = Pattern.compile("/api/v1/projects/([A-Z0-9]{26})/files/([A-Z0-9]{26})/download");
    private final ApiAutomationReusableStepRepository reusableRepository;
    private final ApiAutomationDataSetRepository dataSetRepository;
    private final ApiAutomationExecutionProfileRepository profileRepository;
    private final ApiAutomationReusableStepService reusable;
    private final ApiAutomationDataSetService dataSets;
    private final ApiAutomationExecutionProfileService profiles;
    private final ApiAutomationTransferRepository transfer;
    private final AccessService access;
    private final AuditService audit;
    private final ProjectFileReferenceGuard fileLinks;
    private final FileObjectService files;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ApiAutomationAssetSyncService(ApiAutomationReusableStepRepository reusableRepository,
            ApiAutomationDataSetRepository dataSetRepository, ApiAutomationExecutionProfileRepository profileRepository,
            ApiAutomationReusableStepService reusable, ApiAutomationDataSetService dataSets,
            ApiAutomationExecutionProfileService profiles, ApiAutomationTransferRepository transfer,
            AccessService access, AuditService audit, ProjectFileReferenceGuard fileLinks, FileObjectService files,
            JdbcClient jdbc, ObjectMapper json) {
        this.reusableRepository = reusableRepository; this.dataSetRepository = dataSetRepository;
        this.profileRepository = profileRepository; this.reusable = reusable; this.dataSets = dataSets;
        this.profiles = profiles; this.transfer = transfer; this.access = access; this.audit = audit;
        this.fileLinks = fileLinks; this.files = files; this.jdbc = jdbc; this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor, String projectId, String expectedUserId) {
        if (!actor.userId().equals(expectedUserId)) throw conflict("登录账号已变化，接口自动化资料同步已停止");
        access.requireProjectRead(actor, projectId);
        var items = new ArrayList<Item>();
        var ids = new TreeMap<String, String>();
        var versions = new TreeMap<String, Object>();
        for (var row : reusableRepository.findByProject(projectId)) {
            var detail = reusableRepository.findDetail(projectId, row.id()).orElseThrow();
            var item = item("REUSABLE_STEP", row.name(), new Reusable(detail.description(), detail.steps()));
            add(items, ids, item, row.id()); versions.put(key(item), detail.version());
        }
        for (var row : dataSetRepository.findByProject(projectId)) {
            var content = dataSetRepository.findContent(projectId, row.id()).orElseThrow();
            var ordered = content.rows().stream().map(value -> {
                var result = new LinkedHashMap<String, String>();
                row.columns().forEach(column -> result.put(column, value.get(column))); return (Map<String, String>) result;
            }).toList();
            String source = row.sourceFile() == null ? null : "/api/v1/projects/" + projectId + "/files/" + row.sourceFile().id() + "/download";
            var item = item("DATA_SET", row.name(), new DataSet(row.sourceFormat(), row.columns(), ordered, source));
            add(items, ids, item, row.id()); versions.put(key(item), List.of(row.checksum(), String.valueOf(row.updatedAt())));
        }
        for (var row : profileRepository.findByProject(projectId)) {
            var detail = profileRepository.findDetail(projectId, row.id()).orElseThrow();
            var item = item("EXECUTION_PROFILE", row.name(), new Profile(detail.environmentCode(), detail.variables()));
            add(items, ids, item, row.id()); versions.put(key(item), detail.version());
        }
        items.sort(Comparator.comparing(Item::kind).thenComparing(Item::name));
        capacity(items);
        return new Snapshot(1, "API_AUTOMATION_ASSET_SYNC", projectId, actor.userId(),
                digest(encoded(List.of(items, ids, versions))), items, ids);
    }

    public Request decode(byte[] body) {
        if (body == null || body.length == 0) throw invalid("接口自动化资料同步请求不能为空");
        if (body.length > MAX_BYTES) throw tooLarge();
        try { return json.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body); }
        catch (java.io.IOException error) { throw invalid("接口自动化资料同步 JSON 不合法或包含不支持的字段"); }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor, String projectId, Request request) {
        var project = access.requireProjectParameterWrite(actor, projectId, WRITERS);
        if (request == null || !actor.userId().equals(request.expectedUserId())) throw conflict("登录账号已变化，接口自动化资料同步已停止");
        if (!uuid(request.requestKey()) || request.expectedRevision() == null || !request.expectedRevision().matches("[a-f0-9]{64}")
                || request.operations() == null || request.operations().isEmpty() || request.operations().size() > 300)
            throw invalid("接口自动化同步请求标识、修订或操作数量无效");
        var operationIds = new HashSet<String>(); var keys = new HashSet<String>();
        for (var operation : request.operations()) {
            if (operation == null || !uuid(operation.operationId()) || !operationIds.add(operation.operationId()))
                throw invalid("接口自动化同步操作标识无效或重复");
            validate(projectId, operation.item());
            if (!keys.add(key(operation.item()))) throw invalid("同一接口自动化资料不能重复提交");
        }
        byte[] payload = encoded(request);
        if (payload.length > MAX_BYTES) throw tooLarge();
        String payloadHash = digest(payload);
        transfer.lockProject(projectId);
        var previous = jdbc.sql("SELECT payload_sha256,result_json FROM api_automation_asset_sync_receipt WHERE project_id=:p AND requested_by=:a AND request_key=:k")
                .param("p", projectId).param("a", actor.userId()).param("k", request.requestKey())
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).optional();
        if (previous.isPresent()) {
            if (!payloadHash.equals(previous.get().getKey())) throw conflict("该同步请求标识已用于其他自动化资料");
            try { return json.readValue(previous.get().getValue(), Receipt.class); }
            catch (JsonProcessingException error) { throw new IllegalStateException("接口自动化资料同步回执损坏", error); }
        }
        var before = snapshot(actor, projectId, actor.userId());
        if (!before.revision().equals(request.expectedRevision())) throw conflict("目标自动化资料已变化，请重新核对后同步");
        for (var operation : request.operations()) {
            fileLinks.validate(operation.item().definition(), projectId);
            if (operation.item().kind().equals("EXECUTION_PROFILE"))
                requireEnabledEnvironment(projectId, data(operation.item(), Profile.class).environmentCode());
        }
        var merged = new LinkedHashMap<String, Item>(); before.items().forEach(item -> merged.put(key(item), item));
        for (var operation : request.operations()) {
            var prior = merged.get(key(operation.item()));
            if (prior != null && !prior.name().equals(operation.item().name())) throw conflict("两端名称大小写不同，请先统一名称");
            merged.put(key(operation.item()), operation.item());
        }
        capacity(merged.values());
        try {
            for (var operation : request.operations()) {
                var prior = before.items().stream().filter(value -> key(value).equals(key(operation.item()))).findFirst();
                if (prior.isPresent() && json.valueToTree(prior.get()).equals(json.valueToTree(operation.item()))) continue;
                write(actor, projectId, operation.item(), before.itemIds().get(key(operation.item())));
            }
            var after = snapshot(actor, projectId, actor.userId());
            var byKey = new HashMap<String, Item>(); after.items().forEach(item -> byKey.put(key(item), item));
            var results = new ArrayList<Result>();
            for (var operation : request.operations()) {
                var actual = byKey.get(key(operation.item()));
                if (!json.valueToTree(operation.item()).equals(json.valueToTree(actual)))
                    throw conflict("自动化资料被规范化或引用变化，整批未提交；请核对后重试");
                results.add(new Result(operation.operationId(), actual));
            }
            var receipt = new Receipt(request.requestKey(), results);
            jdbc.sql("INSERT INTO api_automation_asset_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:a,:k,:h,:r)")
                    .param("id", UlidCreator.getUlid().toString()).param("o", project.organizationId()).param("p", projectId)
                    .param("a", actor.userId()).param("k", request.requestKey()).param("h", payloadHash)
                    .param("r", text(receipt)).update();
            audit.record(actor, project.organizationId(), projectId, "API_AUTOMATION_ASSETS_SYNCED", "API_AUTOMATION_ASSET_SYNC",
                    request.requestKey(), Map.of("assets", results.size()));
            return receipt;
        } catch (DataIntegrityViolationException error) { throw conflict("目标自动化资料名称或来源文件发生冲突，整批未提交"); }
    }

    private void write(AuthenticatedPrincipal actor, String projectId, Item item, String existingId) {
        switch (item.kind()) {
            case "REUSABLE_STEP" -> {
                var data = data(item, Reusable.class);
                if (existingId == null) reusable.create(actor, projectId,
                        new ApiAutomationReusableStepService.ReusableStepInput(item.name(), data.description(), data.steps(), null));
                else {
                    var old = reusable.detail(actor, projectId, existingId);
                    reusable.update(actor, projectId, existingId,
                            new ApiAutomationReusableStepService.ReusableStepInput(item.name(), data.description(), data.steps(), old.version()));
                }
            }
            case "DATA_SET" -> {
                var data = data(item, DataSet.class);
                String fileId = sourceFile(actor, projectId, data.sourceFileLink());
                if (existingId == null) dataSets.createTransferred(actor, projectId, item.name(), data.sourceFormat(), data.columns(), data.rows(), fileId);
                else dataSets.updateTransferred(actor, projectId, existingId, item.name(), data.sourceFormat(), data.columns(), data.rows(), fileId);
            }
            case "EXECUTION_PROFILE" -> {
                var data = data(item, Profile.class);
                requireEnabledEnvironment(projectId, data.environmentCode());
                if (existingId == null) profiles.create(actor, projectId,
                        new ApiAutomationExecutionProfileService.ProfileInput(item.name(), data.environmentCode(), data.variables(), null));
                else profiles.update(actor, projectId, existingId,
                        new ApiAutomationExecutionProfileService.ProfileInput(item.name(), data.environmentCode(), data.variables(),
                                profiles.detail(actor, projectId, existingId).version()));
            }
            default -> throw invalid("自动化同步资料类型无效");
        }
    }

    private void validate(String projectId, Item item) {
        if (item == null || item.kind() == null || !KINDS.contains(item.kind()) || item.name() == null || item.name().isBlank()
                || !item.name().equals(item.name().trim()) || item.name().length() > 160 || item.definition() == null)
            throw invalid("自动化同步资料类型、名称或定义无效");
        Object value;
        switch (item.kind()) {
            case "REUSABLE_STEP" -> {
                var data = data(item, Reusable.class);
                reusable.validateSyncInput(new ApiAutomationReusableStepService.ReusableStepInput(item.name(), data.description(), data.steps(), null));
                value = data;
            }
            case "DATA_SET" -> {
                var data = data(item, DataSet.class);
                dataSets.validateSyncRows(item.name(), data.sourceFormat(), data.columns(), data.rows());
                if (data.sourceFileLink() != null && !DOWNLOAD.matcher(data.sourceFileLink()).matches()) throw invalid("数据集来源文件链接无效");
                value = data;
            }
            case "EXECUTION_PROFILE" -> {
                var data = data(item, Profile.class);
                if (data.environmentCode() == null || !data.environmentCode().matches("[a-z0-9][a-z0-9_-]{0,63}")
                        || data.variables() == null || encoded(data.variables()).length > 256 * 1024)
                    throw invalid("执行配置集环境或变量无效");
                ApiAutomationExecutionProfileService.validateVariables(data.variables(), "同步执行配置集");
                value = data;
            }
            default -> throw invalid("自动化同步资料类型无效");
        }
        if (!json.valueToTree(value).equals(json.valueToTree(item.definition()))) throw invalid("自动化同步字段不完整或尚未规范化");
    }

    private String sourceFile(AuthenticatedPrincipal actor, String projectId, String link) {
        if (link == null) return null;
        var matcher = DOWNLOAD.matcher(link);
        if (!matcher.matches() || !projectId.equals(matcher.group(1))) throw conflict("来源文件不属于目标项目");
        var file = files.metadata(actor, projectId, matcher.group(2));
        if (file.sizeBytes() < 1 || file.sizeBytes() > 512 * 1024) throw conflict("数据集来源文件大小无效");
        return file.id();
    }
    private void requireEnabledEnvironment(String projectId, String code) {
        boolean enabled = jdbc.sql("SELECT enabled FROM project_environment WHERE project_id=:p AND environment_code=:code FOR UPDATE")
                .param("p", projectId).param("code", code).query(Boolean.class).optional().orElse(false);
        if (!enabled) throw conflict("目标执行环境缺失或已停用，请先核对环境同步");
    }
    private void capacity(java.util.Collection<Item> items) {
        if (items.size() > 300 || encoded(items).length > MAX_SNAPSHOT_BYTES) throw conflict("自动化基础资料超过 300 条或 8 MiB 同步范围");
        for (String kind : KINDS) if (items.stream().filter(item -> item.kind().equals(kind)).count() > 100)
            throw conflict("每类自动化基础资料最多支持 100 条");
    }
    private Item item(String kind, String name, Object value) { return new Item(kind, name, json.convertValue(value, new TypeReference<>() {})); }
    private void add(List<Item> items, Map<String, String> ids, Item item, String id) {
        if (ids.putIfAbsent(key(item), id) != null) throw conflict("自动化资料存在同名或大小写重复，请先统一名称");
        items.add(item);
    }
    private String key(Item item) { return item.kind() + "\n" + item.name().toLowerCase(Locale.ROOT); }
    private boolean uuid(String value) {
        try { return value != null && value.equals(UUID.fromString(value).toString()); }
        catch (IllegalArgumentException error) { return false; }
    }
    private <T> T data(Item item, Class<T> type) {
        try { return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(encoded(item.definition())); }
        catch (java.io.IOException error) { throw invalid("自动化同步资料字段无效"); }
    }
    private byte[] encoded(Object value) {
        try { return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("自动化同步资料序列化失败", error); }
    }
    private String text(Object value) { return new String(encoded(value), StandardCharsets.UTF_8); }
    private String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private ResponseStatusException tooLarge() { return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "接口自动化资料同步请求不能超过 10 MiB"); }
    public record Snapshot(int version, String scope, String projectId, String actorId, String revision, List<Item> items, Map<String, String> itemIds) { }
    public record Item(String kind, String name, Map<String, Object> definition) { }
    public record Reusable(String description, List<AutomationStep> steps) { }
    public record DataSet(String sourceFormat, List<String> columns, List<Map<String, String>> rows, String sourceFileLink) { }
    public record Profile(String environmentCode, Map<String, String> variables) { }
    public record Request(String requestKey, String expectedUserId, String expectedRevision, List<Operation> operations) { }
    public record Operation(String operationId, Item item) { }
    public record Result(String operationId, Item item) { }
    public record Receipt(String requestKey, List<Result> results) { }
}
