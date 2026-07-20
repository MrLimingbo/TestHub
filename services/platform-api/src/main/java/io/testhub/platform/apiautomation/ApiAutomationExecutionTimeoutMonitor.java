package io.testhub.platform.apiautomation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ApiAutomationExecutionTimeoutMonitor {
    private final ApiAutomationExecutionService executions;

    public ApiAutomationExecutionTimeoutMonitor(ApiAutomationExecutionService executions) {
        this.executions = executions;
    }

    @Scheduled(fixedDelayString = "${testhub.runner.executions.timeout-sweep-interval-ms:1000}")
    public void expireDueExecutions() {
        executions.expireDueExecutions();
    }
}
