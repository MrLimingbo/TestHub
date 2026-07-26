package io.testhub.platform.apiautomation;

/** Non-sensitive project-file metadata for a data set's original imported source. */
public record ApiAutomationDataSetSourceFile(String id, String originalName, String contentType, long sizeBytes) {
}
