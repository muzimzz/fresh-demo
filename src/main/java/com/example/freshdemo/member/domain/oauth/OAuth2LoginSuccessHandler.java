package com.example.freshdemo.member.domain.oauth;

import com.example.freshdemo.common.auth.jwt.RememberMeRequestFilter;
import com.example.freshdemo.member.domain.entity.Member;
import com.example.freshdemo.member.domain.service.MemberTokenService;
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

/**
 * [LG-fm 컨벤션 리팩토링 3차] 순환_의존이_없다 대응: JwtTokenProvider/RefreshTokenRepository
 * 직접 호출을 MemberTokenService.issue()로 옮겼다. 로직 자체는 무변경.
 */
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final MemberTokenService memberTokenService;

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

        memberTokenService.issue(member, rememberMe, response);

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
