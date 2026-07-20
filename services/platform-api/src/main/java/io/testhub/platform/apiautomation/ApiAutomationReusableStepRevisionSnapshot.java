package io.testhub.platform.apiautomation;

import java.util.List;

/** Immutable primitive-step source used by a scenario reference. */
public record ApiAutomationReusableStepRevisionSnapshot(String name, String description,
                                                        List<AutomationStep> steps) {
    public ApiAutomationReusableStepRevisionSnapshot {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        steps = List.copyOf(steps == null ? List.of() : steps);
    }
}
