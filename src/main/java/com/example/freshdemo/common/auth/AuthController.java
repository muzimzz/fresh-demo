package com.example.freshdemo.common.auth;

import com.example.freshdemo.admin.domain.entity.Admin;
import com.example.freshdemo.admin.domain.repository.AdminRepository;
import com.example.freshdemo.admin.exception.AdminErrorCode;
import com.example.freshdemo.admin.exception.AdminException;
import com.example.freshdemo.common.auth.jwt.AccessTokenValidAfterRepository;
import com.example.freshdemo.common.auth.jwt.JwtTokenProvider;
import com.example.freshdemo.common.auth.jwt.RefreshTokenRepository;
import com.example.freshdemo.common.auth.jwt.TokenType;
import com.example.freshdemo.common.response.ResponseEnvelope;
import com.example.freshdemo.member.domain.client.KakaoLogoutClient;
import com.example.freshdemo.member.domain.entity.Member;
import com.example.freshdemo.member.domain.repository.MemberRepository;
import com.example.freshdemo.member.exception.MemberErrorCode;
import com.example.freshdemo.member.exception.MemberException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토큰 재발급 / 로그아웃 API. context-path가 /api라서 실제 경로는 /api/auth/**.
 * member(MEMBER)·admin(ADMIN) 공용 — RefreshTokenRepository와 같은 이유로 common.auth 소속.
 *
 * [LG-fm 컨벤션 리팩토링] member.controller.AuthController에서 common.auth로 이동. 응답 실패
 * 처리도 바꿨다 — 기존엔 BusinessException(ErrorCode.UNAUTHORIZED)를 던졌는데, "인증 자체가 안
 * 된" 상황(리프레시 토큰 무효/재사용 의심)은 도메인 정책 위반이라기보다 인증 실패라고 보고
 * Spring Security의 BadCredentialsException(AuthenticationException 하위)을 던지도록 바꿨다 —
 * GlobalExceptionHandler가 AuthenticationException을 CommonErrorCode.UNAUTHENTICATED로 이미
 * 처리하므로 별도 예외 클래스 없이 일관된 응답이 나간다.
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository;
    private final MemberRepository memberRepository;
    private final AdminRepository adminRepository;
    private final AuthCookieFactory authCookieFactory;
    private final KakaoLogoutClient kakaoLogoutClient;

    @PostMapping("/reissue")
    public ResponseEntity<ResponseEnvelope<Void>> reissue(HttpServletRequest request, HttpServletResponse response) {

        String refreshToken = resolveRefreshTokenFromCookie(request);
        if (refreshToken == null || !jwtTokenProvider.validateToken(refreshToken)) {
            throw new BadCredentialsException("유효하지 않은 refreshToken");
        }

        Long id = jwtTokenProvider.getId(refreshToken);
        TokenType type = jwtTokenProvider.getType(refreshToken);
        String claimedRole = jwtTokenProvider.getRole(refreshToken);

        if (type == null || claimedRole == null) {
            throw new BadCredentialsException("type/role 클레임 누락");
        }

        String role = (type == TokenType.MEMBER)
                ? reissueMemberRole(id, claimedRole)
                : reissueAdminRole(id);

        boolean remember = jwtTokenProvider.getRemember(refreshToken);

        String accessToken = jwtTokenProvider.createAccessToken(id, type, role);
        String newRefreshToken = jwtTokenProvider.createRefreshToken(id, type, role, remember);

        boolean rotated = refreshTokenRepository.compareAndSave(
                type, claimedRole, id, refreshToken, newRefreshToken,
                Duration.ofMillis(jwtTokenProvider.getRefreshTokenValidityMs()));

        if (!rotated) {
            refreshTokenRepository.delete(type, claimedRole, id);
            accessTokenValidAfterRepository.invalidateBefore(
                    claimedRole, id, LocalDateTime.now(),
                    Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()));
            log.warn("event=REFRESH_TOKEN_REUSE_SUSPECTED role={} id={} jti={}",
                    claimedRole, id, jwtTokenProvider.getJti(refreshToken));
            throw new BadCredentialsException("refreshToken 재사용 의심");
        }

        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.accessTokenCookie(accessToken, remember).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.refreshTokenCookie(newRefreshToken, remember).toString());

        return ResponseEntity.ok(ResponseEnvelope.success());
    }

    private String reissueMemberRole(Long memberId, String claimedRole) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

        if (member.isWithdrawn()) {
            refreshTokenRepository.delete(TokenType.MEMBER, claimedRole, memberId);
            throw new BadCredentialsException("탈퇴한 회원");
        }

        return member.getRole().name();
    }

    private String reissueAdminRole(Long adminId) {
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new AdminException(AdminErrorCode.ADMIN_NOT_FOUND));

        return admin.getRole().toAuthority();
    }

    @PostMapping("/logout")
    public ResponseEntity<ResponseEnvelope<Void>> logout(@AuthenticationPrincipal CustomUserDetails userDetails, HttpServletResponse response) {

        refreshTokenRepository.delete(userDetails.getType(), userDetails.getRole(), userDetails.getId());

        if (userDetails.getType() == TokenType.MEMBER) {
            memberRepository.findById(userDetails.getId())
                    .ifPresent(member -> kakaoLogoutClient.logout(member.getProviderUserId()));
        }

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
