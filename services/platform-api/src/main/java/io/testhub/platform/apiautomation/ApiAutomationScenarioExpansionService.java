package io.testhub.platform.apiautomation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves frozen project-local references before an execution reaches a Runner. Nested references are deliberately
 * rejected, so an execution has a small, deterministic primitive-step graph and cannot recurse across scenarios.
 */
@Service
public class ApiAutomationScenarioExpansionService {
    public static final String REUSABLE_STEP = "REUSABLE_STEP";
    public static final String SUB_SCENARIO = "SUB_SCENARIO";
    private static final Set<String> REFERENCE_TYPES = Set.of(REUSABLE_STEP, SUB_SCENARIO);
    private static final Set<String> PRIMITIVE_TYPES = Set.of("REQUEST", "PARALLEL", "EXTRACT", "ASSERT", "CONDITION", "LOOP", "WAIT");
    private static final int MAX_EXPANDED_STEPS = 300;
    private final ApiAutomationScenarioRepository scenarios;
    private final ApiAutomationReusableStepRepository reusableSteps;

    public ApiAutomationScenarioExpansionService(ApiAutomationScenarioRepository scenarios,
                                                 ApiAutomationReusableStepRepository reusableSteps) {
        this.scenarios = scenarios;
        this.reusableSteps = reusableSteps;
    }

    public boolean isReferenceType(String type) {
        return REFERENCE_TYPES.contains(type);
    }

    /** Validates references at save time, including project scope and source-version existence. */
    public List<AutomationStep> validateCandidate(String projectId, String rootScenarioId, List<AutomationStep> steps) {
        return expand(projectId, rootScenarioId, steps);
    }

    public List<AutomationStep> expand(String projectId, String rootScenarioId, List<AutomationStep> source) {
        List<AutomationStep> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (AutomationStep step : source == null ? List.<AutomationStep>of() : source) {
            if (!isReferenceType(step.type())) {
                add(result, ids, step);
                continue;
            }
            if (REUSABLE_STEP.equals(step.type())) {
                expandReusable(projectId, step, result, ids);
            } else {
                expandSubScenario(projectId, rootScenarioId, step, result, ids);
            }
        }
        validateSequence(result);
        return List.copyOf(result);
    }

    private void expandReusable(String projectId, AutomationStep reference, List<AutomationStep> target, Set<String> ids) {
        requireKeys(reference, Set.of("reusableStepId", "reusableStepVersion"));
        String sourceId = sourceId(reference, "reusableStepId", "公共步骤");
        int sourceVersion = sourceVersion(reference, "reusableStepVersion", "公共步骤");
        ApiAutomationReusableStepRevisionSnapshot source = reusableSteps.findRevisionSnapshot(projectId, sourceId, sourceVersion)
                .orElseThrow(() -> rejected("公共步骤不存在、版本不可用，或不属于当前项目"));
        expandPrimitive(reference, sourceId, sourceVersion, source.name(), source.steps(), target, ids, "公共步骤");
    }

    private void expandSubScenario(String projectId, String rootScenarioId, AutomationStep reference,
                                   List<AutomationStep> target, Set<String> ids) {
        requireKeys(reference, Set.of("scenarioId", "scenarioVersion"));
        String sourceId = sourceId(reference, "scenarioId", "子场景");
        if (sourceId.equals(rootScenarioId)) throw rejected("子场景不能引用自身");
        int sourceVersion = sourceVersion(reference, "scenarioVersion", "子场景");
        ApiAutomationScenarioRevisionSnapshot source = scenarios.findRevisionSnapshot(projectId, sourceId, sourceVersion)
                .orElseThrow(() -> rejected("子场景不存在、版本不可用，或不属于当前项目"));
        if (!source.preRequestScript().isBlank() || !source.postResponseScript().isBlank()) {
            throw rejected("子场景引用不支持携带场景前后置脚本");
        }
        expandPrimitive(reference, sourceId, sourceVersion, source.name(), source.steps(), target, ids, "子场景");
    }

    private void expandPrimitive(AutomationStep reference, String sourceId, int sourceVersion, String sourceName,
                                 List<AutomationStep> source, List<AutomationStep> target, Set<String> ids, String sourceLabel) {
        if (source == null || source.isEmpty()) throw rejected(sourceLabel + "必须至少包含一个基础步骤");
        for (AutomationStep sourceStep : source) {
            if (sourceStep == null || !PRIMITIVE_TYPES.contains(sourceStep.type())) {
                throw rejected(sourceLabel + "只能包含基础步骤，不能嵌套引用");
            }
            String id = generatedId(reference.id(), sourceId, sourceVersion, sourceStep.id());
            String name = reference.name() + " / " + sourceName + " / " + sourceStep.name();
            if (name.length() > 160) name = name.substring(0, 160);
            add(target, ids, new AutomationStep(id, sourceStep.type(), name, sourceStep.config()));
        }
    }

