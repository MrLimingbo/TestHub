package io.testhub.platform.functional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record FunctionalDefect(String id, long displayId, String organizationId, String projectId, String catalogId,
                               String defectCode, String title, LocalDate dueDate, String bugType, String keywords,
                               List<String> browsers, List<String> operatingSystems, List<String> ccUserIds,
                               String severity, String priority, String status, boolean confirmed,
                               String assigneeId, String assigneeName, String assignedBy,
                               String assignedByName, String resolution,
                               String resolvedBy, String resolvedByName, Instant resolvedAt, String closedBy,
                               Instant closedAt, String source, String externalUrl, String description,
                               String createdBy, String createdByName, String updatedBy, Instant createdAt,
                               Instant updatedAt, int linkCount) { }
