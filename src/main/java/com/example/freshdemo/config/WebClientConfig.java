package com.example.freshdemo.config;

import com.example.freshdemo.common.filter.TraceIdExchangeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    /**
     * 카카오 unlink/logout API 호출 전용. 다른 외부 API용 WebClient가 있다면 그것과 분리해서 쓰는 걸 권장.
     *
     * TraceIdExchangeFilter를 붙여뒀지만 카카오는 우리 traceId 규약을 모르니 지금은 사실상 무효과다
     * (TraceIdExchangeFilter의 클래스 주석 참고) — 나중에 우리가 만든 다른 서비스를 이 WebClient류로
     * 호출하게 되면 그때부터 값어치가 생긴다.
     */
    @Bean
    public WebClient kakaoApiWebClient() {
        return WebClient.builder()
                .filter(TraceIdExchangeFilter.propagateTraceId())
                .build();
    }
}
