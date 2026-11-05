package io.testhub.platform.functional;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FunctionalTestRunCase(String id, String runId, String caseId, String caseCode, String title,
                                    String status, String note, String titleSnapshot) {
    public FunctionalTestRunCase(String id, String runId, String caseId, String caseCode, String title, String status, String note) {
        this(id, runId, caseId, caseCode, title, status, note, null);
    }
    @JsonProperty("titleSource")
    public String titleSource() { return titleSnapshot == null ? "CURRENT_ASSET" : "SNAPSHOT"; }
}
