package io.testhub.platform.apidebug;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ApiDebugReplayRetention implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(ApiDebugReplayRetention.class);
    private final ApiDebugHistoryRepository history;
    private final ApiDebugReplayPolicy policy;
    public ApiDebugReplayRetention(ApiDebugHistoryRepository history, ApiDebugReplayPolicy policy) {
        this.history = history; this.policy = policy;
    }

    @Override
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 86_400_000)
    public void run() {
        try {
            int cleared = history.clearExpiredManagedReplays(policy.now());
            if (cleared > 0) LOG.info("Expired managed debug replays cleared: {}", cleared);
        } catch (DataAccessException error) {
            LOG.error("Debug replay cleanup failed ({}); retained data will be retried", error.getClass().getSimpleName());
        }
    }
}
