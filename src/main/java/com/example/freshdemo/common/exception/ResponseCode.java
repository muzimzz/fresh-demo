package com.example.freshdemo.common.exception;

import org.springframework.http.HttpStatus;

public interface ResponseCode {
    HttpStatus getStatus();
    String getMessage();

    /** 코드 식별자. enum 구현체는 Enum#name()이 자동으로 충족한다. */
    String name();
}
