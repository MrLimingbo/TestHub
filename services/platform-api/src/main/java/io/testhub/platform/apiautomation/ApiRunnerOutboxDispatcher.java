package io.testhub.platform.apiautomation;

import java.util.List;
import java.util.Map;
import io.testhub.platform.audit.AuditService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ApiRunnerOutboxDispatcher {
    private static final int BATCH_SIZE = 20;
    private final ApiAutomationExecutionRepository executions;
    private final ApiRunnerTaskPublisher publisher;
    private final AuditService audit;
    private final ApiAutomationExecutionService executionService;

    public ApiRunnerOutboxDispatcher(ApiAutomationExecutionRepository executions, ApiRunnerTaskPublisher publisher,
                                     AuditService audit, ApiAutomationExecutionService executionService) {
        this.executions = executions;
        this.publisher = publisher;
        this.audit = audit;
        this.executionService = executionService;
    }

    @Scheduled(fixedDelayString = "${testhub.runner.outbox.dispatch-interval-ms:500}")
    public void dispatchPending() {
        executions.releaseStaleOutboxClaims();
        finalizeExhaustedDeliveries();
        List<ApiRunnerOutboxMessage> messages = executions.findDispatchable(BATCH_SIZE);
        for (ApiRunnerOutboxMessage message : messages) {
            if (executions.claimOutbox(message.id()) == 0) continue;
            try {
                publisher.publish(message.streamKey(), message.payload());
                executions.markOutboxSent(message.id());
            } catch (Exception exception) {
                executions.rescheduleOutbox(message.id(), safeMessage(exception));
            }
        }
        finalizeExhaustedDeliveries();
    }

    private String safeMessage(Exception exception) {
        return "Runner Stream 投递失败：" + exception.getClass().getSimpleName();
    }

    private void finalizeExhaustedDeliveries() {
        for (ApiAutomationExecution execution : executions.finalizeExhaustedDeliveries()) {
            executionService.recordDeliveryFailure(execution);
            audit.recordSystem(execution.organizationId(), execution.projectId(), "API_AUTOMATION_EXECUTION_DELIVERY_FAILED",
                    "API_AUTOMATION_EXECUTION", execution.id(), Map.of("taskId", execution.taskId(),
                            "statusCode", execution.statusCode()));
        }
    }
}
