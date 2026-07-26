package io.testhub.platform.apidesign;

public record ApiMockExampleSource(String apiId, String name, String method, String path, String versionName,
                                   String responseBody, String exampleResponse) {
}
