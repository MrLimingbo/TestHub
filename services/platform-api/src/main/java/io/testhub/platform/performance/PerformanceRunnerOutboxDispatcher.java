package io.testhub.platform.performance;

import io.testhub.platform.audit.AuditService;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PerformanceRunnerOutboxDispatcher {
    private static final int BATCH_SIZE = 20;
    private final PerformanceTestExecutionRepository executions;
    private final PerformanceRunnerTaskPublisher publisher;
    private final AuditService audit;

    public PerformanceRunnerOutboxDispatcher(PerformanceTestExecutionRepository executions,
                                             PerformanceRunnerTaskPublisher publisher, AuditService audit) {
        this.executions = executions;
        this.publisher = publisher;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${testhub.performance-runner.outbox.dispatch-interval-ms:500}")
    public void dispatchPending() {
        executions.releaseStaleOutboxClaims();
        finalizeExhaustedDeliveries();
        List<PerformanceRunnerOutboxMessage> messages = executions.findDispatchable(BATCH_SIZE);
        for (PerformanceRunnerOutboxMessage message : messages) {
            if (executions.claimOutbox(message.id()) == 0) continue;
            try {
                publisher.publish(message.streamKey(), message.payload());
                executions.markOutboxSent(message.id());
            } catch (Exception exception) {
                executions.rescheduleOutbox(message.id(), "JMeter Runner Stream 投递失败：" + exception.getClass().getSimpleName());
            }
        }
        finalizeExhaustedDeliveries();
    }

    private void finalizeExhaustedDeliveries() {
        for (PerformanceTestExecution execution : executions.finalizeExhaustedDeliveries()) {
            audit.recordSystem(execution.organizationId(), execution.projectId(), "PERFORMANCE_TEST_EXECUTION_DELIVERY_FAILED",
                    "PERFORMANCE_TEST_EXECUTION", execution.id(), Map.of("taskId", execution.taskId(),
                            "statusCode", execution.statusCode()));
        }
    }
}
