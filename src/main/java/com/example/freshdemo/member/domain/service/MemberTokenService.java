package com.example.freshdemo.member.domain.service;

import com.example.freshdemo.common.auth.AuthCookieFactory;
import com.example.freshdemo.common.auth.jwt.AccessTokenValidAfterRepository;
import com.example.freshdemo.common.auth.jwt.JwtTokenProvider;
import com.example.freshdemo.common.auth.jwt.RefreshTokenRepository;
import com.example.freshdemo.common.auth.jwt.TokenHasher;
import com.example.freshdemo.common.auth.jwt.TokenType;
import com.example.freshdemo.member.domain.client.KakaoLogoutClient;
import com.example.freshdemo.member.domain.entity.Member;
import com.example.freshdemo.member.domain.repository.MemberRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 로그인/재발급/로그아웃 시 토큰(access/refresh) 발급·회전·폐기를 담당. common.auth.jwt의
 * RefreshTokenRepository(순수 Redis)를 1차 저장소로 쓰고, Member 행의
 * refreshTokenHash/refreshTokenExpiresAt에 DB 백업을 write-through로 남긴다 — Redis 장애 시
 * (특히 reissue의 compareAndSave) DB CAS로 폴백한다.
 *
 * [LG-fm 컨벤션 리팩토링 3차] 순환_의존이_없다 ArchUnit 위반 해소: common.auth.AuthController가
 * MemberAuthApi를 거쳐 member를 참조하던 엣지(common→member)를 없애고, "회원 토큰을 어떻게
 * 다룰지"는 전부 member 도메인 안으로 옮겼다. common.auth.jwt.*(JwtTokenProvider,
 * RefreshTokenRepository, AccessTokenValidAfterRepository, AuthCookieFactory)는 회원/관리자를
 * 모르는 순수 유틸로 남고, 이 클래스가 그것들 + MemberRepository를 조합한다(member→common
 * 단방향이라 사이클이 아니다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberTokenService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository;
    private final AuthCookieFactory authCookieFactory;
    private final MemberRepository memberRepository;
    private final KakaoLogoutClient kakaoLogoutClient;

    public record ReissueResult(String accessToken, String refreshToken, boolean remember) {
    }

    /** 카카오 OIDC 로그인 성공 시 토큰 발급 + 쿠키 세팅까지. */
    @Transactional
    public void issue(Member member, boolean rememberMe, HttpServletResponse response) {
        Long memberId = member.getId();
        String role = member.getRole().name();
        Duration ttl = Duration.ofMillis(jwtTokenProvider.getRefreshTokenValidityMs());

        String accessToken = jwtTokenProvider.createAccessToken(memberId, TokenType.MEMBER, role);
        String refreshToken = jwtTokenProvider.createRefreshToken(memberId, TokenType.MEMBER, role, rememberMe);

        trySaveDbBackup(memberId, TokenHasher.sha256(refreshToken), LocalDateTime.now().plus(ttl));
        try {
            refreshTokenRepository.save(role, memberId, refreshToken, ttl);
        } catch (DataAccessException e) {
            log.warn("event=REDIS_SAVE_FAILED role={} id={} — DB 백업만 반영됨", role, memberId, e);
        }

        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.accessTokenCookie(accessToken, rememberMe).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.refreshTokenCookie(refreshToken, rememberMe).toString());
    }

    /**
     * POST /members/reissue용. 리프레시 토큰 서명·클레임 검증은 컨트롤러가 먼저 끝내고 넘겨준다.
     */
    @Transactional
    public ReissueResult reissue(Long memberId, String claimedRole, String oldRefreshToken, boolean remember) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BadCredentialsException("존재하지 않는 회원"));

        if (member.isWithdrawn()) {
            revoke(memberId, claimedRole, false);
            throw new BadCredentialsException("탈퇴한 회원");
        }

        String role = member.getRole().name();
        String newAccessToken = jwtTokenProvider.createAccessToken(memberId, TokenType.MEMBER, role);
        String newRefreshToken = jwtTokenProvider.createRefreshToken(memberId, TokenType.MEMBER, role, remember);
        Duration ttl = Duration.ofMillis(jwtTokenProvider.getRefreshTokenValidityMs());
        LocalDateTime expiresAt = LocalDateTime.now().plus(ttl);

        boolean rotated;
        try {
            rotated = refreshTokenRepository.compareAndSave(claimedRole, memberId, oldRefreshToken, newRefreshToken, ttl);
        } catch (DataAccessException e) {
            log.warn("event=REDIS_CAS_FAILED role={} id={} — DB CAS로 폴백", claimedRole, memberId, e);
            String oldHash = TokenHasher.sha256(oldRefreshToken);
            String newHash = TokenHasher.sha256(newRefreshToken);
            rotated = memberRepository.compareAndSetRefreshToken(memberId, oldHash, newHash, expiresAt) > 0;
        }

        if (!rotated) {
            revoke(memberId, claimedRole, false);
            log.warn("event=REFRESH_TOKEN_REUSE_SUSPECTED role={} id={} jti={}",
                    claimedRole, memberId, jwtTokenProvider.getJti(oldRefreshToken));
            throw new BadCredentialsException("refreshToken 재사용 의심");
        }

        trySaveDbBackup(memberId, TokenHasher.sha256(newRefreshToken), expiresAt);
        return new ReissueResult(newAccessToken, newRefreshToken, remember);
    }

    /**
     * 로그아웃/탈퇴 시 토큰 폐기. logoutExternalSession=true면 카카오 세션도 끊는다(일반
     * /members/logout에서만 true — 탈퇴 흐름은 MemberWithdrawalEvent/KakaoUnlinkEventListener가
     * 별도로 처리하므로 여기서 또 끊지 않는다).
     */
    @Transactional
    public void revoke(Long memberId, String role, boolean logoutExternalSession) {
        try {
            memberRepository.clearRefreshToken(memberId);
        } catch (DataAccessException e) {
            log.warn("event=DB_BACKUP_DELETE_FAILED memberId={} — DB 백업 삭제 실패(계속 진행)", memberId, e);
        }
        try {
            refreshTokenRepository.delete(role, memberId);
        } catch (DataAccessException e) {
            log.warn("event=REDIS_DELETE_FAILED role={} id={} — DB 백업만 반영됨", role, memberId, e);
        }
        accessTokenValidAfterRepository.invalidateBefore(role, memberId, LocalDateTime.now(),
                Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()));

        if (logoutExternalSession) {
            memberRepository.findById(memberId)
                    .map(Member::getProviderUserId)
                    .ifPresent(kakaoLogoutClient::logout);
        }
    }

    private void trySaveDbBackup(Long memberId, String tokenHash, LocalDateTime expiresAt) {
        try {
            int updated = memberRepository.updateRefreshToken(memberId, tokenHash, expiresAt);
            if (updated == 0) {
                log.warn("event=DB_BACKUP_SAVE_SKIPPED memberId={} — 대상 행을 찾지 못함", memberId);
            }
        } catch (DataAccessException e) {
            log.warn("event=DB_BACKUP_SAVE_FAILED memberId={} — Redis만 반영됨(DB 백업 유실 가능, 다음 쓰기 때 다시 시도됨)",
                    memberId, e);
        }
    }
}
