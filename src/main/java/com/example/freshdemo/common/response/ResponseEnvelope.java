package com.example.freshdemo.common.response;

import com.example.freshdemo.common.exception.ErrorCode;

/**
 * [LG-fm 컨벤션 적용] 모든 응답이 통과하는 공통 봉투. 기존 ApiResponse(boolean success, code,
 * message, data, Instant timestamp)를 대체한다 — LG-fm은 success 불리언과 timestamp를 두지 않고,
 * code가 "SUCCESS"인지로 성공 여부를 판별한다(boolean을 따로 두면 code와 어긋난 응답이 나올 수
 * 있다는 게 LG-fm의 설계 근거). 실패 시 code/message는 항상 ErrorCode에서 나오고 던지는 자리에서
 * 문장을 지어내지 않는다.
 */
public record ResponseEnvelope<T>(
        String code,
        String message,
        T data
) {

    private static final String SUCCESS_CODE = "SUCCESS";
    private static final String SUCCESS_MESSAGE = "요청이 성공적으로 처리되었습니다.";

    public static <T> ResponseEnvelope<T> success(T data) {
        return new ResponseEnvelope<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static ResponseEnvelope<Void> success() {
        return new ResponseEnvelope<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    public static ResponseEnvelope<Void> fail(ErrorCode errorCode) {
        return new ResponseEnvelope<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> ResponseEnvelope<T> fail(ErrorCode errorCode, T data) {
        return new ResponseEnvelope<>(errorCode.getCode(), errorCode.getMessage(), data);
    }
}
