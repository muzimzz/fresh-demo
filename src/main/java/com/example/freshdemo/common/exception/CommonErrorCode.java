package com.example.freshdemo.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * [LG-fm 컨벤션 적용] 특정 도메인에 속하지 않는 공통 오류 코드 — 도메인 지식이 필요 없는 프레임워크
 * 경계의 실패만 둔다. 회원을 못 찾았다거나 하는 건 도메인이 아는 실패라 각 도메인 ErrorCode로 간다.
 *
 * 기존 fresh-demo ErrorCode의 INVALID_PARAMETER/INTERNAL_ERROR/METHOD_NOT_ALLOWED/NOT_FOUND/
 * UNAUTHORIZED/FORBIDDEN 6개는 이 9개 중 아래 대응 항목으로 흡수됐다:
 * INVALID_PARAMETER->INVALID_INPUT, NOT_FOUND->ENDPOINT_NOT_FOUND, UNAUTHORIZED->UNAUTHENTICATED,
 * FORBIDDEN->PERMISSION_DENIED, 나머지는 이름 그대로.
 */
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-001", "서버 오류가 발생했습니다."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON-002", "입력값이 올바르지 않습니다. 요청 형식을 확인해 주세요."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "COMMON-003", "요청을 해석할 수 없습니다. 본문과 파라미터 형식을 확인해 주세요."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "COMMON-004", "인증이 필요합니다. 로그인 후 다시 시도해 주세요."),
    PERMISSION_DENIED(HttpStatus.FORBIDDEN, "COMMON-005", "접근 권한이 없습니다."),
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON-006", "요청하신 경로를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON-007", "지원하지 않는 요청 방식입니다."),
    CONTENT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "COMMON-008", "요청 크기가 허용 범위를 넘었습니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON-009", "지원하지 않는 형식입니다. Content-Type을 확인해 주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
