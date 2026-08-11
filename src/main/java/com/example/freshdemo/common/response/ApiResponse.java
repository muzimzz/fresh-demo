package com.example.freshdemo.common.response;

import com.example.freshdemo.common.exception.ResponseCode;
import java.time.Instant;

public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        Instant timestamp
) {

    /**
     * 성공 응답 전용 — ResponseCode 없이 바로 쓴다. 에러와 달리 성공은 프론트가 code값으로 분기할
     * 일이 거의 없어서(호출한 엔드포인트 자체가 이미 "무슨 성공인지" 말해준다), 매 케이스마다
     * SuccessCode enum을 따로 만들지 않고 고정된 "OK"를 쓴다.
     */
    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(true, "OK", "요청이 성공했습니다.", data, Instant.now());
    }

    /** 성공인데 특정 케이스만 다른 코드/메시지를 주고 싶을 때를 위해 남겨둔 오버로드(지금은 미사용). */
    public static <T> ApiResponse<T> of(T data, ResponseCode responseCode) {
        return new ApiResponse<>(
                true,
                responseCode.getStatus().name(),
                responseCode.getMessage(),
                data,
                Instant.now()
        );
    }

    public static <T> ApiResponse<T> fail(T data, ResponseCode responseCode) {
        return new ApiResponse<>(
                false,
                responseCode.getStatus().name(),
                responseCode.getMessage(),
                data,
                Instant.now()
        );
    }

    public static <T> ApiResponse<T> fail(ResponseCode responseCode) {
        return new ApiResponse<>(
                false,
                responseCode.getStatus().name(),
                responseCode.getMessage(),
                null,
                Instant.now()
        );
    }
}