    private void add(List<AutomationStep> target, Set<String> ids, AutomationStep step) {
        if (target.size() >= MAX_EXPANDED_STEPS) throw rejected("公共步骤和子场景展开后最多支持 300 个步骤");
        if (step == null || step.id() == null || !ids.add(step.id())) throw rejected("展开后的步骤标识重复或不合法");
        target.add(step);
    }

    private void requireKeys(AutomationStep step, Set<String> expected) {
        if (step.config() == null || !step.config().keySet().equals(expected)) {
            throw rejected((REUSABLE_STEP.equals(step.type()) ? "公共步骤" : "子场景") + "引用配置不合法");
        }
    }

    private String sourceId(AutomationStep step, String key, String label) {
        Object value = step.config().get(key);
        String id = value instanceof String text ? text.trim() : "";
        if (!id.matches("[0-9A-HJKMNP-TV-Z]{26}")) throw rejected(label + "标识不合法");
        return id;
    }

    private int sourceVersion(AutomationStep step, String key, String label) {
        try {
            Object value = step.config().get(key);
            int version;
            if (value instanceof Number number) {
                double numeric = number.doubleValue();
                if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)
                        || numeric < Integer.MIN_VALUE || numeric > Integer.MAX_VALUE) throw new NumberFormatException();
                version = (int) numeric;
            } else {
                String raw = String.valueOf(value).trim();
                if (!raw.matches("[+]?[0-9]+")) throw new NumberFormatException();
                version = Integer.parseInt(raw);
            }
            if (version < 1) throw new NumberFormatException();
            return version;
        } catch (Exception exception) {
            throw rejected(label + "版本不合法");
        }
    }

    private String generatedId(String referenceId, String sourceId, int sourceVersion, String sourceStepId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String source = referenceId + "|" + sourceId + "|" + sourceVersion + "|" + sourceStepId;
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("ref-");
            for (int index = 0; index < 16; index++) value.append(String.format(Locale.ROOT, "%02x", bytes[index]));
            return value.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成引用步骤标识", exception);
        }
    }

    private void validateSequence(List<AutomationStep> steps) {
        boolean requestSeen = false;
        boolean latestRequestIsSingle = false;
        int expandedRequestCount = 0;
        for (int index = 0; index < steps.size(); index++) {
            AutomationStep step = steps.get(index);
            if ("REQUEST".equals(step.type())) {
                requestSeen = true;
                latestRequestIsSingle = true;
                expandedRequestCount++;
            }
            if ("PARALLEL".equals(step.type())) {
                requestSeen = true;
                latestRequestIsSingle = false;
                expandedRequestCount += parallelRequestCount(step);
            }
            if (("EXTRACT".equals(step.type()) || "ASSERT".equals(step.type())) && !latestRequestIsSingle) {
                throw rejected("数据提取和断言不能直接作用于并行请求组");
            }
            if ("CONDITION".equals(step.type())) {
                if (!requestSeen || !latestRequestIsSingle || index + 1 >= steps.size() || !"REQUEST".equals(steps.get(index + 1).type())) {
                    throw rejected("条件步骤必须位于已有请求之后，并且只控制紧随的一个请求步骤");
                }
                if (index + 2 < steps.size() && "LOOP".equals(steps.get(index + 2).type())) {
                    throw rejected("条件控制的请求不能紧随循环步骤");
                }
            }
            if ("LOOP".equals(step.type())) {
                if (index == 0 || !"REQUEST".equals(steps.get(index - 1).type())) {
                    throw rejected("循环步骤必须紧随一个请求步骤，用于重复该请求");
                }
                Object value = step.config().get("totalRuns");
                int totalRuns = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
                expandedRequestCount += totalRuns - 1;
            }
        }
        if (expandedRequestCount > MAX_EXPANDED_STEPS) throw rejected("循环展开后的请求次数不能超过 300 次");
    }

    private int parallelRequestCount(AutomationStep step) {
        Object requests = step.config().get("requests");
        return requests instanceof List<?> list ? list.size() : 0;
    }

    private ResponseStatusException rejected(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
