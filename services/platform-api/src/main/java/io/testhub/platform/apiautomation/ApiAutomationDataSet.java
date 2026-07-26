package io.testhub.platform.apiautomation;

import java.time.Instant;
import java.util.List;

/** Project-scoped, non-secret source data used to create isolated automation executions. */
public record ApiAutomationDataSet(String id, String organizationId, String projectId, String name,
                                   String sourceFormat, List<String> columns, int rowCount, String checksum,
                                   ApiAutomationDataSetSourceFile sourceFile, String createdBy, Instant createdAt,
                                   Instant updatedAt) {
    public ApiAutomationDataSet {
        columns = List.copyOf(columns == null ? List.of() : columns);
    }
}
