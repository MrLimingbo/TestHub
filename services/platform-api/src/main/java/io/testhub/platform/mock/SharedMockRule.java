package io.testhub.platform.mock;

record SharedMockRule(String name, String httpMethod, String path, int priority, int responseStatus,
                      java.util.Map<String, String> requestQuery, java.util.Map<String, String> requestHeaders,
                      String requestBodyMatch) {
}
