package io.testhub.platform.notification;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Small outbound boundary: it does not log bodies, targets, or webhook secrets. */
@Component
public class WebhookNotificationTransport {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final WebhookTargetPolicy targetPolicy;

    public WebhookNotificationTransport(WebhookTargetPolicy targetPolicy) {
        this.targetPolicy = targetPolicy;
    }

    public Result deliver(String targetUrl, Map<String, String> headers, String payload) throws Exception {
        String normalizedTarget = targetPolicy.normalizeAndAssertAllowed(targetUrl);
        HttpRequest.Builder request = HttpRequest.newBuilder(java.net.URI.create(normalizedTarget)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload));
        headers.forEach(request::header);
        HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
        return new Result(response.statusCode());
    }

    public record Result(int statusCode) {
    }
}
