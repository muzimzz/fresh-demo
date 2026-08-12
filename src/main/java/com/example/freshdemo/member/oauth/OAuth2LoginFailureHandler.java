package com.example.freshdemo.member.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 카카오 OAuth2 로그인 흐름에서 실패가 나면(카카오 쪽 동의 거부, id_token 검증 실패,
 * CustomOidcUserService가 던진 OAuth2AuthenticationException 등 원인 불문) 전부 여기로 모인다 —
 * 원인이 스프링 시큐리티 필터 체인 안 어디서 터지든 이 핸들러 하나로 다 잡히는 게 핵심이다.
 * CustomOidcUserService 안에도 좀 더 구체적인 로그가 추가로 남는 경우가 있는데, 그건 이 핸들러가
 * 못 담는 맥락(예: 어떤 소셜 타입이었는지)을 보완하는 것뿐이고, 이 핸들러가 항상 최종 안전망이다.
 *
 * 이전까지는 이 흐름에서 실패해도 로그가 전혀 안 남았다 — GlobalExceptionHandler는 DispatcherServlet이
 * 컨트롤러를 호출하는 과정에서 터진 예외만 잡는데, OAuth2 로그인 실패는 그보다 앞단인 스프링 시큐리티
 * 필터 안에서 끝나버려서 거기까지 도달하지 않기 때문에 별도 핸들러가 필요했다.
 */
@Slf4j
@Component
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    @Value("${app.frontend.callback-url}")
    private String frontendCallbackUrl;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                         AuthenticationException exception) throws IOException {
        log.warn("event=MEMBER_LOGIN_FAILED reason={}", exception.getClass().getSimpleName(), exception);

        String targetUrl = UriComponentsBuilder.fromUriString(frontendCallbackUrl)
                .queryParam("loginFailed", true)
                .build().toUriString();
        response.sendRedirect(targetUrl);
    }
}
