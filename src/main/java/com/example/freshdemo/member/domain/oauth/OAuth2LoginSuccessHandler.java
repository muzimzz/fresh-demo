package com.example.freshdemo.member.domain.oauth;

import com.example.freshdemo.common.auth.AuthCookieFactory;
import com.example.freshdemo.common.auth.jwt.JwtTokenProvider;
import com.example.freshdemo.common.auth.jwt.RefreshTokenRepository;
import com.example.freshdemo.common.auth.jwt.RememberMeRequestFilter;
import com.example.freshdemo.common.auth.jwt.TokenType;
import com.example.freshdemo.member.domain.entity.Member;
import com.example.freshdemo.member.domain.entity.MemberRole;
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

        refreshTokenRepository.save(TokenType.MEMBER, role, memberId, refreshToken, Duration.ofMillis(jwtTokenProvider.getRefreshTokenValidityMs()));

        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.accessTokenCookie(accessToken, rememberMe).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.refreshTokenCookie(refreshToken, rememberMe).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(RememberMeRequestFilter.REMEMBER_ME_COOKIE, "")
                .httpOnly(true).path("/").maxAge(Duration.ZERO).sameSite("Lax").build().toString());

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
