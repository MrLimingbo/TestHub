package io.testhub.platform.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    public static final String BODY_TOO_LARGE_ATTRIBUTE = "testhub.requestBodyTooLarge";
    public static final String BODY_TOO_LARGE_MESSAGE_ATTRIBUTE = "testhub.requestBodyTooLargeMessage";
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleOversizedUpload(HttpServletRequest request) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_REJECTED", "上传文件或表单超过允许大小，请缩小文件后重试", request);
    }

    @ExceptionHandler({BadCredentialsException.class, DisabledException.class})
    ResponseEntity<ApiError> handleBadCredentials(HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "账号或密码错误", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "AUTH_FORBIDDEN", "当前账号没有访问权限", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(HttpServletRequest request, MethodArgumentNotValidException exception) {
        Map<String, Object> details = exception.getBindingResult().getFieldErrors().stream()
                .collect(java.util.stream.Collectors.toMap(
                        error -> error.getField(),
                        error -> (Object) (error.getDefaultMessage() == null ? "输入不合法" : error.getDefaultMessage()),
                        (first, ignored) -> first));
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", "请求参数不合法", requestId, details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableBody(HttpServletRequest request) {
        if (Boolean.TRUE.equals(request.getAttribute(BODY_TOO_LARGE_ATTRIBUTE)))
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_REJECTED", request.getAttribute(BODY_TOO_LARGE_MESSAGE_ATTRIBUTE) instanceof String message ? message : "迁移请求不能超过 33 MiB。", request);
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求正文缺失或 JSON 格式不合法", request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> handleResponseStatus(HttpServletRequest request, ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return error(status, "REQUEST_REJECTED", exception.getReason() == null ? "请求被拒绝" : exception.getReason(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleInvalidParameterType(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "REQUEST_REJECTED", "请求参数类型不合法，请检查后重试", request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> handleMissingParameter(HttpServletRequest request, MissingServletRequestParameterException exception) {
        return error(HttpStatus.BAD_REQUEST, "REQUEST_REJECTED", "缺少必填请求参数：" + exception.getParameterName(), request);
    }

    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    ResponseEntity<ApiError> handleMissingPart(HttpServletRequest request, org.springframework.web.multipart.support.MissingServletRequestPartException exception) {
        return error(HttpStatus.BAD_REQUEST, "REQUEST_REJECTED", "缺少必填文件或表单项：" + exception.getRequestPartName(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleUnsupportedMethod(HttpServletRequest request, HttpRequestMethodNotSupportedException exception) {
        var methods = exception.getSupportedHttpMethods();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .allow(methods == null ? new org.springframework.http.HttpMethod[0] : methods.toArray(org.springframework.http.HttpMethod[]::new))
                .body(new ApiError("REQUEST_METHOD_NOT_ALLOWED", "该接口不支持此请求方法",
                        (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), Map.of()));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleUnsupportedMediaType(HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "REQUEST_REJECTED", "请求正文类型不受支持，请使用接口要求的内容类型", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(HttpServletRequest request, Exception exception) {
        if (DependencyFailures.isUnavailable(exception))
            return error(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "服务依赖暂时不可用，请稍后重试", request);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时不可用", request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return ResponseEntity.status(status).body(new ApiError(code, message, requestId, Map.of()));
    }
}
