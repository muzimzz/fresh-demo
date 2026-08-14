package com.example.freshdemo.common.exception;

import lombok.Getter;

// [LG-fm 컨벤션 적용] 비즈니스 정책 위반을 나타내는 공통 예외 기반 클래스. 기존 BusinessException은
// ResponseCode(단일 enum)를 들고 있었는데, 이제 ErrorCode를 들고 도메인마다 이 클래스를 상속하는
// 자기 예외(MemberException 등)를 통해서만 던져진다 — 직접 인스턴스화하지 않는다(abstract).
@Getter
public abstract class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 하위 계층/외부 호출의 예외를 도메인 실패로 옮길 때 cause를 넘긴다(스택이 끊기지 않게). */
    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
