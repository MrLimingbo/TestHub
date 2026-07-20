package io.testhub.platform.apiautomation;

import java.util.List;

/** Metadata-only result of creating row-isolated executions from one data set. */
public record ApiAutomationDataSetExecutionBatch(String dataSetId, String dataSetName, int rowCount,
                                                  List<ApiAutomationExecution> executions) {
    public ApiAutomationDataSetExecutionBatch {
        executions = List.copyOf(executions == null ? List.of() : executions);
    }
}
