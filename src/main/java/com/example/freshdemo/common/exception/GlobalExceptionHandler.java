package com.example.freshdemo.common.exception;

import com.example.freshdemo.common.response.ResponseEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * [LG-fm 컨벤션 적용] Controller 경계까지 전파된 예외를 공통 HTTP 응답으로 변환한다. 기존
 * fresh-demo GlobalExceptionHandler는 ResponseEntityExceptionHandler를 상속해 일부 예외만
 * 오버라이드했는데, LG-fm은 상속 없이 plain @RestControllerAdvice + 명시적 @ExceptionHandler
 * 목록으로 훨씬 넓은 예외(NoResourceFoundException/HttpRequestMethodNotSupportedException/
 * MaxUploadSizeExceededException/HttpMediaTypeNotSupportedException/AuthenticationException/
 * AccessDeniedException 포함)를 다룬다 — 그 형태를 그대로 옮겨왔다.
 *
 * AuthenticationException/AccessDeniedException은 필터 단계(SecurityConfig)에서
 * HandlerExceptionResolver로 다시 이 핸들러에 위임된 것도 함께 잡는다(SecurityConfig 참고) —
 * 그래서 기존에 있던 JwtAuthenticationEntryPoint/JwtAccessDeniedHandler(직접 JSON 작성)는
 * 삭제했다. 응답 문구는 항상 ErrorCode에서만 나온다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("business exception. code={}", errorCode.getCode(), e);
        return toResponse(errorCode);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleBind(BindException e) {
        String fields = e.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("invalid body. fields=[{}]", fields);
        return toResponse(CommonErrorCode.INVALID_INPUT);
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ResponseEnvelope<Void>> handleValidation(Exception e) {
        log.warn("invalid parameter. detail={}", e.getMessage());
        return toResponse(CommonErrorCode.INVALID_INPUT);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ResponseEnvelope<Void>> handleMalformedRequest(Exception e) {
        log.warn("malformed request. detail={}", e.getMessage());
        return toResponse(CommonErrorCode.MALFORMED_REQUEST);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleAuthentication(AuthenticationException e) {
        log.warn("unauthenticated. detail={}", e.getMessage());
        return toResponse(CommonErrorCode.UNAUTHENTICATED);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("permission denied. detail={}", e.getMessage());
        return toResponse(CommonErrorCode.PERMISSION_DENIED);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("endpoint not found. path={}", e.getResourcePath());
        return toResponse(CommonErrorCode.ENDPOINT_NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.warn("method not allowed. method={}", e.getMethod());
        return toResponse(CommonErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleContentTooLarge(MaxUploadSizeExceededException e) {
        log.warn("content too large. maxBytes={}", e.getMaxUploadSize());
        return toResponse(CommonErrorCode.CONTENT_TOO_LARGE);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        log.warn("unsupported media type. contentType={}", e.getContentType());
        return toResponse(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("unhandled exception. method={}, uri={}", request.getMethod(), request.getRequestURI(), e);
        return toResponse(CommonErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ResponseEnvelope<Void>> toResponse(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ResponseEnvelope.fail(errorCode));
    }
}
