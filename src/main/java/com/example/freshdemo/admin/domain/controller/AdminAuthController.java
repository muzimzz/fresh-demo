package com.example.freshdemo.admin.domain.controller;

import com.example.freshdemo.admin.domain.service.AdminTokenService;
import com.example.freshdemo.common.auth.AuthCookieFactory;
import com.example.freshdemo.common.auth.CustomUserDetails;
import com.example.freshdemo.common.auth.jwt.JwtTokenProvider;
import com.example.freshdemo.common.auth.jwt.TokenType;
import com.example.freshdemo.common.response.ResponseEnvelope;
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
 * 관리자 토큰 재발급/로그아웃. 실제 경로 /api/admin/reissue, /api/admin/logout.
 * member.domain.controller.MemberAuthController와 대칭 구조 — 배경은 그쪽 클래스 주석 참고.
 *
 * [주의] fresh-market 본 프로젝트로 이식할 때는 관리자 로그인/인증을 다른 팀원이 맡기로 해서, 이
 * 클래스(및 admin 도메인 전반)는 이식 대상에서 제외된다 — fresh-demo 자체 빌드/로컬 테스트를
 * 위해서만 유지한다.
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
class AdminAuthController {

    private final JwtTokenProvider jwtTokenProvider;
    private final AdminTokenService adminTokenService;
    private final AuthCookieFactory authCookieFactory;

    @PostMapping("/reissue")
    public ResponseEntity<ResponseEnvelope<Void>> reissue(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = resolveRefreshTokenFromCookie(request);
        if (refreshToken == null || !jwtTokenProvider.validateToken(refreshToken)) {
            throw new BadCredentialsException("유효하지 않은 refreshToken");
        }

        TokenType type = jwtTokenProvider.getType(refreshToken);
        String claimedRole = jwtTokenProvider.getRole(refreshToken);
        if (type != TokenType.ADMIN || claimedRole == null) {
            throw new BadCredentialsException("type/role 클레임 불일치");
        }

        Long adminId = jwtTokenProvider.getId(refreshToken);

        AdminTokenService.ReissueResult result = adminTokenService.reissue(adminId, claimedRole, refreshToken);

        response.addHeader(HttpHeaders.SET_COOKIE,
                authCookieFactory.accessTokenCookie(result.accessToken(), result.remember()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                authCookieFactory.refreshTokenCookie(result.refreshToken(), result.remember()).toString());

        return ResponseEntity.ok(ResponseEnvelope.success());
    }

    @PostMapping("/logout")
    public ResponseEntity<ResponseEnvelope<Void>> logout(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                           HttpServletResponse response) {
        adminTokenService.revoke(userDetails.getId(), userDetails.getRole());

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
