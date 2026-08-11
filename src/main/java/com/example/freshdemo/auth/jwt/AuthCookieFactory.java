package com.example.freshdemo.auth.jwt;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * accessToken/refreshToken 쿠키를 만들고 지우는 로직을 한 곳에 모은 것.
 * member/admin이 쿠키 이름("accessToken"/"refreshToken")을 공유한다 — 즉 한 브라우저에서
 * 회원 로그인과 관리자 로그인을 동시에 유지할 수는 없다(둘 다 켜져 있으면 나중 로그인이 덮어씀).
 * 이 프로젝트 규모에서는 감수할 만한 단순화라 판단했고, 필요해지면 쿠키 이름에 type을 접두사로
 * 붙이는 식으로 분리하면 된다.
 */
@Component
@RequiredArgsConstructor
public class AuthCookieFactory {

    private final JwtTokenProvider jwtTokenProvider;

    @Value("${jwt.cookie.secure:false}")
    private boolean secure; // TODO: 운영(https)에서는 반드시 true

    /**
     * persistent=false면 Max-Age를 아예 안 붙인다 — 브라우저가 "세션 쿠키"로 취급해서
     * 브라우저(탭이 아니라 프로세스)가 완전히 종료되면 같이 사라진다. "자동로그인" 미체크 시
     * 요구사항("브라우저 종료와 함께 만료")을 쿠키 레벨에서 구현한 것 — JWT 자체의 만료시간은
     * remember 여부와 무관하게 항상 고정이다(accessToken 1시간 / refreshToken 14일).
     */
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
