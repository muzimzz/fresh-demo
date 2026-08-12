package com.example.freshdemo.member.controller;

import com.example.freshdemo.admin.domain.Admin;
import com.example.freshdemo.admin.repository.AdminRepository;
import com.example.freshdemo.auth.CustomUserDetails;
import com.example.freshdemo.auth.jwt.AccessTokenValidAfterRepository;
import com.example.freshdemo.auth.jwt.AuthCookieFactory;
import com.example.freshdemo.auth.jwt.JwtTokenProvider;
import com.example.freshdemo.auth.jwt.RefreshTokenRepository;
import com.example.freshdemo.auth.jwt.TokenType;
import com.example.freshdemo.common.exception.BusinessException;
import com.example.freshdemo.common.exception.ErrorCode;
import com.example.freshdemo.common.response.ApiResponse;
import com.example.freshdemo.member.client.KakaoLogoutClient;
import com.example.freshdemo.member.domain.Member;
import com.example.freshdemo.member.repository.MemberRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토큰 재발급 / 로그아웃 API. context-path가 /api라서 실제 경로는 /api/auth/**.
 * refreshToken은 httpOnly 쿠키로만 오간다.
 *
 * 회원(MEMBER)·관리자(ADMIN) 공용 — refreshToken의 type 클레임으로 어느 쪽인지 갈라서 처리한다.
 * 엔드포인트를 /admin/reissue 식으로 따로 안 둔 이유: 재발급·로그아웃은 로직이 "DB에서 최신 role을
 * 다시 읽어와 토큰을 새로 발급"이라는 점에서 본질적으로 같아서, 나누면 중복만 늘어난다.
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
    public ResponseEntity<ApiResponse<Void>> reissue(HttpServletRequest request, HttpServletResponse response) {

        String refreshToken = resolveRefreshTokenFromCookie(request);
        if (refreshToken == null || !jwtTokenProvider.validateToken(refreshToken)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        UUID id = jwtTokenProvider.getId(refreshToken);
        TokenType type = jwtTokenProvider.getType(refreshToken);
        String claimedRole = jwtTokenProvider.getRole(refreshToken);

        if (type == null || claimedRole == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // DB에서 최신 상태를 다시 읽는다 — role이 바뀌었거나(관리자) 탈퇴했으면(회원) 반영해야 하므로
        String role = (type == TokenType.MEMBER)
                ? reissueMemberRole(id, claimedRole)
                : reissueAdminRole(id);

        // remember-me 여부는 이전 refreshToken의 claim을 그대로 이어간다 — 재발급할 때마다
        // 사용자가 다시 체크박스를 누를 수는 없으니, 최초 로그인 때 정한 값을 rotation 내내 유지.
        boolean remember = jwtTokenProvider.getRemember(refreshToken);

        String accessToken = jwtTokenProvider.createAccessToken(id, type, role);
        String newRefreshToken = jwtTokenProvider.createRefreshToken(id, type, role, remember); // Refresh Token Rotation (RTR)

        // "저장된 값이 지금 쿠키로 들어온 옛 토큰과 같을 때만 새 토큰으로 교체"를 원자적으로 수행한다 —
        // 조회와 저장을 분리했을 때 생기는 동시 요청 race(같은 옛 토큰으로 두 재발급 요청이 겹치는 경우)를 없앤다.
        boolean rotated = refreshTokenRepository.compareAndSave(
                claimedRole, id, refreshToken, newRefreshToken,
                Duration.ofMillis(jwtTokenProvider.getRefreshTokenValidityMs()));

        if (!rotated) {
            // 이미 다른 요청이 먼저 교체했거나(정상적인 동시 요청 race), 이미 폐기된 옛 토큰이 다시
            // 들어온 것(탈취 의심)일 수 있다 — 어느 쪽인지 구분할 방법이 없으니 안전하게 재사용으로
            // 간주해 세션을 통째로 무효화한다. (role, id)당 세션이 하나뿐인 키 구조라 삭제 한 번으로
            // 완전히 끊긴다 — 진짜 사용자도 다시 로그인해야 하지만, 탈취 가능성을 방치하는 것보다 낫다.
            //
            // RT뿐 아니라 AT도 같이 무효화한다 — RT가 탈취됐다는 건 같은 시점에 발급된 AT도 탈취됐을
            // 가능성이 있다는 뜻이라, RT 재발급만 막고 이미 살아있는 AT를 그대로 두면 탈취범이 그
            // AT의 남은 수명(최대 1시간) 동안은 계속 정상 요청을 보낼 수 있다. cutoff=지금 시각으로
            // 등록하면 재로그인해서 새로 받는 토큰(iat가 cutoff 이후)은 영향받지 않는다.
            refreshTokenRepository.delete(claimedRole, id);
            accessTokenValidAfterRepository.invalidateBefore(
                    claimedRole, id, LocalDateTime.now(),
                    Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()));
            // jti는 서명 없는 순수 식별자 라벨이라 평문으로 남겨도 안전하다(JwtTokenProvider.createRefreshToken() 참고) —
            // 나중에 "정확히 어떤 토큰 인스턴스가 재사용됐는지" 로그로 추적할 수 있게.
            log.warn("event=REFRESH_TOKEN_REUSE_SUSPECTED role={} id={} jti={}",
                    claimedRole, id, jwtTokenProvider.getJti(refreshToken));
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.accessTokenCookie(accessToken, remember).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.refreshTokenCookie(newRefreshToken, remember).toString());

        return ResponseEntity.ok(ApiResponse.of(null));
    }

    private String reissueMemberRole(UUID memberId, String claimedRole) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        if (member.isWithdrawn()) {
            // 재발급 시점에 탈퇴 상태면 refreshToken도 같이 지워서 이후 재시도를 막는다
            refreshTokenRepository.delete(claimedRole, memberId);
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        return member.getRole().name();
    }

    private String reissueAdminRole(UUID adminId) {
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_NOT_FOUND));

        return admin.getRole().toAuthority();
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@AuthenticationPrincipal CustomUserDetails userDetails, HttpServletResponse response) {

        refreshTokenRepository.delete(userDetails.getRole(), userDetails.getId());

        // 회원일 때만 카카오 쪽도 정리한다 — 관리자는 카카오와 무관(ID/PW 인증)이라 해당 없음.
        // 실패해도 우리 서비스 로그아웃 자체는 이미 끝난 뒤라 응답에 영향 없다(KakaoLogoutClient 내부에서 흡수).
        if (userDetails.getType() == TokenType.MEMBER) {
            memberRepository.findById(userDetails.getId())
                    .ifPresent(member -> kakaoLogoutClient.logout(member.getSocialTypeId()));
        }

        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.expiredAccessTokenCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.expiredRefreshTokenCookie().toString());

        return ResponseEntity.ok(ApiResponse.of(null));
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
