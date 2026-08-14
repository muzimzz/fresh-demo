package com.example.freshdemo.common.auth.jwt;

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
 * 잠깐 담아둔다. [LG-fm 컨벤션 리팩토링] common.auth.jwt로 이동, 로직 무변경.
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
                    .maxAge(Duration.ofMinutes(10))
                    .sameSite("Lax")
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }

        filterChain.doFilter(request, response);
    }
}
