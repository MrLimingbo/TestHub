package io.testhub.platform.apiautomation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
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
import org.quartz.CronExpression;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Transfers definitions only. Incoming changes pause local triggers; secrets and execution state remain local. */
@Service
public class ApiAutomationTriggerSyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Set<String> MANAGERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN");
    private final ApiAutomationScheduleRepository schedules;
    private final ApiAutomationWebhookTriggerRepository webhooks;
    private final ApiAutomationScenarioRepository scenarios;
    private final ApiAutomationExecutionProfileRepository profiles;
    private final ApiAutomationScheduleService scheduleService;
    private final ApiAutomationWebhookTriggerService webhookService;
    private final ApiAutomationTransferRepository projectLocks;
    private final AccessService access;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public ApiAutomationTriggerSyncService(ApiAutomationScheduleRepository schedules, ApiAutomationWebhookTriggerRepository webhooks,
            ApiAutomationScenarioRepository scenarios, ApiAutomationExecutionProfileRepository profiles,
            ApiAutomationScheduleService scheduleService, ApiAutomationWebhookTriggerService webhookService,
            ApiAutomationTransferRepository projectLocks, AccessService access, AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.schedules = schedules; this.webhooks = webhooks; this.scenarios = scenarios; this.profiles = profiles;
        this.scheduleService = scheduleService; this.webhookService = webhookService; this.projectLocks = projectLocks;
        this.access = access; this.audit = audit; this.jdbc = jdbc; this.json = json;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor, String project, String expectedUserId) {
        requireActor(actor, expectedUserId); access.requireProjectParameterWrite(actor, project, MANAGERS);
        var references = new TreeMap<String, String>(); var refVersions = new TreeMap<String, Integer>();
        var names = new HashMap<String, String>(); var groups = new HashMap<String, List<String>>();
        for (var row : scenarios.findByProject(project)) {
            names.put(row.id(), row.name()); groups.computeIfAbsent("SCENARIO\n" + lower(row.name()), k -> new ArrayList<>()).add(row.id());
            refVersions.put("SCENARIO\n" + row.id(), row.version());
        }
        for (var row : profiles.findByProject(project)) {
            names.put(row.id(), row.name()); groups.computeIfAbsent("EXECUTION_PROFILE\n" + lower(row.name()), k -> new ArrayList<>()).add(row.id());
            refVersions.put("EXECUTION_PROFILE\n" + row.id(), row.version());
        }
        groups.forEach((key, ids) -> { if (ids.size() == 1) references.put(key.substring(0, key.indexOf('\n') + 1) + names.get(ids.getFirst()), ids.getFirst()); });
        var candidates = new ArrayList<Candidate>(); var flags = new TreeMap<String, Object>();
        for (var row : schedules.findByProject(project)) {
            var item = item("SCHEDULE", row.name(), new ScheduleDefinition(names.get(row.scenarioId()), row.executionProfileId() == null ? null : names.get(row.executionProfileId()),
                    row.cronExpression(), row.timezone(), row.timeoutSeconds(), row.maxConsecutiveFailures()));
            candidates.add(new Candidate(item, row.id(), row.scenarioId(), row.executionProfileId()));
            flags.put(row.id(), row.enabled());
        }
        for (var row : webhooks.findByProject(project)) {
            candidates.add(new Candidate(item("WEBHOOK", row.name(), new WebhookDefinition(names.get(row.scenarioId()))), row.id(), row.scenarioId(), null));
            flags.put(row.id(), List.of(row.enabled(), row.secretReady()));
        }
        for (var kind : List.of("SCHEDULE", "WEBHOOK")) if (candidates.stream().filter(c -> c.item().kind().equals(kind)).count() > 200)
            throw conflict("每类触发配置最多同步 200 条，现有记录保留");
        var grouped = new TreeMap<String, List<Candidate>>(); candidates.forEach(c -> grouped.computeIfAbsent(key(c.item()), k -> new ArrayList<>()).add(c));
        var items = new ArrayList<Item>(); var ids = new TreeMap<String, String>(); var blocked = new TreeMap<String, String>();
        grouped.forEach((key, values) -> {
            if (values.size() != 1) { blocked.put(key, "本端触发器存在同名或大小写重复，请先统一名称。"); return; }
            var c = values.getFirst(); ids.put(key, c.id());
            try {
                validate(c.item());
                var refs = references(c.item());
                if (!c.scenario().equals(references.get(refs.getFirst())) || (c.profile() != null && !c.profile().equals(references.get(refs.getLast()))))
                    throw conflict("引用名称不唯一或来源已缺失");
                items.add(c.item());
            } catch (ResponseStatusException e) { blocked.put(key, "本端触发配置或场景／配置集引用失效，请修复后同步。"); }
        });
        items.sort(Comparator.comparing(Item::kind).thenComparing(Item::name));
        if (encoded(items).length > 2 * 1024 * 1024) throw conflict("触发配置超过 2 MiB 同步范围");
        return new Snapshot(1, "API_AUTOMATION_TRIGGER_SYNC", project, actor.userId(), hash(encoded(List.of(items, ids, blocked, references, refVersions, flags))), items, ids, blocked, references);
    }
    public Request decode(byte[] body) {
        if (body == null || body.length == 0) throw invalid("触发配置同步请求不能为空");
        if (body.length > MAX_BYTES) throw tooLarge(); return decoded(body, Request.class);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor, String project, Request request) {
        var context = access.requireProjectParameterWrite(actor, project, MANAGERS);
        if (request == null) throw invalid("触发配置同步请求不能为空"); requireActor(actor, request.expectedUserId());
        if (!uuid(request.requestKey()) || request.expectedRevision() == null || !request.expectedRevision().matches("[a-f0-9]{64}")
                || request.operations() == null || request.operations().isEmpty() || request.operations().size() > 400) throw invalid("触发配置同步请求标识或数量无效");
        var opIds = new HashSet<String>(); var keys = new HashSet<String>();
        for (var op : request.operations()) {
            if (op == null || !uuid(op.operationId()) || !opIds.add(op.operationId())) throw invalid("触发配置操作标识重复或无效");
            validate(op.item()); if (!keys.add(key(op.item()))) throw invalid("同一触发配置不能重复提交");
        }
        byte[] body = encoded(request); if (body.length > MAX_BYTES) throw tooLarge(); String digest = hash(body);
        projectLocks.lockProject(project);
        var prior = jdbc.sql("SELECT payload_sha256,result_json FROM api_automation_trigger_sync_receipt WHERE project_id=:p AND requested_by=:u AND request_key=:k")
                .param("p", project).param("u", actor.userId()).param("k", request.requestKey()).query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).optional();
        if (prior.isPresent()) {
            if (!prior.get().getKey().equals(digest)) throw conflict("该请求标识已用于其他触发配置");
            return decoded(prior.get().getValue().getBytes(StandardCharsets.UTF_8), Receipt.class);
        }
        for (String table : List.of("api_automation_schedule", "api_automation_webhook_trigger", "api_automation_scenario", "api_automation_execution_profile"))
            jdbc.sql("SELECT id FROM " + table + " WHERE project_id=:p ORDER BY id FOR UPDATE").param("p", project).query(String.class).list();
        var before = snapshot(actor, project, actor.userId());
        if (!before.revision().equals(request.expectedRevision())) throw conflict("目标触发配置、启用状态或引用来源已变化，请重新核对");
        var current = new HashMap<String, Item>(); before.items().forEach(i -> current.put(key(i), i));
        var merged = new LinkedHashMap<>(current);
        for (var op : request.operations()) {
            String k = key(op.item());
            if (before.blockedKeys().containsKey(k)) throw conflict("目标同名触发器重复或失效，请先在目标端核对");
            if (current.containsKey(k) && !current.get(k).name().equals(op.item().name())) throw conflict("触发器名称大小写不同，请先统一");
            for (var ref : references(op.item())) if (!before.referenceIds().containsKey(ref)) throw conflict("目标场景或配置集缺失或名称不同，请先同步并核对");
            merged.put(k, op.item());
        }
        for (var kind : List.of("SCHEDULE", "WEBHOOK")) {
            long existing = kind.equals("SCHEDULE") ? schedules.findByProject(project).size() : webhooks.findByProject(project).size();
            long added = request.operations().stream().filter(op -> op.item().kind().equals(kind) && !before.itemIds().containsKey(key(op.item()))).count();
            if (existing + added > 200) throw conflict("合并后每类触发配置超过 200 条");
        }
        if (encoded(merged.values()).length > 2 * 1024 * 1024) throw conflict("合并后触发配置超过 2 MiB");
        try {
            for (var op : request.operations()) {
                var item = op.item(); if (same(current.get(key(item)), item)) continue;
                String id = before.itemIds().get(key(item));
                if (item.kind().equals("SCHEDULE")) {
                    var d = definition(item, ScheduleDefinition.class);
                    scheduleService.saveSyncedConfiguration(actor, project, id, new ApiAutomationScheduleService.ScheduleInput(
                            before.referenceIds().get("SCENARIO\n" + d.scenarioName()), d.executionProfileName() == null ? null : before.referenceIds().get("EXECUTION_PROFILE\n" + d.executionProfileName()),
                            item.name(), d.cronExpression(), d.timezone(), d.timeoutSeconds(), d.maxConsecutiveFailures(), false));
                } else {
                    var d = definition(item, WebhookDefinition.class); String scene = before.referenceIds().get("SCENARIO\n" + d.scenarioName());
                    if (id == null) webhookService.createSyncedDraft(actor, project, scene, item.name());
                    else webhookService.updateConfiguration(actor, project, id, scene, item.name());
                }
            }
            var after = snapshot(actor, project, actor.userId());
            var actual = new HashMap<String, Item>(); after.items().forEach(i -> actual.put(key(i), i));
            var results = new ArrayList<Result>();
            for (var op : request.operations()) {
                var item = actual.get(key(op.item()));
                if (!same(item, op.item())) throw conflict("触发配置被规范化或引用已变化，整批未提交");
                results.add(new Result(op.operationId(), item));
            }
            var receipt = new Receipt(request.requestKey(), results);
            jdbc.sql("INSERT INTO api_automation_trigger_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:u,:k,:h,:r)")
                    .param("id", UlidCreator.getUlid().toString()).param("o", context.organizationId()).param("p", project).param("u", actor.userId())
                    .param("k", request.requestKey()).param("h", digest).param("r", new String(encoded(receipt), StandardCharsets.UTF_8)).update();
            audit.record(actor, context.organizationId(), project, "API_AUTOMATION_TRIGGERS_SYNCED", "API_AUTOMATION_TRIGGER_SYNC", request.requestKey(), Map.of("definitions", results.size(), "incomingChangesPaused", true));
            return receipt;
        } catch (DataIntegrityViolationException e) { throw conflict("触发配置名称或引用约束冲突，整批未提交"); }
    }
    private void validate(Item item) {
        if (item == null || !Set.of("SCHEDULE", "WEBHOOK").contains(item.kind() == null ? "" : item.kind()) || !name(item.name()) || item.definition() == null) throw invalid("触发配置类型、名称或定义无效");
        Object value;
        if (item.kind().equals("SCHEDULE")) {
            var d = definition(item, ScheduleDefinition.class); value = d;
            if (!name(d.scenarioName()) || (d.executionProfileName() != null && !name(d.executionProfileName())) || !name(d.cronExpression())
                    || !CronExpression.isValidExpression(d.cronExpression()) || d.timezone() == null || !d.timezone().equals(d.timezone().trim()) || d.timezone().length() > 64
                    || d.timeoutSeconds() < 10 || d.timeoutSeconds() > 3600 || d.maxConsecutiveFailures() < 1 || d.maxConsecutiveFailures() > 10) throw invalid("定时计划配置字段不合法");
            try { ZoneId.of(d.timezone()); } catch (RuntimeException e) { throw invalid("计划时区不合法"); }
        } else { var d = definition(item, WebhookDefinition.class); value = d; if (!name(d.scenarioName())) throw invalid("Webhook 场景名称无效"); }
        if (!same(value, item.definition())) throw invalid("触发配置字段不完整或包含不支持的字段");
    }
    private List<String> references(Item item) {
        if (item.kind().equals("WEBHOOK")) return List.of("SCENARIO\n" + definition(item, WebhookDefinition.class).scenarioName());
        var d = definition(item, ScheduleDefinition.class); var refs = new ArrayList<String>(); refs.add("SCENARIO\n" + d.scenarioName());
        if (d.executionProfileName() != null) refs.add("EXECUTION_PROFILE\n" + d.executionProfileName()); return refs;
    }
    private <T> T definition(Item item, Class<T> type) { return decoded(encoded(item.definition()), type); }
    private Item item(String kind, String name, Object value) { return new Item(kind, name, json.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() {})); }
    private boolean same(Object a, Object b) { return a != null && b != null && json.valueToTree(a).equals(json.valueToTree(b)); }
    private String key(Item i) { return i.kind() + "\n" + lower(i.name()); }
    private String lower(String v) { return v.toLowerCase(Locale.ROOT); }
    private boolean name(String v) { return v != null && !v.isBlank() && v.equals(v.trim()) && v.length() <= 160; }
    private void requireActor(AuthenticatedPrincipal actor, String expected) { if (!actor.userId().equals(expected)) throw conflict("登录账号已变化，触发配置同步已停止"); }
    private boolean uuid(String v) { try { return v != null && v.equals(UUID.fromString(v).toString()); } catch (IllegalArgumentException e) { return false; } }
    private <T> T decoded(byte[] bytes, Class<T> type) { try { T result = json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes); if (result == null) throw invalid("触发配置不能为空"); return result; } catch (java.io.IOException e) { throw invalid("触发配置 JSON 不合法或包含不支持的字段"); } }
    private byte[] encoded(Object value) { try { return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value); } catch (java.io.IOException e) { throw new IllegalStateException("触发配置无法序列化", e); } }
    private String hash(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private ResponseStatusException tooLarge() { return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "触发配置同步请求不能超过 4 MiB"); }
    private record Candidate(Item item, String id, String scenario, String profile) { }
    public record Snapshot(int version, String scope, String projectId, String actorId, String revision, List<Item> items, Map<String, String> itemIds, Map<String, String> blockedKeys, Map<String, String> referenceIds) { }
    public record Item(String kind, String name, Map<String, Object> definition) { }
    public record ScheduleDefinition(String scenarioName, String executionProfileName, String cronExpression, String timezone, int timeoutSeconds, int maxConsecutiveFailures) { }
    public record WebhookDefinition(String scenarioName) { }
    public record Request(String requestKey, String expectedUserId, String expectedRevision, List<Operation> operations) { }
    public record Operation(String operationId, Item item) { }
    public record Result(String operationId, Item item) { }
    public record Receipt(String requestKey, List<Result> results) { }
}
