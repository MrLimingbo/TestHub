package io.testhub.platform.version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ProjectVersion(String id, long displayId, String organizationId, String projectId, String projectName,
                             String versionNumber, List<String> buildNumbers, String status,
                             LocalDate plannedReleaseDate, LocalDate actualReleaseDate, String description,
                             String createdBy, String createdByName, String updatedBy, Instant createdAt,
                             Instant updatedAt) { }
