package com.example.freshdemo.common.filter;

import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

/**
 * 아웃바운드 WebClient 호출(카카오 등 외부 API)마다 method/URL/상태코드/소요시간을 자동으로 남기는
 * 공통 필터. WebClient.Builder에 한 번 끼워두면, 그 WebClient로 나가는 모든 호출이 클라이언트
 * 클래스(KakaoUnlinkClient 등)에서 로깅 코드를 따로 안 짜도 자동으로 기록된다 — 나중에 외부 API가
 * 늘어나도(Toss 등) 이 필터를 쓰는 WebClient에 얹기만 하면 됨.
 *
 * KakaoUnlinkClient/KakaoLogoutClient가 이미 남기는 event=KAKAO_UNLINK_FAILED 같은 로그와는
 * 역할이 다르다 — 그쪽은 "이 kakaoUserId에 대한 unlink가 왜 실패했는지"같은 비즈니스 맥락이고,
 * 이 필터는 "이 HTTP 호출이 기술적으로 얼마나 걸렸고 상태코드가 뭐였는지"만 본다. 서로 보완 관계라
 * 기존 로그를 대체하지 않고 같이 남는다.
 *
 * 요청/응답 바디는 안 남긴다 — WebClient는 바디가 Publisher라 가로채려면 별도로 감싸야 해서
 * 복잡도가 커지고, 지금 목적(외부 API가 느려서 우리가 멈췄는지 증명하는 것)엔 상태코드+소요시간이면
 * 충분하다고 판단했다. URL은 그대로 로그에 남기는데, 지금 쓰는 카카오 API들은 쿼리 파라미터에
 * 민감정보를 안 실어서 문제없다 — 나중에 쿼리 파라미터에 토큰/키 같은 게 들어가는 외부 API를
 * 추가하게 되면 이 필터를 그대로 쓰지 말고 URL 마스킹을 추가해야 한다.
 */
@Slf4j
public final class ExternalApiLoggingExchangeFilter {

    private ExternalApiLoggingExchangeFilter() {
    }

    public static ExchangeFilterFunction logCalls() {
        return (request, next) -> {
            Instant start = Instant.now();
            return next.exchange(request)
                    .doOnNext(response -> log.info(
                            "event=EXTERNAL_API_CALL method={} uri={} status={} durationMs={}",
                            request.method(), request.url(), response.statusCode().value(),
                            Duration.between(start, Instant.now()).toMillis()))
                    .doOnError(ex -> log.warn(
                            "event=EXTERNAL_API_CALL_FAILED method={} uri={} durationMs={} err={}",
                            request.method(), request.url(),
                            Duration.between(start, Instant.now()).toMillis(), ex.toString()));
        };
    }
}
