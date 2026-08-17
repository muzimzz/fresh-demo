package com.example.freshdemo.member.domain.controller;

import com.example.freshdemo.common.auth.AuthCookieFactory;
import com.example.freshdemo.common.auth.CustomUserDetails;
import com.example.freshdemo.common.auth.jwt.JwtTokenProvider;
import com.example.freshdemo.common.auth.jwt.TokenType;
import com.example.freshdemo.common.response.ResponseEnvelope;
import com.example.freshdemo.member.domain.service.MemberTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 토큰 재발급/로그아웃. 실제 경로 /api/members/reissue, /api/members/logout.
 *
 * [LG-fm 컨벤션 리팩토링 3차] 순환_의존이_없다 대응: common.auth.AuthController(회원/관리자
 * 공용, common→member/admin 엣지의 근원)를 도메인별로 쪼갠 결과물 중 회원 쪽이다.
 * "/auth/reissue"였던 경로가 "/members/reissue"로 바뀌었다 — 프론트 연동 시 반영 필요.
 * SecurityConfig의 permitAll 목록도 같이 바꿨다(POST /members/reissue만 인증 없이 허용,
 * /members/logout은 기존 "/members/**" 규칙대로 TYPE_MEMBER 인증 필요).
 */
@RestController
@RequestMapping("/members")
@RequiredArgsConstructor
class MemberAuthController {

    private final JwtTokenProvider jwtTokenProvider;
    private final MemberTokenService memberTokenService;
    private final AuthCookieFactory authCookieFactory;

    @PostMapping("/reissue")
    public ResponseEntity<ResponseEnvelope<Void>> reissue(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = resolveRefreshTokenFromCookie(request);
        if (refreshToken == null || !jwtTokenProvider.validateToken(refreshToken)) {
            throw new BadCredentialsException("유효하지 않은 refreshToken");
        }

        TokenType type = jwtTokenProvider.getType(refreshToken);
        String claimedRole = jwtTokenProvider.getRole(refreshToken);
        if (type != TokenType.MEMBER || claimedRole == null) {
            throw new BadCredentialsException("type/role 클레임 불일치");
        }

        Long memberId = jwtTokenProvider.getId(refreshToken);
        boolean remember = jwtTokenProvider.getRemember(refreshToken);

        MemberTokenService.ReissueResult result = memberTokenService.reissue(memberId, claimedRole, refreshToken, remember);

        response.addHeader(HttpHeaders.SET_COOKIE,
                authCookieFactory.accessTokenCookie(result.accessToken(), result.remember()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                authCookieFactory.refreshTokenCookie(result.refreshToken(), result.remember()).toString());

        return ResponseEntity.ok(ResponseEnvelope.success());
    }

    @PostMapping("/logout")
    public ResponseEntity<ResponseEnvelope<Void>> logout(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                           HttpServletResponse response) {
        memberTokenService.revoke(userDetails.getId(), userDetails.getRole(), true);

        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.expiredAccessTokenCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.expiredRefreshTokenCookie().toString());

        return ResponseEntity.ok(ResponseEnvelope.success());
    }

    private String resolveRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("refreshToken".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
