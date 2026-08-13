package com.example.freshdemo.member.oauth;

import com.example.freshdemo.auth.jwt.AuthCookieFactory;
import com.example.freshdemo.auth.jwt.JwtTokenProvider;
import com.example.freshdemo.auth.jwt.RefreshTokenRepository;
import com.example.freshdemo.auth.jwt.RememberMeRequestFilter;
import com.example.freshdemo.auth.jwt.TokenType;
import com.example.freshdemo.member.domain.Member;
import com.example.freshdemo.member.domain.MemberRole;
import com.example.freshdemo.member.oauth.oidc.CustomOidcUser;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthCookieFactory authCookieFactory;

    @Value("${app.frontend.callback-url}")
    private String frontendCallbackUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {

        if (!(authentication.getPrincipal() instanceof CustomOidcUser oidcUser)) {
            throw new IllegalStateException("알 수 없는 principal 타입: " + authentication.getPrincipal().getClass());
        }

        Member member = oidcUser.getMember();
        boolean rememberMe = resolveRememberMe(request);

        Long memberId = member.getId();
        MemberRole memberRole = member.getRole();
        String role = memberRole.name();

        String accessToken = jwtTokenProvider.createAccessToken(memberId, TokenType.MEMBER, role);
        String refreshToken = jwtTokenProvider.createRefreshToken(memberId, TokenType.MEMBER, role, rememberMe);

        refreshTokenRepository.save(role, memberId, refreshToken, Duration.ofMillis(jwtTokenProvider.getRefreshTokenValidityMs()));

        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.accessTokenCookie(accessToken, rememberMe).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.refreshTokenCookie(refreshToken, rememberMe).toString());
        // RememberMeRequestFilter가 왕복용으로 잠깐 심어둔 쿠키는 용도를 다 했으니 지운다.
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(RememberMeRequestFilter.REMEMBER_ME_COOKIE, "")
                .httpOnly(true).path("/").maxAge(Duration.ZERO).sameSite("Lax").build().toString());

        // isNewMember(생성 이벤트) 대신 status 기반 pendingProfile을 내려준다 — 몇 번을 다시
        // 로그인해도(예: 온보딩 폼 작성 중 브라우저 종료) 매번 정확한 값이 나온다.
        String targetUrl = UriComponentsBuilder.fromUriString(frontendCallbackUrl)
                .queryParam("pendingProfile", member.isPendingProfile())
                .build().toUriString();

        response.sendRedirect(targetUrl);
    }

    private boolean resolveRememberMe(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return false;
        }
        for (Cookie cookie : request.getCookies()) {
            if (RememberMeRequestFilter.REMEMBER_ME_COOKIE.equals(cookie.getName())) {
                return "true".equalsIgnoreCase(cookie.getValue());
            }
        }
        return false;
    }
}
