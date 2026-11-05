package io.testhub.platform.reporting;

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

/** Whitelist the target field rather than exposing submitted values from Jackson exceptions. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=QualityReportScheduleController.class)
public class QualityReportScheduleInputExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpServletRequest request, HttpMessageNotReadableException error) {
        String requestId=(String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (Boolean.TRUE.equals(request.getAttribute(ApiExceptionHandler.BODY_TOO_LARGE_ATTRIBUTE))) {
            String message=request.getAttribute(ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE) instanceof String value ? value : "迁移请求不能超过 33 MiB。";
            return ResponseEntity.status(413).body(new ApiError("REQUEST_REJECTED",message,requestId,Map.of()));
        }
        boolean period=false;
        for (Throwable cause=error; cause!=null; cause=cause.getCause()) {
            if (cause instanceof JsonMappingException mapping && mapping.getPath().stream().anyMatch(ref -> "periodDays".equals(ref.getFieldName()))) {
                period=true; break;
            }
        }
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED",period ? "periodDays 必须是精确整数 JSON 数值" : "请求正文缺失或 JSON 格式不合法",requestId,
                period ? Map.of("periodDays","应使用 1 到 90 的整数 JSON 数值") : Map.of()));
    }
}
