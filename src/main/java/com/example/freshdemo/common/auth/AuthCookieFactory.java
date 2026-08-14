package com.example.freshdemo.common.auth;

import com.example.freshdemo.common.auth.jwt.JwtTokenProvider;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * accessToken/refreshToken 쿠키를 만들고 지우는 로직을 한 곳에 모은 것. member/admin이 쿠키 이름을
 * 공유한다(한 브라우저에서 회원/관리자 로그인 동시 유지 불가 — 감수하기로 한 단순화).
 * [LG-fm 컨벤션 리팩토링] common.auth로 이동, 로직 무변경(context-path=/api 유지라 refreshToken
 * 쿠키 path도 "/api/auth" 그대로).
 */
@Component
@RequiredArgsConstructor
public class AuthCookieFactory {

    private final JwtTokenProvider jwtTokenProvider;

    @Value("${jwt.cookie.secure:false}")
    private boolean secure; // TODO: 운영(https)에서는 반드시 true

    public ResponseCookie accessTokenCookie(String accessToken, boolean persistent) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from("accessToken", accessToken)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .sameSite("Lax");
        if (persistent) {
            builder.maxAge(Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()));
        }
        return builder.build();
    }

    public ResponseCookie refreshTokenCookie(String refreshToken, boolean persistent) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(secure)
                .path("/api/auth")
                .sameSite("Lax");
        if (persistent) {
            builder.maxAge(Duration.ofMillis(jwtTokenProvider.getRefreshTokenValidityMs()));
        }
        return builder.build();
    }

    public ResponseCookie expiredAccessTokenCookie() {
        return ResponseCookie.from("accessToken", "")
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .maxAge(Duration.ZERO)
                .sameSite("Lax")
                .build();
    }

    public ResponseCookie expiredRefreshTokenCookie() {
        return ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(secure)
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .sameSite("Lax")
                .build();
    }
}
