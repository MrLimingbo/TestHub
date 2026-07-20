package io.testhub.platform.mock;

public record SmartMockExample(String apiId, String name, String method, String path, String versionName,
                               int responseStatus, String bodyPreview) {
}
