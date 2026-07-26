package io.testhub.platform.uiautomation;

import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UiAutomationRunnerOutboxDispatcher {
    private final UiAutomationRepository repository;
    private final UiAutomationRunnerTaskPublisher publisher;
    public UiAutomationRunnerOutboxDispatcher(UiAutomationRepository repository, UiAutomationRunnerTaskPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${testhub.ui-automation-runner.outbox.dispatch-interval-ms:500}")
    public void dispatch() {
        repository.releaseStaleOutboxClaims();
        List<UiAutomationRepository.OutboxMessage> messages = repository.findDispatchable(20);
        for (UiAutomationRepository.OutboxMessage message : messages) {
            if (repository.claimOutbox(message.id()) == 0) continue;
            try {
                publisher.publish(message.streamKey(), message.payload());
                repository.markOutboxSent(message.id());
            } catch (RuntimeException exception) {
                repository.retryOutbox(message.id(), "Playwright Runner Stream 投递失败：" + exception.getClass().getSimpleName());
            }
        }
    }
}
