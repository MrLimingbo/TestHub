package io.testhub.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.shared.api.ApiError;
import io.testhub.platform.shared.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class SecurityApiErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper objectMapper;

    public SecurityApiErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         org.springframework.security.core.AuthenticationException exception) throws IOException {
        write(response, request, HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "请先登录后再访问");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        write(response, request, HttpStatus.FORBIDDEN, "AUTH_FORBIDDEN", "当前账号没有访问权限");
    }

    private void write(HttpServletResponse response, HttpServletRequest request, HttpStatus status,
                       String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        objectMapper.writeValue(response.getWriter(), new ApiError(code, message, requestId, Map.of()));
    }
}
