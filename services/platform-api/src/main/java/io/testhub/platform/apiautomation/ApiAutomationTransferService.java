package io.testhub.platform.apiautomation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.parameter.ProjectEnvironmentRepository;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.storage.FileObjectService;
import io.testhub.platform.storage.FileTransferStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Versioned, explicit copy of editable automation assets. Execution history and Webhook secrets are excluded. */
@Service
public class ApiAutomationTransferService {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final ApiAutomationScenarioRepository scenarioRepository;
    private final ApiAutomationReusableStepRepository reusableRepository;
    private final ApiAutomationDataSetRepository dataSetRepository;
    private final ApiAutomationExecutionProfileRepository profileRepository;
    private final ApiAutomationScheduleRepository scheduleRepository;
    private final ApiAutomationScenarioService scenarios;
    private final ApiAutomationReusableStepService reusableSteps;
    private final ApiAutomationDataSetService dataSets;
    private final ApiAutomationExecutionProfileService profiles;
    private final ApiAutomationScheduleService schedules;
    private final ApiAutomationTransferRepository receipts;
    private final ProjectEnvironmentRepository environments;
    private final FileObjectService files;
    private final FileTransferStorage fileStorage;
    private final AuditService audit;
    private final ObjectMapper json;

    public ApiAutomationTransferService(AccessService access, ApiAutomationScenarioRepository scenarioRepository,
                                        ApiAutomationReusableStepRepository reusableRepository,
                                        ApiAutomationDataSetRepository dataSetRepository,
                                        ApiAutomationExecutionProfileRepository profileRepository,
                                        ApiAutomationScheduleRepository scheduleRepository,
                                        ApiAutomationScenarioService scenarios, ApiAutomationReusableStepService reusableSteps,
                                        ApiAutomationDataSetService dataSets, ApiAutomationExecutionProfileService profiles,
                                        ApiAutomationScheduleService schedules, ApiAutomationTransferRepository receipts,
                                        ProjectEnvironmentRepository environments, FileObjectService files,
                                        FileTransferStorage fileStorage,
                                        AuditService audit, ObjectMapper json) {
        this.access = access; this.scenarioRepository = scenarioRepository; this.reusableRepository = reusableRepository;
        this.dataSetRepository = dataSetRepository; this.profileRepository = profileRepository;
        this.scheduleRepository = scheduleRepository; this.scenarios = scenarios; this.reusableSteps = reusableSteps;
        this.dataSets = dataSets; this.profiles = profiles; this.schedules = schedules; this.receipts = receipts;
        this.environments = environments;
        this.files = files; this.fileStorage = fileStorage;
        this.audit = audit; this.json = json;
    }

