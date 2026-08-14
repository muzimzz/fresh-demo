package com.example.freshdemo.common.exception;

import org.springframework.http.HttpStatus;

// [LG-fm 컨벤션 적용] 기존 단일 flat ErrorCode enum(ResponseCode 인터페이스 구현)을 대체한다.
// 공통 코드는 CommonErrorCode이며, 도메인마다 자기 ErrorCode enum을 두고 이 인터페이스를 구현한다.
public interface ErrorCode {

    HttpStatus getHttpStatus();

    String getCode();

    String getMessage();
}
