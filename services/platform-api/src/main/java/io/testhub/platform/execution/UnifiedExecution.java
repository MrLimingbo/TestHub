package io.testhub.platform.execution;

import java.time.Instant;

/** Project-scoped read projection; names and times come from execution snapshots. */
public record UnifiedExecution(String id, String projectId, String module, String name, String status,
                               Instant createdAt, Instant startedAt, Instant finishedAt, Instant updatedAt,
                               Integer totalCount, Integer passedCount, Integer failedCount, Integer blockedCount) { }