    public Map<String, Object> capabilities(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectParameterWrite(principal, projectId, WRITERS);
        return Map.of("version", 1, "scope", "API_AUTOMATION", "maxBytes", MAX_BYTES,
                "includes", List.of("scenarios", "reusableSteps", "dataSets", "executionProfiles", "schedules", "dataSetSourceFiles"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectParameterWrite(principal, projectId, WRITERS);
        List<Reusable> reusable = new ArrayList<>();
        for (var item : reusableRepository.findByProject(projectId)) {
            List<ApiAutomationReusableStepRevisionSnapshot> revisions = new ArrayList<>();
            for (int version = 1; version <= item.version(); version++) {
                revisions.add(reusableRepository.findRevisionSnapshot(projectId, item.id(), version)
                        .orElseThrow(() -> invalid("公共步骤缺少历史版本，请先修复本地数据")));
            }
            reusable.add(new Reusable(item.id(), revisions));
        }
        List<Scenario> scenarioItems = new ArrayList<>();
        for (var item : scenarioRepository.findByProject(projectId)) {
            List<ApiAutomationScenarioRevisionSnapshot> revisions = new ArrayList<>();
            for (int version = 1; version <= item.version(); version++) {
                revisions.add(scenarioRepository.findRevisionSnapshot(projectId, item.id(), version)
                        .orElseThrow(() -> invalid("场景缺少历史版本，请先修复本地数据")));
            }
            scenarioItems.add(new Scenario(item.id(), revisions));
        }
        List<DataSet> dataSetItems = new ArrayList<>();
        for (var item : dataSetRepository.findByProject(projectId)) {
            var content = dataSetRepository.findContent(projectId, item.id())
                    .orElseThrow(() -> invalid("数据集内容不存在"));
            SourceFile sourceFile = null;
            if (item.sourceFile() != null) {
                var metadata = item.sourceFile();
                if (metadata.sizeBytes() < 1 || metadata.sizeBytes() > 512 * 1024) throw invalid("数据集来源文件大小无效");
                try (var stream = files.download(principal, projectId, metadata.id())) {
                    byte[] bytes = stream.readNBytes((int) metadata.sizeBytes() + 1);
                    if (bytes.length != metadata.sizeBytes()) throw invalid("数据集来源文件大小不一致");
                    sourceFile = new SourceFile(metadata.id(), metadata.originalName(), metadata.contentType(),
                            bytes.length, sha256(bytes), bytes);
                } catch (IOException error) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "数据集来源文件读取失败", error); }
            }
            dataSetItems.add(new DataSet(item.id(), item.name(), item.sourceFormat(), item.columns(), content.rows(), sourceFile));
        }
        List<Profile> profileItems = new ArrayList<>();
        for (var item : profileRepository.findByProject(projectId)) {
            var detail = profileRepository.findDetail(projectId, item.id())
                    .orElseThrow(() -> invalid("运行配置集不存在"));
            profileItems.add(new Profile(item.id(), detail.name(), detail.environmentCode(), detail.variables(), detail.version()));
        }
        List<Schedule> scheduleItems = scheduleRepository.findByProject(projectId).stream()
                .map(item -> new Schedule(item.id(), item.scenarioId(), item.executionProfileId(), item.name(),
                        item.cronExpression(), item.timezone(), item.timeoutSeconds(), item.maxConsecutiveFailures(),
                        item.enabled())).toList();
        Snapshot snapshot = new Snapshot(1, projectId, reusable, scenarioItems, dataSetItems, profileItems, scheduleItems);
        validate(snapshot);
        encoded(snapshot);
        return snapshot;
    }

