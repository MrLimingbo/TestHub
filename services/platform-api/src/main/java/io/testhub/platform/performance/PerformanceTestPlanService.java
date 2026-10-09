package io.testhub.platform.performance;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.math.BigDecimal;
import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PerformanceTestPlanService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> PLAN_STATUSES = Set.of("DRAFT", "READY", "ARCHIVED");
    private static final Set<String> LOAD_MODELS = Set.of("STEADY", "RAMP_UP", "STEPPED_RAMP");
    private static final Set<String> REQUEST_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");
    private static final Set<String> RESTRICTED_HEADERS = Set.of("authorization", "cookie", "host", "content-length",
            "connection", "transfer-encoding", "proxy-authorization", "proxy-connection", "upgrade");
    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]{0,63})}");
    private static final Pattern TEMPLATE_EXPRESSION = Pattern.compile("\\$\\{([^}]*)}");
    private static final Pattern DATA_SET_VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final String TEMPLATE_URL_SENTINEL = "__testhub_data_variable__";
    private final PerformanceTestPlanRepository plans;
    private final AccessService access;
    private final AuditService audit;

    public PerformanceTestPlanService(PerformanceTestPlanRepository plans, AccessService access, AuditService audit) {
        this.plans = plans;
        this.access = access;
        this.audit = audit;
    }

    public List<PerformanceTestPlan> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return plans.findByProject(projectId);
    }

    public PerformanceTestPlanDetail detail(AuthenticatedPrincipal principal, String projectId, String planId) {
        access.requireProjectRead(principal, projectId);
        return require(projectId, planId);
    }

    @Transactional
    public PerformanceTestPlanDetail create(AuthenticatedPrincipal principal, String projectId, PlanInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        plans.lockProject(projectId);
        if (plans.countByProject(projectId) >= 100) throw rejected("每个项目最多创建 100 个压测计划");
        PlanPayload payload = payload(input);
        String planId = UlidCreator.getUlid().toString();
        try {
            plans.insertPlan(planId, project.organizationId(), projectId, payload.name(), payload.description(), payload.status(),
                    principal.userId());
            insertThreadGroups(planId, project.organizationId(), projectId, payload.threadGroups());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前项目已存在同名压测计划或线程组");
        }
        PerformanceTestPlanDetail result = require(projectId, planId);
        audit.record(principal, project.organizationId(), projectId, "PERFORMANCE_TEST_PLAN_CREATED", "PERFORMANCE_TEST_PLAN",
                planId, auditDetails(result));
        return result;
    }

    @Transactional
    public PerformanceTestPlanDetail update(AuthenticatedPrincipal principal, String projectId, String planId,
                                            PlanInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        plans.lockProject(projectId);
        if (input == null || input.expectedVersion() == null || input.expectedVersion() < 1) {
            throw rejected("保存压测计划时必须提供当前版本号");
        }
        PlanPayload payload = payload(input);
        int updated;
        try {
            updated = plans.updatePlan(projectId, planId, input.expectedVersion(), payload.name(), payload.description(),
                    payload.status());
            if (updated > 0) {
                plans.deleteThreadGroups(projectId, planId);
                insertThreadGroups(planId, project.organizationId(), projectId, payload.threadGroups());
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前计划内已存在同名线程组，或项目已存在同名压测计划");
        }
        if (updated == 0) throw conflict("压测计划已被其他成员更新，请刷新后再保存");
        PerformanceTestPlanDetail result = require(projectId, planId);
        audit.record(principal, project.organizationId(), projectId, "PERFORMANCE_TEST_PLAN_UPDATED", "PERFORMANCE_TEST_PLAN",
                planId, auditDetails(result));
        return result;
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, String projectId, String planId) {
        ProjectSummary project = writeAccess(principal, projectId);
        plans.lockProject(projectId);
        PerformanceTestPlanDetail existing = require(projectId, planId);
        if (plans.deletePlan(projectId, planId) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "压测计划不存在");
        audit.record(principal, project.organizationId(), projectId, "PERFORMANCE_TEST_PLAN_DELETED", "PERFORMANCE_TEST_PLAN",
                planId, auditDetails(existing));
    }

    private void insertThreadGroups(String planId, String organizationId, String projectId, List<ThreadGroupPayload> groups) {
        for (int index = 0; index < groups.size(); index++) {
            plans.insertThreadGroup(UlidCreator.getUlid().toString(), planId, organizationId, projectId, groups.get(index), index);
        }
    }

    private PlanPayload payload(PlanInput input) {
        if (input == null) throw rejected("压测计划参数不能为空");
        List<ThreadGroupPayload> groups = groups(input.threadGroups());
        return new PlanPayload(required(input.name(), "压测计划名称", 160), text(input.description(), 2_000),
                normalized(input.status(), PLAN_STATUSES, "压测计划状态", "DRAFT"), groups);
    }

    PlanInput normalizeDefinition(String name, String description, List<ThreadGroupInput> groups) {
        PlanPayload value = payload(new PlanInput(name, description, "DRAFT", groups, null));
        return new PlanInput(value.name(), value.description(), "DRAFT", value.threadGroups().stream().map(group ->
                new ThreadGroupInput(group.name(), group.targetUrl(), group.loadModel(), group.threadCount(),
                        group.rampUpSeconds(), group.stepCount(), group.stepIntervalSeconds(), group.durationSeconds(),
                        group.targetTps(), group.p95ThresholdMillis(), group.errorThresholdPercent(), group.requestMethod(),
                        group.requestHeaders(), group.queryParameters(), group.requestBody(), group.expectedStatusCode(),
                        group.responseContains(), group.dataSetCsv(), group.rendezvousUsers())).toList(), null);
    }

    private List<ThreadGroupPayload> groups(List<ThreadGroupInput> source) {
        if (source == null || source.isEmpty()) throw rejected("压测计划至少需要一个线程组");
        if (source.size() > 10) throw rejected("每个压测计划最多支持 10 个线程组");
        Set<String> names = new HashSet<>();
        List<ThreadGroupPayload> groups = source.stream().map(this::group).toList();
        for (ThreadGroupPayload group : groups) {
            if (!names.add(group.name().toLowerCase(Locale.ROOT))) throw rejected("线程组名称不能重复");
        }
        return groups;
    }

    private ThreadGroupPayload group(ThreadGroupInput input) {
        if (input == null) throw rejected("线程组参数不能为空");
        int threadCount = range(input.threadCount(), 1, 100_000, "并发线程数");
        String loadModel = normalized(input.loadModel(), LOAD_MODELS, "负载模型", "RAMP_UP");
        int rampUpSeconds = range(input.rampUpSeconds(), 0, 86_400, "加压时长");
        int durationSeconds = range(input.durationSeconds(), 10, 86_400, "持续时长");
        SteppedRampPayload steppedRamp = steppedRamp(loadModel, threadCount, rampUpSeconds, durationSeconds,
                input.stepCount(), input.stepIntervalSeconds(), input.rendezvousUsers());
        String requestMethod = requestMethod(input.requestMethod());
        DataSetPayload dataSet = dataSet(input.dataSetCsv());
        Map<String, String> requestHeaders = requestHeaders(input.requestHeaders());
        Map<String, String> queryParameters = queryParameters(input.queryParameters());
        String requestBody = requestBody(requestMethod, input.requestBody());
        String responseContains = nullableText(input.responseContains(), "响应包含断言", 1_000);
        Integer targetTps = nullableRange(input.targetTps(), 1, 1_000_000, "目标 TPS");
        if ("STEPPED_RAMP".equals(loadModel) && targetTps != null && targetTps < steppedRamp.stepCount()) {
            throw rejected("阶梯加压的目标 TPS 不能小于阶段数，避免任一阶段失去限速");
        }
        templates(input.targetUrl(), "目标 URL", dataSet.variables());
        requestHeaders.forEach((name, value) -> templates(value, "请求头 " + name, dataSet.variables()));
        queryParameters.forEach((name, value) -> templates(value, "查询参数 " + name, dataSet.variables()));
        templates(requestBody, "请求正文", dataSet.variables());
        templates(responseContains, "响应包含断言", dataSet.variables());
        return new ThreadGroupPayload(required(input.name(), "线程组名称", 160), targetUrl(input.targetUrl()),
                loadModel, threadCount, rampUpSeconds, steppedRamp.stepCount(), steppedRamp.stepIntervalSeconds(),
                durationSeconds, targetTps,
                nullableRange(input.p95ThresholdMillis(), 1, 600_000, "P95 阈值"), percent(input.errorThresholdPercent()),
                requestMethod, requestHeaders, queryParameters, requestBody,
                nullableRange(input.expectedStatusCode(), 100, 599, "预期响应状态码"),
                responseContains, dataSet.csv(), nullableRange(input.rendezvousUsers(), 2, threadCount, "集合点等待线程数"));
    }

    private SteppedRampPayload steppedRamp(String loadModel, int threadCount, int rampUpSeconds, int durationSeconds,
                                            Integer requestedSteps, Integer requestedInterval, Integer rendezvousUsers) {
        if (!"STEPPED_RAMP".equals(loadModel)) {
            if (requestedSteps != null || requestedInterval != null) throw rejected("仅阶梯加压模型可以配置阶段数和阶梯间隔");
            return new SteppedRampPayload(null, null);
        }
        if (rampUpSeconds != 0) throw rejected("阶梯加压请将加压时长设为 0，并使用阶梯间隔配置启动节奏");
        if (rendezvousUsers != null) throw rejected("阶梯加压暂不支持集合点同步，避免跨阶段等待产生歧义");
        int stepCount = range(requestedSteps, 2, Math.min(threadCount, 20), "阶梯阶段数");
        int stepIntervalSeconds = range(requestedInterval, 1, 86_400, "阶梯间隔");
        long totalDuration = (long) durationSeconds + (long) (stepCount - 1) * stepIntervalSeconds;
        if (totalDuration > 86_400) throw rejected("持续时长与阶梯间隔的总时长不能超过 86400 秒");
        return new SteppedRampPayload(stepCount, stepIntervalSeconds);
    }

    private String targetUrl(String value) {
        String normalized = required(value, "目标 URL", 2_048);
        try {
            URI uri = URI.create(TEMPLATE.matcher(normalized).replaceAll(TEMPLATE_URL_SENTINEL));
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw rejected("目标 URL 必须为不含账号信息的 HTTP 或 HTTPS 地址");
            }
            if (uri.getRawAuthority() != null && uri.getRawAuthority().contains(TEMPLATE_URL_SENTINEL)) {
                throw rejected("目标 URL 仅支持在路径中使用数据变量");
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw rejected("目标 URL 不能包含查询参数或片段，请使用结构化查询参数配置");
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw rejected("目标 URL 格式不合法");
        }
    }

    private Integer nullableRange(Integer value, int minimum, int maximum, String label) {
        return value == null ? null : range(value, minimum, maximum, label);
    }

    private String requestMethod(String value) {
        return normalized(value, REQUEST_METHODS, "请求方法", "GET");
    }

    private Map<String, String> requestHeaders(Map<String, String> source) {
        if (source == null || source.isEmpty()) return Map.of();
        if (source.size() > 16) throw rejected("请求头最多支持 16 项");
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> normalizedNames = new HashSet<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String name = required(entry.getKey(), "请求头名称", 128);
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) throw rejected("请求头名称格式不合法");
            String normalizedName = name.toLowerCase(Locale.ROOT);
            if (!normalizedNames.add(normalizedName)) throw rejected("请求头名称不能重复");
            if (RESTRICTED_HEADERS.contains(normalizedName)) {
                throw rejected("请求头 " + name + " 不允许直接配置；请使用后续的项目密钥与认证能力");
            }
            String headerValue = entry.getValue() == null ? "" : entry.getValue().trim();
            if (headerValue.length() > 1_024 || headerValue.indexOf('\r') >= 0 || headerValue.indexOf('\n') >= 0) {
                throw rejected("请求头值不合法或过长");
            }
            result.put(name, headerValue);
        }
        return Map.copyOf(result);
    }

    private Map<String, String> queryParameters(Map<String, String> source) {
        if (source == null || source.isEmpty()) return Map.of();
        if (source.size() > 16) throw rejected("查询参数最多支持 16 项");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String name = required(entry.getKey(), "查询参数名称", 128);
            if (!name.matches("[A-Za-z0-9._~-]+")) throw rejected("查询参数名称格式不合法");
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (value.length() > 1_024 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw rejected("查询参数值不合法或过长");
            }
            result.put(name, value);
        }
        return Map.copyOf(result);
    }

    private String requestBody(String requestMethod, String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 65_536) throw rejected("请求正文不能超过 65536 个字符");
        if (Set.of("GET", "HEAD").contains(requestMethod)) throw rejected("GET 和 HEAD 请求不能配置请求正文");
        return value;
    }

    private DataSetPayload dataSet(String source) {
        if (source == null || source.isBlank()) return new DataSetPayload(null, Set.of());
        if (source.length() > 65_536) throw rejected("内联 CSV 数据集不能超过 65536 个字符");
        String csv = source.replace("\r\n", "\n").replace('\r', '\n');
        List<String> rows = csv.lines().toList();
        if (rows.size() < 2 || rows.size() > 501) throw rejected("内联 CSV 数据集需要 1 到 500 行数据");
        List<String> headers = csvLine(rows.getFirst(), 1);
        if (headers.isEmpty() || headers.size() > 20) throw rejected("CSV 数据集最多支持 20 个变量列");
        Set<String> variables = new HashSet<>();
        for (String header : headers) {
            if (!DATA_SET_VARIABLE.matcher(header).matches() || !variables.add(header)) {
                throw rejected("CSV 首行必须是唯一的变量名（字母、数字和下划线）");
            }
        }
        for (int index = 1; index < rows.size(); index++) {
            List<String> cells = csvLine(rows.get(index), index + 1);
            if (cells.size() != headers.size()) throw rejected("CSV 第 " + (index + 1) + " 行的列数与首行不一致");
            if (cells.stream().anyMatch(value -> value.length() > 4_096)) {
                throw rejected("CSV 单元格不能超过 4096 个字符");
            }
        }
        return new DataSetPayload(csv, Set.copyOf(variables));
    }

    private List<String> csvLine(String source, int lineNumber) {
        List<String> cells = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        boolean quoteClosed = false;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < source.length() && source.charAt(index + 1) == '"') {
                    value.append(character);
                    index++;
                } else if (quoted) {
                    quoted = false;
                    quoteClosed = true;
                } else if (value.isEmpty()) {
                    quoted = true;
                } else {
                    throw rejected("CSV 第 " + lineNumber + " 行的引号格式不合法");
                }
            } else if (character == ',' && !quoted) {
                cells.add(value.toString());
                value.setLength(0);
                quoteClosed = false;
            } else {
                if (quoteClosed) throw rejected("CSV 第 " + lineNumber + " 行的引号格式不合法");
                value.append(character);
            }
        }
        if (quoted) throw rejected("CSV 第 " + lineNumber + " 行存在未闭合引号");
        cells.add(value.toString());
        return cells;
    }

    private void templates(String value, String label, Set<String> variables) {
        if (value == null || !value.contains("${")) return;
        Matcher matcher = TEMPLATE_EXPRESSION.matcher(value);
        int count = 0;
        while (matcher.find()) {
            count++;
            String variable = matcher.group(1);
            if (!DATA_SET_VARIABLE.matcher(variable).matches() || !variables.contains(variable)) {
                throw rejected(label + " 只能引用当前 CSV 数据集已声明的变量");
            }
        }
        if (count == 0 || TEMPLATE_EXPRESSION.matcher(value).replaceAll("").contains("${")) {
            throw rejected(label + " 包含不合法的数据变量表达式");
        }
    }

    private int range(Integer value, int minimum, int maximum, String label) {
        if (value == null || value < minimum || value > maximum) {
            throw rejected(label + "应在 " + minimum + " 到 " + maximum + " 之间");
        }
        return value;
    }

    private BigDecimal percent(BigDecimal value) {
        if (value == null) return null;
        if (value.scale() > 2 || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw rejected("错误率阈值应在 0 到 100 之间，最多保留两位小数");
        }
        return value;
    }

    private String normalized(String value, Set<String> allowed, String label, String fallback) {
        String result = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(result)) throw rejected(label + "不受支持");
        return result;
    }

    private String required(String value, String label, int maximum) {
        if (value == null || value.isBlank()) throw rejected(label + "不能为空");
        String result = value.trim();
        if (result.length() > maximum) throw rejected(label + "不能超过 " + maximum + " 个字符");
        return result;
    }

    private String text(String value, int maximum) {
        String result = value == null ? "" : value.trim();
        if (result.length() > maximum) throw rejected("压测计划说明不能超过 " + maximum + " 个字符");
        return result;
    }

    private String nullableText(String value, String label, int maximum) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > maximum) throw rejected(label + "不能超过 " + maximum + " 个字符");
        return result;
    }

    private PerformanceTestPlanDetail require(String projectId, String planId) {
        return plans.findDetail(projectId, planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "压测计划不存在"));
    }

    private ProjectSummary writeAccess(AuthenticatedPrincipal principal, String projectId) {
        return access.requireProjectParameterWrite(principal, projectId, WRITERS);
    }

    private Map<String, Object> auditDetails(PerformanceTestPlanDetail plan) {
        return Map.of("name", plan.name(), "status", plan.status(), "version", plan.version(),
                "threadGroupCount", plan.threadGroups().size());
    }

    private ResponseStatusException rejected(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record PlanInput(String name, String description, String status, List<ThreadGroupInput> threadGroups,
                            Integer expectedVersion) {
    }

    public record ThreadGroupInput(String name, String targetUrl, String loadModel, Integer threadCount,
                                   Integer rampUpSeconds, Integer stepCount, Integer stepIntervalSeconds, Integer durationSeconds, Integer targetTps,
                                   Integer p95ThresholdMillis, BigDecimal errorThresholdPercent, String requestMethod,
                                   Map<String, String> requestHeaders, Map<String, String> queryParameters, String requestBody, Integer expectedStatusCode,
                                   String responseContains, String dataSetCsv, Integer rendezvousUsers) {
    }

    record ThreadGroupPayload(String name, String targetUrl, String loadModel, int threadCount, int rampUpSeconds,
                              Integer stepCount, Integer stepIntervalSeconds, int durationSeconds, Integer targetTps, Integer p95ThresholdMillis,
                              BigDecimal errorThresholdPercent, String requestMethod, Map<String, String> requestHeaders,
                              Map<String, String> queryParameters, String requestBody, Integer expectedStatusCode, String responseContains, String dataSetCsv,
                              Integer rendezvousUsers) {
    }

    private record DataSetPayload(String csv, Set<String> variables) {
    }

    private record SteppedRampPayload(Integer stepCount, Integer stepIntervalSeconds) {
    }

    private record PlanPayload(String name, String description, String status, List<ThreadGroupPayload> threadGroups) {
    }
}
