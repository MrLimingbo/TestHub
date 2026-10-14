package io.testhub.platform.apiautomation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Fixed references carry immutable content rather than endpoint-specific IDs and version counters. */
@Component
public class ApiAutomationScenarioSyncProjection {
    private final ApiAutomationScenarioRepository scenarios;
    private final ApiAutomationReusableStepRepository reusable;
    private final ApiAutomationScenarioExpansionService expansions;
    private final AccessService access;
    private final ObjectMapper json;
    public ApiAutomationScenarioSyncProjection(ApiAutomationScenarioRepository scenarios,
            ApiAutomationReusableStepRepository reusable, ApiAutomationScenarioExpansionService expansions,
            AccessService access, ObjectMapper json) {
        this.scenarios = scenarios; this.reusable = reusable; this.expansions = expansions;
        this.access = access; this.json = json;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot read(AuthenticatedPrincipal actor, String projectId, String expectedUserId) {
        if (!actor.userId().equals(expectedUserId)) throw conflict("登录账号已变化，场景同步已停止");
        access.requireProjectRead(actor, projectId);
        var rows = scenarios.findByProject(projectId);
        var scenarioNames = new HashMap<String, String>(); var reusableNames = new HashMap<String, String>();
        var referenceIds = new TreeMap<String, String>(); var versions = new TreeMap<String, Integer>();
        for (var row : rows) {
            scenarioNames.put(row.id(), row.name()); addReference(referenceIds, "SCENARIO", row.name(), row.id());
            versions.put("SCENARIO\n" + row.name().toLowerCase(Locale.ROOT), row.version());
        }
        for (var row : reusable.findByProject(projectId)) {
            reusableNames.put(row.id(), row.name()); addReference(referenceIds, "REUSABLE_STEP", row.name(), row.id());
            versions.put("REUSABLE_STEP\n" + row.name().toLowerCase(Locale.ROOT), row.version());
        }
        var items = new ArrayList<Item>(); var itemIds = new TreeMap<String, String>(); var blocked = new TreeMap<String, String>();
        int totalBytes = 0;
        for (var row : rows) {
            itemIds.put(row.name().toLowerCase(Locale.ROOT), row.id());
            var detail = scenarios.findDetail(projectId, row.id()).orElseThrow();
            try {
                expansions.validateCandidate(projectId, row.id(), detail.steps());
                var steps = new ArrayList<AutomationStep>();
                int stepBytes = 0;
                for (var step : detail.steps()) {
                    if (step.type().equals("REUSABLE_STEP")) {
                        String id = String.valueOf(step.config().get("reusableStepId"));
                        var source = reusable.findRevisionSnapshot(projectId, id, version(step.config().get("reusableStepVersion")))
                                .orElseThrow(() -> conflict("固定公共步骤修订缺失"));
                        steps.add(new AutomationStep(step.id(), step.type(), step.name(), Map.of("sourceName", requireName(reusableNames, id), "revision", value(source))));
                    } else if (step.type().equals("SUB_SCENARIO")) {
                        String id = String.valueOf(step.config().get("scenarioId"));
                        var source = scenarios.findRevisionSnapshot(projectId, id, version(step.config().get("scenarioVersion")))
                                .orElseThrow(() -> conflict("固定子场景修订缺失"));
                        steps.add(new AutomationStep(step.id(), step.type(), step.name(), Map.of("sourceName", requireName(scenarioNames, id), "revision", value(source))));
                    } else steps.add(step);
                    stepBytes += encoded(steps.getLast()).length;
                    if (stepBytes > 8 * 1024 * 1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "固定引用内容超过 8 MiB");
                }
                var item = new Item(row.name(), value(new Definition(detail.description(), detail.environmentCode(), detail.failureStrategy(),
                        steps, detail.variables(), detail.preRequestScript(), detail.postResponseScript())));
                totalBytes += encoded(item).length; items.add(item);
            } catch (ResponseStatusException error) {
                blocked.put(row.name(), error.getStatusCode().value() == 413 ? "固定历史引用内容超过 8 MiB，请先缩减资料。"
                        : "本端场景的固定历史引用已缺失或不受支持，请先修复引用。");
            }
            if (totalBytes > 8 * 1024 * 1024) throw conflict("场景资料超过 8 MiB 同步范围");
        }
        items.sort(Comparator.comparing(Item::name));
        if (rows.size() > 200 || encoded(items).length > 8 * 1024 * 1024) throw conflict("场景资料超过 200 条或 8 MiB 同步范围");
        return new Snapshot(1, "API_AUTOMATION_SCENARIO_SYNC", projectId, actor.userId(),
                digest(encoded(List.of(items, blocked, itemIds, referenceIds, versions))), items, blocked, itemIds, referenceIds);
    }
    private void addReference(Map<String, String> references, String kind, String name, String id) {
        String key = kind + "\n" + name;
        if (references.keySet().stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).equals(key.toLowerCase(Locale.ROOT))))
            throw conflict("场景或公共步骤存在同名、大小写重复，请先统一名称");
        references.put(key, id);
    }
    private String requireName(Map<String, String> names, String id) {
        var value = names.get(id); if (value == null) throw conflict("固定引用的来源已删除"); return value;
    }
    private int version(Object value) {
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException error) { throw conflict("固定引用的版本无效"); }
    }
    private Map<String, Object> value(Object source) { return json.convertValue(source, new TypeReference<>() {}); }
    byte[] encoded(Object source) {
        try { return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(source); }
        catch (JsonProcessingException error) { throw new IllegalStateException("场景同步资料序列化失败", error); }
    }
    private String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    public record Snapshot(int version, String scope, String projectId, String actorId, String revision, List<Item> items,
                           Map<String, String> blockedNames, Map<String, String> itemIds, Map<String, String> referenceIds) { }
    public record Item(String name, Map<String, Object> definition) { }
    public record Definition(String description, String environmentCode, String failureStrategy, List<AutomationStep> steps,
                             Map<String, String> variables, String preRequestScript, String postResponseScript) { }
}