    public ImportRequest decode(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BYTES) throw invalid("接口自动化迁移请求不能为空或超过 32 MiB");
        try {
            ImportRequest request = json.readerFor(ImportRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(body);
            if (request == null || request.snapshot() == null) throw invalid("迁移资料不能为空");
            return request;
        } catch (java.io.IOException error) {
            throw invalid("迁移请求 JSON 格式不合法或包含不支持的字段");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt transfer(AuthenticatedPrincipal principal, String projectId, ImportRequest request) {
        var project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        if (request == null || request.requestKey() == null) throw invalid("requestKey 必须是 UUID");
        String requestKey;
        if (!request.requestKey().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw invalid("requestKey 必须是 UUID");
        try { requestKey = UUID.fromString(request.requestKey()).toString(); }
        catch (IllegalArgumentException error) { throw invalid("requestKey 必须是 UUID"); }
        if (!principal.userId().equals(request.expectedUserId())) throw conflict("登录账号已变化，请重新预览迁移");
        Snapshot source = request.snapshot();
        byte[] payload = encoded(source);
        String hash = sha256(payload);
        receipts.lockProject(projectId);
        var previous = receipts.receipt(projectId, principal.userId(), requestKey);
        if (previous.isPresent()) {
            if (!previous.get().hash().equals(hash)) throw conflict("该 requestKey 已用于其他迁移资料");
            try { return json.readValue(previous.get().result(), Receipt.class); }
            catch (JsonProcessingException error) { throw new IllegalStateException("接口自动化迁移回执损坏", error); }
        }
        validate(source);
        if (source.reusableSteps().isEmpty() && source.scenarios().isEmpty() && source.dataSets().isEmpty()
                && source.executionProfiles().isEmpty() && source.schedules().isEmpty()) throw invalid("没有可迁移的接口自动化资料");
        Set<String> environmentCodes = new HashSet<>();
        for (var item : source.scenarios()) for (var revision : item.revisions()) environmentCodes.add(revision.environmentCode());
        for (var item : source.executionProfiles()) environmentCodes.add(item.environmentCode());
        for (String code : environmentCodes) {
            var environment = environments.findByCode(projectId, code);
            if (environment.isEmpty() || !environment.get().enabled())
                throw conflict("目标项目缺少已启用的运行环境：" + code);
        }
        try {
            Map<String, String> sourceFileIds = new HashMap<>();
            for (var item : source.dataSets()) {
                String sourceFileId = null;
                if (item.sourceFile() != null) {
                    var file = item.sourceFile();
                    sourceFileId = sourceFileIds.computeIfAbsent(file.id(), ignored ->
                            fileStorage.copy(principal, projectId, file.name(), file.type(), file.content()).id());
                }
                dataSets.createTransferred(principal, projectId, item.name(), item.sourceFormat(), item.columns(),
                        item.rows(), sourceFileId);
            }
            Map<String, String> profileIds = new HashMap<>();
            for (var item : source.executionProfiles()) {
                var created = profiles.create(principal, projectId, new ApiAutomationExecutionProfileService.ProfileInput(
                        item.name(), item.environmentCode(), item.variables(), null));
                profileIds.put(item.id(), created.id());
                if (item.version() > 1) receipts.restoreProfileVersion(projectId, created.id(), item.version());
            }
            Map<String, String> reusableIds = new HashMap<>();
            for (var item : source.reusableSteps()) {
                var first = item.revisions().getFirst();
                var created = reusableSteps.create(principal, projectId, new ApiAutomationReusableStepService.ReusableStepInput(
                        first.name(), first.description(), first.steps(), null));
                reusableIds.put(item.id(), created.id());
                for (int index = 1; index < item.revisions().size(); index++) {
                    var revision = item.revisions().get(index);
                    reusableSteps.update(principal, projectId, created.id(),
                            new ApiAutomationReusableStepService.ReusableStepInput(revision.name(), revision.description(),
                                    revision.steps(), index));
                }
            }
            Map<String, String> scenarioIds = new HashMap<>();
            for (var item : orderedScenarios(source.scenarios())) {
                for (int index = 0; index < item.revisions().size(); index++) {
                    var revision = item.revisions().get(index);
                    var input = new ApiAutomationScenarioService.ScenarioInput(revision.name(), revision.description(),
                            revision.environmentCode(), revision.failureStrategy(),
                            remapSteps(revision.steps(), reusableIds, scenarioIds), revision.variables(),
                            revision.preRequestScript(), revision.postResponseScript(), index == 0 ? null : index);
                    if (index == 0) {
                        var created = scenarios.create(principal, projectId, input);
                        scenarioIds.put(item.id(), created.id());
                    } else scenarios.update(principal, projectId, scenarioIds.get(item.id()), input);
                }
            }
            for (var item : source.schedules()) {
                schedules.create(principal, projectId, new ApiAutomationScheduleService.ScheduleInput(
                        scenarioIds.get(item.scenarioId()), item.executionProfileId() == null ? null : profileIds.get(item.executionProfileId()),
                        item.name(), item.cronExpression(), item.timezone(), item.timeoutSeconds(),
                        item.maxConsecutiveFailures(), false));
            }
            Receipt receipt = new Receipt(UlidCreator.getUlid().toString(), source.reusableSteps().size(),
                    source.scenarios().size(), source.dataSets().size(), source.executionProfiles().size(),
                    source.schedules().size(), sourceFileIds.size());
            receipts.saveReceipt(receipt.id(), project.organizationId(), projectId, principal.userId(), requestKey,
                    hash, new String(encoded(receipt), StandardCharsets.UTF_8));
            audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_TRANSFERRED", "API_AUTOMATION_TRANSFER",
                    receipt.id(), Map.of("scenarios", receipt.scenarios(), "reusableSteps", receipt.reusableSteps(),
                            "dataSets", receipt.dataSets(), "executionProfiles", receipt.executionProfiles(),
                            "disabledSchedules", receipt.schedules(), "dataSetSourceFiles", receipt.dataSetSourceFiles()));
            return receipt;
        } catch (DataIntegrityViolationException error) {
            throw conflict("迁移资产与目标项目冲突，未写入任何迁移数据");
        }
    }

    private void validate(Snapshot source) {
        if (source == null || source.version() != 1 || !validId(source.sourceProjectId())) throw invalid("迁移协议或来源项目无效");
        bounded(source.reusableSteps(), 100, "公共步骤"); bounded(source.scenarios(), 200, "场景");
        bounded(source.dataSets(), 100, "数据集"); bounded(source.executionProfiles(), 100, "运行配置集");
        bounded(source.schedules(), 200, "计划");
        Set<String> ids = new HashSet<>();
        Map<String, SourceFile> sourceFiles = new HashMap<>();
        long sourceFileBytes = 0;
        for (var item : source.reusableSteps()) {
            unique(ids, item.id()); bounded(item.revisions(), 100, "公共步骤修订");
            if (item.revisions().isEmpty()) throw invalid("公共步骤缺少修订");
            for (var revision : item.revisions()) validateSteps(revision.steps());
        }
        for (var item : source.scenarios()) {
            unique(ids, item.id()); bounded(item.revisions(), 100, "场景修订");
            if (item.revisions().isEmpty()) throw invalid("场景缺少修订");
            for (var revision : item.revisions()) validateSteps(revision.steps());
        }
        for (var item : source.dataSets()) {
            unique(ids, item.id());
            if (item.columns() == null || item.rows() == null) throw invalid("数据集内容不完整");
            if (item.sourceFile() != null) {
                var file = item.sourceFile();
                if (!validId(file.id()) || file.content() == null || file.size() < 1 || file.size() > 512 * 1024
                        || file.content().length != file.size() || !sha256(file.content()).equals(file.sha256())
                        || file.name() == null || file.type() == null) throw invalid("数据集来源文件大小或 SHA-256 校验失败");
                SourceFile previous = sourceFiles.putIfAbsent(file.id(), file);
                if (previous != null && (!previous.sha256().equals(file.sha256())
                        || !previous.name().equals(file.name()) || !previous.type().equals(file.type())))
                    throw invalid("同一来源文件的内容或信息不一致");
                if (previous == null) sourceFileBytes += file.size();
                if (sourceFileBytes > 20L * 1024 * 1024) throw invalid("数据集来源文件合计不能超过 20 MiB");
            }
        }
        for (var item : source.executionProfiles()) {
            unique(ids, item.id());
            if (item.version() < 1 || item.version() > 10000) throw invalid("运行配置集版本无效");
            if (item.variables() == null) throw invalid("运行配置集变量无效");
        }
        for (var item : source.schedules()) {
            unique(ids, item.id());
            if (source.scenarios().stream().noneMatch(s -> s.id().equals(item.scenarioId()))) throw invalid("计划引用的场景不在迁移资料中");
            if (item.executionProfileId() != null && source.executionProfiles().stream().noneMatch(p -> p.id().equals(item.executionProfileId())))
                throw invalid("计划引用的运行配置集不在迁移资料中");
        }
        Set<String> reusableIds = new HashSet<>();
        for (var item : source.reusableSteps()) reusableIds.add(item.id());
        Set<String> scenarioIds = new HashSet<>();
        for (var item : source.scenarios()) scenarioIds.add(item.id());
        for (var item : source.scenarios()) for (var revision : item.revisions()) for (var step : revision.steps()) {
            if ("REUSABLE_STEP".equals(step.type()) && !reusableIds.contains(step.config().get("reusableStepId")))
                throw invalid("场景引用的公共步骤不在迁移资料中");
            if ("SUB_SCENARIO".equals(step.type()) && !scenarioIds.contains(step.config().get("scenarioId")))
                throw invalid("子场景不在迁移资料中");
        }
        orderedScenarios(source.scenarios());
    }

    private void validateSteps(List<AutomationStep> steps) {
        if (steps == null || steps.isEmpty() || steps.size() > 300 || steps.stream().anyMatch(java.util.Objects::isNull))
            throw invalid("迁移步骤数量或内容无效");
    }

    private List<Scenario> orderedScenarios(List<Scenario> source) {
        Map<String, Scenario> byId = new HashMap<>();
        for (var item : source) byId.put(item.id(), item);
        List<Scenario> ordered = new ArrayList<>();
        Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        for (var item : source) visit(item, byId, visiting, visited, ordered);
        return ordered;
    }

    private void visit(Scenario item, Map<String, Scenario> byId, Set<String> visiting, Set<String> visited,
                       List<Scenario> ordered) {
        if (visited.contains(item.id())) return;
        if (!visiting.add(item.id())) throw invalid("子场景引用存在循环");
        for (var revision : item.revisions()) for (var step : revision.steps()) if ("SUB_SCENARIO".equals(step.type())) {
            Scenario dependency = byId.get(step.config().get("scenarioId"));
            if (dependency == null) throw invalid("子场景不在迁移资料中");
            visit(dependency, byId, visiting, visited, ordered);
        }
        visiting.remove(item.id()); visited.add(item.id()); ordered.add(item);
    }

    private List<AutomationStep> remapSteps(List<AutomationStep> steps, Map<String, String> reusableIds,
                                             Map<String, String> scenarioIds) {
        List<AutomationStep> result = new ArrayList<>();
        for (var step : steps) {
            Map<String, Object> config = new LinkedHashMap<>(step.config());
            if ("REUSABLE_STEP".equals(step.type())) config.put("reusableStepId", requiredMapping(reusableIds, config.get("reusableStepId")));
            if ("SUB_SCENARIO".equals(step.type())) config.put("scenarioId", requiredMapping(scenarioIds, config.get("scenarioId")));
            result.add(new AutomationStep(step.id(), step.type(), step.name(), config));
        }
        return result;
    }

    private String requiredMapping(Map<String, String> ids, Object sourceId) {
        String mapped = ids.get(sourceId);
        if (mapped == null) throw invalid("迁移引用无法映射到目标项目");
        return mapped;
    }

    private void unique(Set<String> ids, String id) {
        if (!validId(id) || !ids.add(id)) throw invalid("迁移资产标识无效或重复");
    }

    private boolean validId(String id) { return id != null && id.matches("[A-Z0-9]{26}"); }

    private void bounded(List<?> items, int limit, String label) {
        if (items == null || items.size() > limit || items.stream().anyMatch(java.util.Objects::isNull))
            throw invalid(label + "数量无效或超出上限");
    }

    private byte[] encoded(Object source) {
        try {
            byte[] bytes = json.writeValueAsBytes(source);
            if (bytes.length > MAX_BYTES) throw invalid("接口自动化迁移资料不能超过 32 MiB");
            return bytes;
        } catch (JsonProcessingException error) { throw invalid("接口自动化迁移资料无法序列化"); }
    }

    private String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record Snapshot(int version, String sourceProjectId, List<Reusable> reusableSteps, List<Scenario> scenarios,
                           List<DataSet> dataSets, List<Profile> executionProfiles, List<Schedule> schedules) { }
    public record Reusable(String id, List<ApiAutomationReusableStepRevisionSnapshot> revisions) { }
    public record Scenario(String id, List<ApiAutomationScenarioRevisionSnapshot> revisions) { }
    public record DataSet(String id, String name, String sourceFormat, List<String> columns,
                          List<Map<String, String>> rows, SourceFile sourceFile) { }
    public record SourceFile(String id, String name, String type, long size, String sha256, byte[] content) { }
    public record Profile(String id, String name, String environmentCode, Map<String, String> variables, int version) { }
    public record Schedule(String id, String scenarioId, String executionProfileId, String name, String cronExpression,
                           String timezone, int timeoutSeconds, int maxConsecutiveFailures, boolean wasEnabled) { }
    public record ImportRequest(String requestKey, String expectedUserId, Snapshot snapshot) { }
    public record Receipt(String id, int reusableSteps, int scenarios, int dataSets, int executionProfiles, int schedules,
                          int dataSetSourceFiles) { }
}
