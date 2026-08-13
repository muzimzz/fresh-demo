package com.example.freshdemo.common.exception;

import com.example.freshdemo.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.WebUtils;

/**
 * 검증된 예외 처리 정책을 그대로 가져왔다.
 * 로깅 정책(4xx=WARN 스택트레이스 없음, 5xx=ERROR 스택트레이스 포함)은 검증된 부분이라 그대로 유지 권장.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        ResponseCode code = e.getErrorCode();
        logByStatus(e, code, null);

        return ResponseEntity
                .status(code.getStatus())
                .body(ApiResponse.fail(code));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<List<ValidationError>>> handleConstraintViolation(
            ConstraintViolationException e
    ) {
        List<ValidationError> errors = e.getConstraintViolations().stream()
                .map(v -> ValidationError.of(extractField(v.getPropertyPath()), v.getMessage()))
                .toList();

        logClientError(e, ErrorCode.INVALID_PARAMETER, errors);

        return ResponseEntity
                .badRequest()
                .body(ApiResponse.fail(errors, ErrorCode.INVALID_PARAMETER));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<List<ValidationError>>> handleTypeMismatch(
            MethodArgumentTypeMismatchException e
    ) {
        ValidationError error = ValidationError.of(e.getName(), "요청 값의 타입이 올바르지 않습니다.");
        logClientError(e, ErrorCode.INVALID_PARAMETER, "parameter=" + e.getName());

        return ResponseEntity
                .badRequest()
                .body(ApiResponse.fail(List.of(error), ErrorCode.INVALID_PARAMETER));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception e) {
        logServerError(e, ErrorCode.INTERNAL_ERROR);

        return ResponseEntity
                .internalServerError()
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request
    ) {
        List<ValidationError> errors = toValidationErrors(ex.getBindingResult());
        logClientError(ex, ErrorCode.INVALID_PARAMETER, errors);

        return handleExceptionInternal(
                ex, ApiResponse.fail(errors, ErrorCode.INVALID_PARAMETER), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request
    ) {
        List<ValidationError> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> ValidationError.of(
                                result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();

        logClientError(ex, ErrorCode.INVALID_PARAMETER, errors);

        return handleExceptionInternal(
                ex, ApiResponse.fail(errors, ErrorCode.INVALID_PARAMETER), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request
    ) {
        ValidationError error =
                ValidationError.of(ex.getParameterName(), "필수 요청 파라미터가 누락되었습니다.");
        logClientError(ex, ErrorCode.INVALID_PARAMETER, "parameter=" + ex.getParameterName());

        return handleExceptionInternal(
                ex, ApiResponse.fail(List.of(error), ErrorCode.INVALID_PARAMETER), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request
    ) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            HttpServletResponse response = servletWebRequest.getResponse();
            if (response != null && response.isCommitted()) {
                log.warn("event=RESPONSE_COMMITTED exception={} msg=\"{}\"",
                        ex.getClass().getSimpleName(), ex.getMessage());
                return null;
            }
        }

        if (statusCode.equals(HttpStatus.INTERNAL_SERVER_ERROR) && body == null) {
            request.setAttribute(WebUtils.ERROR_EXCEPTION_ATTRIBUTE, ex, WebRequest.SCOPE_REQUEST);
        }

        if (!(body instanceof ApiResponse<?>)) {
            ErrorCode code = mapToErrorCode(statusCode);
            logByStatus(ex, code, null);
            body = ApiResponse.fail(code);
        }

        return createResponseEntity(body, headers, statusCode, request);
    }

    private void logByStatus(Exception ex, ResponseCode code, @Nullable Object detail) {
        if (code.getStatus().is5xxServerError()) {
            logServerError(ex, code);
        } else {
            logClientError(ex, code, detail);
        }
    }

    private void logClientError(Exception ex, ResponseCode code, @Nullable Object detail) {
        log.warn("event=CLIENT_ERROR code={} status={} exception={} detail=\"{}\" msg=\"{}\"",
                code.name(),
                code.getStatus().value(),
                ex.getClass().getSimpleName(),
                detail == null ? "-" : detail,
                rootCauseMessage(ex));
    }

    private void logServerError(Exception ex, ResponseCode code) {
        log.error("event=SERVER_ERROR code={} status={} exception={} msg=\"{}\"",
                code.name(),
                code.getStatus().value(),
                ex.getClass().getSimpleName(),
                rootCauseMessage(ex),
                ex);
    }

    private String rootCauseMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null ? root.getClass().getSimpleName() : message;
    }

    private List<ValidationError> toValidationErrors(BindingResult bindingResult) {
        Stream<ValidationError> fieldErrors = bindingResult.getFieldErrors().stream()
                .map(e -> ValidationError.of(e.getField(), e.getDefaultMessage()));

        Stream<ValidationError> globalErrors = bindingResult.getGlobalErrors().stream()
                .map(e -> ValidationError.of(e.getObjectName(), e.getDefaultMessage()));

        return Stream.concat(fieldErrors, globalErrors).toList();
    }

    private ErrorCode mapToErrorCode(HttpStatusCode status) {
        if (status.equals(HttpStatus.METHOD_NOT_ALLOWED)) {
            return ErrorCode.METHOD_NOT_ALLOWED;
        }
        if (status.equals(HttpStatus.NOT_FOUND)) {
            return ErrorCode.NOT_FOUND;
        }
        if (status.is4xxClientError()) {
            return ErrorCode.INVALID_PARAMETER;
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    private String extractField(Path propertyPath) {
        String path = propertyPath.toString();
        int lastDot = path.lastIndexOf('.');
        return lastDot == -1 ? path : path.substring(lastDot + 1);
    }
}
