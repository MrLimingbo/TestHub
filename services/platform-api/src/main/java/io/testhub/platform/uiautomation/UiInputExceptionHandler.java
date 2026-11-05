package io.testhub.platform.uiautomation;

import io.testhub.platform.shared.api.ApiError;
import io.testhub.platform.shared.api.ApiExceptionHandler;
import io.testhub.platform.shared.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Expose only fixed field names, never Jackson's raw input/value messages. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = { UiAutomationController.class, UiAutomationScheduleController.class })
public class UiInputExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpServletRequest request, HttpMessageNotReadableException error) {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (Boolean.TRUE.equals(request.getAttribute(ApiExceptionHandler.BODY_TOO_LARGE_ATTRIBUTE))) {
            String message = request.getAttribute(ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE) instanceof String value
                    ? value : "迁移请求不能超过 33 MiB。";
            return ResponseEntity.status(413).body(new ApiError("REQUEST_REJECTED", message, requestId, Map.of()));
        }
        String field = UiJson.field(error);
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED",
                field == null ? "请求正文缺失或 JSON 格式不合法" : "Web 字段 " + field + " 类型或数值不合法",
                requestId, field == null ? Map.of() : Map.of(field, "应使用字段要求的 JSON 类型和取值范围")));
    }
}
