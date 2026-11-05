package io.testhub.platform.execution;

import com.fasterxml.jackson.databind.JsonMappingException;
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

/** Report a fixed target field without echoing the supplied value. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ExecutionTaskController.class)
public class TaskInputExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpServletRequest request, HttpMessageNotReadableException error) {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (Boolean.TRUE.equals(request.getAttribute(ApiExceptionHandler.BODY_TOO_LARGE_ATTRIBUTE))) {
            String message = request.getAttribute(ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE) instanceof String value
                    ? value : "迁移请求不能超过 33 MiB。";
            return ResponseEntity.status(413).body(new ApiError("REQUEST_REJECTED", message, requestId, Map.of()));
        }
        Throwable cause = error.getCause();
        boolean progress = cause instanceof JsonMappingException mapping && mapping.getPath().stream()
                .anyMatch(reference -> "progress".equals(reference.getFieldName()));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED",
                progress ? "任务进度必须是 0 到 100 的整数" : "请求正文缺失或 JSON 格式不合法", requestId,
                progress ? Map.of("progress", "必须是 0 到 100 的整数") : Map.of()));
    }
}
