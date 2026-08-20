package io.testhub.platform.apidebug;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class JdkApiDebugTransport implements ApiDebugTransport {
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private final ApiDebugTargetPolicy targetPolicy;
    private final RequestSender sender;

    @Autowired
    public JdkApiDebugTransport(ApiDebugTargetPolicy targetPolicy) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.targetPolicy = targetPolicy;
        this.sender = request -> client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    JdkApiDebugTransport(ApiDebugTargetPolicy targetPolicy, RequestSender sender) {
        this.targetPolicy = targetPolicy;
        this.sender = sender;
    }

    @Override
    public TransportResponse execute(String method, URI url, List<Header> headers, String body, int timeoutMs)
            throws IOException, InterruptedException {
        targetPolicy.assertAllowed(url);
        HttpRequest.BodyPublisher publisher = body == null || body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(Duration.ofMillis(timeoutMs)).method(method, publisher);
        for (Header header : headers) request.header(header.name(), header.value());

        HttpResponse<InputStream> response = sender.send(request.build());
        List<Header> responseHeaders = new ArrayList<>();
        response.headers().map().forEach((name, values) -> values.forEach(value -> responseHeaders.add(new Header(name, value))));
        Body bodyResult;
        try (InputStream stream = response.body()) {
            bodyResult = readBody(stream, contentType(responseHeaders));
        }
        return new TransportResponse(response.statusCode(), statusText(response.statusCode()), responseHeaders,
                bodyResult.value(), bodyResult.truncated());
    }

    private Body readBody(InputStream stream, String contentType) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        boolean truncated = false;
        for (int read; (read = stream.read(buffer)) >= 0;) {
            int writable = Math.min(read, MAX_RESPONSE_BYTES - total);
            if (writable > 0) output.write(buffer, 0, writable);
            total += writable;
            if (read > writable || total >= MAX_RESPONSE_BYTES) {
                truncated = true;
                break;
            }
        }
        byte[] bytes = output.toByteArray();
        if (!isTextual(contentType)) return new Body("data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes), truncated);
        return new Body(new String(bytes, charset(contentType)), truncated);
    }

    private String contentType(List<Header> headers) {
        return headers.stream().filter(header -> header.name().equalsIgnoreCase("Content-Type"))
                .map(Header::value).findFirst().orElse("text/plain");
    }

    private boolean isTextual(String contentType) {
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("text/") || type.contains("json") || type.contains("xml") || type.contains("javascript") || type.contains("graphql");
    }

    private Charset charset(String contentType) {
        for (String item : contentType.split(";")) {
            String value = item.trim();
            if (value.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                try { return Charset.forName(value.substring("charset=".length()).trim()); } catch (Exception ignored) { return StandardCharsets.UTF_8; }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private String statusText(int statusCode) {
        return switch (statusCode) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "HTTP " + statusCode;
        };
    }

    private record Body(String value, boolean truncated) {
    }

    @FunctionalInterface
    interface RequestSender {
        HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;
    }
}
