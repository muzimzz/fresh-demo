package com.example.freshdemo.admin.exception;

import com.example.freshdemo.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AdminErrorCode implements ErrorCode {

    ADMIN_NOT_FOUND(HttpStatus.BAD_REQUEST, "ADMIN-001", "관리자를 찾을 수 없습니다."),
    NOT_SUPER_ADMIN(HttpStatus.FORBIDDEN, "ADMIN-002", "SUPER_ADMIN만 수행할 수 있는 작업입니다."),
    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "ADMIN-003", "이미 사용 중인 아이디입니다."),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "ADMIN-004", "아이디 또는 비밀번호가 올바르지 않습니다."),
    ADMIN_ALREADY_DELETED(HttpStatus.BAD_REQUEST, "ADMIN-005", "이미 삭제(비활성화)된 관리자입니다."),
    CANNOT_DELETE_SELF(HttpStatus.BAD_REQUEST, "ADMIN-006", "본인 계정은 삭제할 수 없습니다."),
    LAST_SUPER_ADMIN_CANNOT_BE_DELETED(HttpStatus.BAD_REQUEST, "ADMIN-007", "마지막 남은 최고관리자는 삭제할 수 없습니다."),
    CURRENT_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "ADMIN-008", "현재 비밀번호가 일치하지 않습니다."),
    ;

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
