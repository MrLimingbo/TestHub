package io.testhub.platform.apidebug;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** L0 limits reads of legacy ciphertext without modifying it. Only new native rows opt in to G0. */
@Component
public class ApiDebugReplayPolicy {
    public static final Duration RETENTION = Duration.ofDays(30);
    private final Clock clock;

    @Autowired
    public ApiDebugReplayPolicy(ObjectProvider<Clock> clock) { this(clock.getIfAvailable(Clock::systemUTC)); }
    public ApiDebugReplayPolicy(Clock clock) { this.clock = clock; }
    public Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    public Instant expiresAt(Instant createdAt) { return createdAt.plus(RETENTION); }

    public ApiDebugHistory forRead(ApiDebugHistory row) {
        if ("UNKNOWN_CREATED_AT".equals(row.replayStatus()) && row.replayDraft() == null) return row;
        if (row.replayDraft() == null && row.replayExpiresAt() == null) return copy(row, null, null, "SUMMARY_ONLY");
        if (row.createdAt() == null || row.createdAt().isAfter(now())) return copy(row, null, null, "UNKNOWN_CREATED_AT");
        Instant expires = row.replayExpiresAt() == null ? expiresAt(row.createdAt()) : row.replayExpiresAt();
        if (!now().isBefore(expires)) return copy(row, null, expires, "EXPIRED");
        return copy(row, row.replayDraft(), expires, row.replayDraft() == null ? "SUMMARY_ONLY" : "AVAILABLE");
    }

    public static ApiDebugHistory copy(ApiDebugHistory row, String draft, Instant expires, String state) {
        return new ApiDebugHistory(row.id(), row.organizationId(), row.projectId(), row.method(), row.protocol(), row.requestUrl(),
                row.responseStatus(), row.durationMs(), row.responseSize(), row.result(), row.errorMessage(), draft,
                row.createdBy(), row.createdAt(), row.importSourceProjectId(), row.importSourceId(), expires, state);
    }
}
