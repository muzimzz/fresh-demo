package com.example.freshdemo.admin.domain.service;

import com.example.freshdemo.admin.domain.entity.Admin;
import com.example.freshdemo.admin.domain.repository.AdminRepository;
import com.example.freshdemo.common.auth.AuthCookieFactory;
import com.example.freshdemo.common.auth.jwt.AccessTokenValidAfterRepository;
import com.example.freshdemo.common.auth.jwt.JwtTokenProvider;
import com.example.freshdemo.common.auth.jwt.RefreshTokenRepository;
import com.example.freshdemo.common.auth.jwt.TokenHasher;
import com.example.freshdemo.common.auth.jwt.TokenType;
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
 * 관리자 로그인/재발급/로그아웃(+비밀번호 변경, 계정 삭제) 시 토큰 발급·회전·폐기를 담당.
 * member.domain.service.MemberTokenService와 대칭 구조 — 자세한 설계 배경은 그쪽 클래스 주석 참고.
 *
 * [주의] fresh-market 본 프로젝트로 이식할 때는 관리자 로그인/인증을 다른 팀원이 맡기로 해서, 이
 * 클래스(및 admin 도메인 전반)는 이식 대상에서 제외된다 — fresh-demo 자체 빌드/로컬 테스트를
 * 위해서만 유지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminTokenService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository;
    private final AuthCookieFactory authCookieFactory;
    private final AdminRepository adminRepository;

    public record ReissueResult(String accessToken, String refreshToken, boolean remember) {
    }

    @Transactional
    public void issue(Admin admin, HttpServletResponse response) {
        Long adminId = admin.getId();
        String role = admin.getRole().toAuthority();
        Duration ttl = Duration.ofMillis(jwtTokenProvider.getRefreshTokenValidityMs());

        String accessToken = jwtTokenProvider.createAccessToken(adminId, TokenType.ADMIN, role);
        String refreshToken = jwtTokenProvider.createRefreshToken(adminId, TokenType.ADMIN, role, true);

        trySaveDbBackup(adminId, TokenHasher.sha256(refreshToken), LocalDateTime.now().plus(ttl));
        try {
            refreshTokenRepository.save(role, adminId, refreshToken, ttl);
        } catch (DataAccessException e) {
            log.warn("event=REDIS_SAVE_FAILED role={} id={} — DB 백업만 반영됨", role, adminId, e);
        }

        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.accessTokenCookie(accessToken, true).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.refreshTokenCookie(refreshToken, true).toString());
    }

    /** POST /admin/reissue용. */
    @Transactional
    public ReissueResult reissue(Long adminId, String claimedRole, String oldRefreshToken) {
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new BadCredentialsException("존재하지 않는 관리자"));

        String role = admin.getRole().toAuthority();
        String newAccessToken = jwtTokenProvider.createAccessToken(adminId, TokenType.ADMIN, role);
        String newRefreshToken = jwtTokenProvider.createRefreshToken(adminId, TokenType.ADMIN, role, true);
        Duration ttl = Duration.ofMillis(jwtTokenProvider.getRefreshTokenValidityMs());
        LocalDateTime expiresAt = LocalDateTime.now().plus(ttl);

        boolean rotated;
        try {
            rotated = refreshTokenRepository.compareAndSave(claimedRole, adminId, oldRefreshToken, newRefreshToken, ttl);
        } catch (DataAccessException e) {
            log.warn("event=REDIS_CAS_FAILED role={} id={} — DB CAS로 폴백", claimedRole, adminId, e);
            String oldHash = TokenHasher.sha256(oldRefreshToken);
            String newHash = TokenHasher.sha256(newRefreshToken);
            rotated = adminRepository.compareAndSetRefreshToken(adminId, oldHash, newHash, expiresAt) > 0;
        }

        if (!rotated) {
            revoke(adminId, claimedRole);
            log.warn("event=REFRESH_TOKEN_REUSE_SUSPECTED role={} id={} jti={}",
                    claimedRole, adminId, jwtTokenProvider.getJti(oldRefreshToken));
            throw new BadCredentialsException("refreshToken 재사용 의심");
        }

        trySaveDbBackup(adminId, TokenHasher.sha256(newRefreshToken), expiresAt);
        return new ReissueResult(newAccessToken, newRefreshToken, true);
    }

    /** 로그아웃/비밀번호 변경/계정 삭제 시 토큰 폐기. */
    @Transactional
    public void revoke(Long adminId, String role) {
        try {
            adminRepository.clearRefreshToken(adminId);
        } catch (DataAccessException e) {
            log.warn("event=DB_BACKUP_DELETE_FAILED adminId={} — DB 백업 삭제 실패(계속 진행)", adminId, e);
        }
        try {
            refreshTokenRepository.delete(role, adminId);
        } catch (DataAccessException e) {
            log.warn("event=REDIS_DELETE_FAILED role={} id={} — DB 백업만 반영됨", role, adminId, e);
        }
        accessTokenValidAfterRepository.invalidateBefore(role, adminId, LocalDateTime.now(),
                Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()));
    }

    private void trySaveDbBackup(Long adminId, String tokenHash, LocalDateTime expiresAt) {
        try {
            int updated = adminRepository.updateRefreshToken(adminId, tokenHash, expiresAt);
            if (updated == 0) {
                log.warn("event=DB_BACKUP_SAVE_SKIPPED adminId={} — 대상 행을 찾지 못함", adminId);
            }
        } catch (DataAccessException e) {
            log.warn("event=DB_BACKUP_SAVE_FAILED adminId={} — Redis만 반영됨(DB 백업 유실 가능, 다음 쓰기 때 다시 시도됨)",
                    adminId, e);
        }
    }
}
