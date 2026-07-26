package io.testhub.platform.apiautomation;

import java.util.List;
import java.util.Map;

/** API-safe data-set view: only a small project-authorized preview is returned. */
public record ApiAutomationDataSetDetail(ApiAutomationDataSet dataSet, List<Map<String, String>> previewRows) {
    public ApiAutomationDataSetDetail {
        previewRows = List.copyOf(previewRows == null ? List.of() : previewRows);
    }
}
