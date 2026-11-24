package io.testhub.platform.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.apidebug.ApiDebugTargetPolicy;
import io.testhub.platform.apidebug.DesktopApiDebugTargetPolicy;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import static io.testhub.platform.model.ModelProviderController.*;

@Component
class ModelGateway {
    private static final MediaType JSON = MediaType.get("application/json");
    private static final int RESPONSE_LIMIT = 512 * 1024;
    private static final int PROBE_OUTPUT_TOKENS = 256;
    private static final String PROBE_PROMPT = "This is a connection test. Reply with only: TestHub OK";
    private static final Set<Integer> RETRYABLE = Set.of(429, 502, 503, 504);
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    private final ApiDebugTargetPolicy targets;
    private final DesktopApiDebugTargetPolicy desktopTargets;
    private final ObjectMapper json;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(Duration.ofSeconds(5))
            .callTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(25))
            .build();

    ModelGateway(ApiDebugTargetPolicy targets, DesktopApiDebugTargetPolicy desktopTargets, ObjectMapper json) {
        this.targets = targets;
        this.desktopTargets = desktopTargets;
        this.json = json;
    }

    String normalizeBaseUrl(String input) {
        URI uri = targets.normalizeWithoutNetworkPolicy(input);
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || input.indexOf('\\') >= 0) {
            throw rejected("API 地址不能包含查询参数、片段或反斜杠");
        }
        HttpUrl url = HttpUrl.get(uri.toString());
        if (!url.isHttps() && desktopLoopback(url).isEmpty()) {
            throw rejected("API 地址必须使用 HTTPS。本机模型请先在本地执行设置中授权对应的回环地址");
        }
        String normalized = url.toString().replaceFirst("/+$", "");
        if (normalized.length() > 2000) throw rejected("规范化后的 API 地址不能超过 2000 字符");
        return normalized;
    }

    List<Model> discover(String baseUrl, String key, RetryPolicy retry) {
        JsonNode models = request(baseUrl, "/models", key, retry, null, false).path("data");
        if (!models.isArray() && !models.isNull()) throw upstream("提供商没有返回有效的模型目录");
        Map<String, Model> result = new LinkedHashMap<>();
        for (JsonNode model : models) {
            String id = text(model.path("id"));
            if (validModelId(id) && (key == null || !id.contains(key))) {
                result.putIfAbsent(id, new Model(id, id.length() <= 120 ? id : "", null, null, false, List.of()));
                if (result.size() == 100) break;
            }
        }
        return List.copyOf(result.values());
    }

    String test(Provider provider, String key, Model model, String effort) {
        JsonNode response;
        String reply;
        int maxTokens = model.maxOutputTokens() == null ? PROBE_OUTPUT_TOKENS : Math.min(model.maxOutputTokens(), PROBE_OUTPUT_TOKENS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model.id());
        if (provider.protocol() == Protocol.CHAT_COMPLETIONS) {
            payload.put("messages", List.of(Map.of("role", "user", "content", PROBE_PROMPT)));
            payload.put("max_tokens", maxTokens);
            if (effort != null) payload.put("reasoning_effort", effort);
            response = request(provider.apiBaseUrl(), "/chat/completions", key, provider.retryPolicy(), payload, true);
            if (unsupportedLegacyTokenParameter(response)) {
                payload.remove("max_tokens");
                payload.put("max_completion_tokens", maxTokens);
                response = request(provider.apiBaseUrl(), "/chat/completions", key, provider.retryPolicy(), payload, false);
            }
            reply = text(response.path("choices").path(0).path("message").path("content"));
        } else {
            payload.put("input", PROBE_PROMPT);
            payload.put("max_output_tokens", maxTokens);
            payload.put("store", false);
            if (effort != null) payload.put("reasoning", Map.of("effort", effort));
            response = request(provider.apiBaseUrl(), "/responses", key, provider.retryPolicy(), payload, false);
            reply = StreamSupport.stream(response.path("output").spliterator(), false)
                    .filter(item -> "message".equals(text(item.path("type"))))
                    .flatMap(item -> StreamSupport.stream(item.path("content").spliterator(), false))
                    .filter(item -> "output_text".equals(text(item.path("type"))))
                    .map(item -> text(item.path("text")))
                    .collect(Collectors.joining("\n"));
        }
        if (reply.isBlank()) throw upstream("提供商没有返回文本回复，请检查模型是否支持所选协议");
        String redacted = (key == null ? reply : reply.replace(key, "[已隐藏]")).strip();
        return redacted.substring(0, Math.min(1000, redacted.length()));
    }

    static boolean validModelId(String id) {
        return id != null && id.length() <= 200 && id.matches("[!-~]+");
    }

    private JsonNode request(String baseUrl, String endpoint, String key, RetryPolicy retry, Map<String, Object> payload,
                             boolean checkChatTokenParameter) {
        HttpUrl url = HttpUrl.get(baseUrl + endpoint);
        List<InetAddress> addresses = desktopLoopback(url);
        if (addresses.isEmpty()) {
            if (!url.isHttps()) throw rejected("该本机 API 地址尚未在本地执行设置中授权");
            addresses = targets.resolveAllowed(url.uri());
        }
        List<InetAddress> pinned = addresses;
        OkHttpClient client = http.newBuilder().dns(host -> {
            if (!url.host().equalsIgnoreCase(host)) throw new UnknownHostException("Unexpected model API host");
            return pinned;
        }).build();

        Request.Builder outgoing = new Request.Builder().url(url).header("Accept", "application/json");
        if (key != null) outgoing.header("Authorization", "Bearer " + key);
        try {
            if (payload != null) outgoing.post(RequestBody.create(json.writeValueAsBytes(payload), JSON));
            Request request = outgoing.build();
            for (int attempt = 0; ; attempt++) {
                try (Response response = client.newCall(request).execute()) {
                    if (attempt == 0 && retry == RetryPolicy.TRANSIENT_ERRORS && RETRYABLE.contains(response.code())
                            && (response.header("Retry-After") == null || "0".equals(response.header("Retry-After")))) continue;
                    if (response.isSuccessful()) return readJson(response);
                    if (checkChatTokenParameter && response.code() == 400) {
                        JsonNode body = readJson(response);
                        if (unsupportedLegacyTokenParameter(body)) return body;
                    }
                    throw upstreamStatus(response.code(), endpoint);
                }
            }
        } catch (InterruptedIOException exception) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "请求模型提供商超时，请稍后重试");
        } catch (IOException exception) {
            throw upstream("无法读取模型提供商响应，请检查地址、网络和 TLS 证书");
        }
    }

    private List<InetAddress> desktopLoopback(HttpUrl url) {
        if (!LOOPBACK_HOSTS.contains(url.host().toLowerCase(Locale.ROOT))) return List.of();
        byte[] ip = url.host().equals("::1") ? new byte[16] : new byte[] {127, 0, 0, 1};
        ip[ip.length - 1] = 1;
        try {
            InetAddress[] addresses = {InetAddress.getByAddress(ip)};
            return desktopTargets.permits(url.uri(), url.isHttps(), addresses) ? List.of(addresses) : List.of();
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private JsonNode readJson(Response response) throws IOException {
        if (response.body() == null) throw upstream("模型提供商返回了空响应");
        byte[] content = response.body().byteStream().readNBytes(RESPONSE_LIMIT + 1);
        if (content.length > RESPONSE_LIMIT) throw upstream("模型提供商响应超过大小限制");
        try {
            JsonNode body = json.readTree(content);
            if (body != null && body.isObject()) return body;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw upstream("模型提供商响应不是有效的 JSON，请检查 API 地址和协议");
        }
        throw upstream("模型提供商响应不是有效的 JSON 对象，请检查 API 地址和协议");
    }

    private ResponseStatusException upstreamStatus(int status, String endpoint) {
        String message = switch (status) {
            case 401, 403 -> "模型提供商拒绝访问，请检查 API 密钥和模型权限";
            case 404 -> endpoint.equals("/models") ? "模型提供商未开放模型目录，请检查 API 地址或手动添加模型"
                    : "模型接口或模型不存在，请检查 API 地址、协议和模型 ID";
            case 429 -> "模型提供商请求过于频繁或配额不足";
            default -> status >= 300 && status < 400 ? "模型提供商返回重定向，请填写最终 API 地址"
                    : "模型提供商拒绝请求，请检查 API 地址、协议和模型 ID";
        };
        return upstream(message + "（HTTP " + status + "）");
    }

    private static String text(JsonNode value) {
        return value.isTextual() ? value.textValue() : "";
    }

    private static boolean unsupportedLegacyTokenParameter(JsonNode body) {
        JsonNode error = body.path("error");
        return "unsupported_parameter".equals(text(error.path("code"))) && "max_tokens".equals(text(error.path("param")));
    }

    private static ResponseStatusException rejected(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException upstream(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }
}
