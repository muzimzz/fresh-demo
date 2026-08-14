package com.example.freshdemo.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 인증/회원/탈퇴 관련 에러코드로 시작한다.
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
    ADMIN_ALREADY_DELETED(HttpStatus.BAD_REQUEST, "이미 삭제(비활성화)된 관리자입니다."),
    CANNOT_DELETE_SELF(HttpStatus.BAD_REQUEST, "본인 계정은 삭제할 수 없습니다."),
    LAST_SUPER_ADMIN_CANNOT_BE_DELETED(HttpStatus.BAD_REQUEST, "마지막 남은 최고관리자는 삭제할 수 없습니다."),
    CURRENT_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "현재 비밀번호가 일치하지 않습니다."),

    // 배송지(Address)
    ADDRESS_NOT_FOUND(HttpStatus.BAD_REQUEST, "배송지를 찾을 수 없습니다."),

    // 회원 등급(MemberGrade)
    DEFAULT_MEMBER_GRADE_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "기본 회원 등급이 설정되어 있지 않습니다."),

    ;

    private final HttpStatus status;
    private final String message;
}
