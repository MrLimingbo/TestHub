package io.testhub.platform.apiautomation;

import java.util.List;
import java.util.Map;

/** Internal complete content used only while freezing one execution per data row. */
public record ApiAutomationDataSetContent(ApiAutomationDataSet dataSet, List<Map<String, String>> rows) {
    public ApiAutomationDataSetContent {
        rows = List.copyOf(rows == null ? List.of() : rows);
    }
}
