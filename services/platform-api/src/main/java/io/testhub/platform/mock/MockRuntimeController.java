package io.testhub.platform.mock;

import io.testhub.platform.shared.api.ApiError;
import io.testhub.platform.shared.api.RequestIdFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MockRuntimeController {
    private final MockService mocks;

    public MockRuntimeController(MockService mocks) {
        this.mocks = mocks;
    }

    @RequestMapping(value = { "/api/v1/mock/{serverKey}", "/api/v1/mock/{serverKey}/**" }, method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.HEAD, RequestMethod.OPTIONS })
    public ResponseEntity<?> execute(@PathVariable String serverKey, HttpServletRequest request) throws IOException {
        String prefix = request.getContextPath() + "/api/v1/mock/" + serverKey;
        String requestUri = request.getRequestURI();
        String path = requestUri.startsWith(prefix) ? requestUri.substring(prefix.length()) : "/";
        if (path.isBlank()) path = "/";
        return mocks.runtime(serverKey, new MockRuntimeRequest(request.getMethod(), path, query(request), headers(request), body(request)))
                .<ResponseEntity<?>>map(this::response)
                .orElseGet(() -> notFound(request));
    }

    private Map<String, List<String>> query(HttpServletRequest request) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> result.put(name, values == null ? List.of() : List.of(values)));
        return result;
    }

    private Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames()).forEach(name -> result.put(name, request.getHeader(name)));
        return result;
    }

    private String body(HttpServletRequest request) throws IOException {
        byte[] data = request.getInputStream().readNBytes(512 * 1024 + 1);
        return new String(data, StandardCharsets.UTF_8);
    }

    private ResponseEntity<String> response(MockRuntimeResponse result) {
        HttpHeaders headers = new HttpHeaders();
        result.headers().forEach(headers::add);
        return ResponseEntity.status(result.status()).headers(headers).body(result.body());
    }

    private ResponseEntity<ApiError> notFound(HttpServletRequest request) {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError("MOCK_RULE_NOT_FOUND", "未找到可用的 Mock 规则", requestId, Map.of()));
    }
}
