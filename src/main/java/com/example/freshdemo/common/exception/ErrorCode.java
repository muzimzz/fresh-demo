package com.example.freshdemo.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * haeyaji의 ErrorCode에서 인증/회원/탈퇴 관련 코드만 추려온 것.
 * 새 프로젝트의 다른 도메인 에러코드는 이 enum에 계속 추가하면 된다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode implements ResponseCode {

    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "요청 파라미터가 올바르지 않습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP Method입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

    MEMBER_NOT_FOUND(HttpStatus.BAD_REQUEST, "회원을 찾을 수 없습니다."),
    MEMBER_ALREADY_WITHDRAWN(HttpStatus.BAD_REQUEST, "이미 탈퇴한 회원입니다."),
    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),

    KAKAO_UNLINK_FAILED(HttpStatus.BAD_GATEWAY, "카카오 연결 해제 요청에 실패했습니다."),
    KAKAO_WEBHOOK_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 카카오 웹훅 요청입니다."),

    // 관리자(Admin) — fm-backend(freshmarket) 스펙 참고
    ADMIN_NOT_FOUND(HttpStatus.BAD_REQUEST, "관리자를 찾을 수 없습니다."),
    NOT_SUPER_ADMIN(HttpStatus.FORBIDDEN, "SUPER_ADMIN만 수행할 수 있는 작업입니다."),
    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),

    // 배송지(Address)
    ADDRESS_NOT_FOUND(HttpStatus.BAD_REQUEST, "배송지를 찾을 수 없습니다."),

    ;

    private final HttpStatus status;
    private final String message;
}
