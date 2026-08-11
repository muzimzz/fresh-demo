package com.example.freshdemo.auth.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * "자동로그인" 체크 여부를 카카오 인가 요청 시작 시점(?rememberMe=true)에 받아서 짧은 쿠키에
 * 잠깐 담아둔다 — 카카오로 리다이렉트됐다가 콜백(/login/oauth2/code/kakao)으로 돌아오는 왕복 동안
 * state/nonce처럼 서버가 뭔가를 들고 있어야 하는데, 이 프로젝트는 세션을 안 쓰므로(STATELESS)
 * 쿠키로 대신한다. OAuth2LoginSuccessHandler가 콜백 처리 끝에 이 쿠키를 읽고 지운다.
 *
 * SecurityConfig에서 OAuth2AuthorizationRequestRedirectFilter보다 앞에 등록해야
 * 카카오로 리다이렉트되기 전에 쿠키가 세팅된다.
 */
public class RememberMeRequestFilter extends OncePerRequestFilter {

    public static final String REMEMBER_ME_COOKIE = "remember_me";
    private static final String AUTHORIZATION_PATH_PART = "/oauth2/authorization/kakao";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (request.getRequestURI().contains(AUTHORIZATION_PATH_PART)
                && "true".equalsIgnoreCase(request.getParameter("rememberMe"))) {

            ResponseCookie cookie = ResponseCookie.from(REMEMBER_ME_COOKIE, "true")
                    .httpOnly(true)
                    .path("/")
                    .maxAge(Duration.ofMinutes(10)) // 카카오 인가~콜백 왕복 시간이면 충분
                    .sameSite("Lax")
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }

        filterChain.doFilter(request, response);
    }
}
