package com.example.freshdemo.admin.service;

import com.example.freshdemo.admin.domain.Admin;
import com.example.freshdemo.admin.domain.AdminRole;
import com.example.freshdemo.admin.domain.AdminStatus;
import com.example.freshdemo.admin.dto.AdminRegisterRequest;
import com.example.freshdemo.admin.repository.AdminRepository;
import com.example.freshdemo.auth.jwt.AccessTokenValidAfterRepository;
import com.example.freshdemo.auth.jwt.AuthCookieFactory;
import com.example.freshdemo.auth.jwt.JwtTokenProvider;
import com.example.freshdemo.auth.jwt.RefreshTokenRepository;
import com.example.freshdemo.auth.jwt.TokenType;
import com.example.freshdemo.common.exception.BusinessException;
import com.example.freshdemo.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 계정 발급/삭제는 권한 상승/회수로 직결되는 민감한 액션이라, 누가(actorId) 언제 누구를
 * (targetId) 대상으로 했는지 감사 로그(event=ADMIN_*)를 남긴다 — 마스킹 없이 id만 찍는다
 * (DESIGN_NOTES.md 로깅 원칙: 비즈니스 로그는 원칙적으로 id만).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository;
    private final AuthCookieFactory authCookieFactory;

    @Transactional(readOnly = true)
    public Admin login(String loginId, String rawPassword, HttpServletResponse response) {
        // 계정 존재 여부를 노출하지 않기 위해 "없음"과 "비번 틀림"을 같은 에러로 응답한다
        // DaoAuthenticationProvider가 BadCredentialsException으로 두 경우를 감춰주는 것을 수동으로 재현.
        // 이 구분은 "응답"에만 없는 거고, 우리끼리만 보는 로그엔 원인을 구분해서 남긴다 —
        // 브루트포스(특정 계정에 비번만 계속 틀림)와 계정 나열 공격(존재하지 않는 아이디를 계속 시도)을
        // 로그로 구분해서 볼 수 있어야 하기 때문.
        Admin admin = adminRepository.findByLoginId(loginId)
                .orElseThrow(() -> {
                    log.warn("event=ADMIN_LOGIN_FAILED loginId={} reason=NO_SUCH_ACCOUNT", loginId);
                    return new BusinessException(ErrorCode.INVALID_PASSWORD);
                });

        // 삭제(비활성화)된 계정도 login_id가 재사용되지 않아 findByLoginId로 계속 조회된다 — 응답은
        // "계정 없음"과 구분하지 않고 동일한 INVALID_PASSWORD로 감춘다(요구사항의 "실패 사유 미노출"과
        // "비활성 계정 로그인 불가"를 함께 만족). 로그에만 원인을 남긴다.
        if (admin.isDeleted()) {
            log.warn("event=ADMIN_LOGIN_FAILED adminId={} reason=DELETED_ACCOUNT", admin.getId());
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        if (!passwordEncoder.matches(rawPassword, admin.getPasswordHash())) {
            log.warn("event=ADMIN_LOGIN_FAILED adminId={} reason=WRONG_PASSWORD", admin.getId());
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        String roleAuthority = admin.getRole().toAuthority();
        Long adminId = admin.getId();

        // 관리자 로그인엔 "자동로그인" 체크박스 개념이 없다(스펙도 회원 섹션에만 있음) — 항상 영속 쿠키.
        String accessToken = jwtTokenProvider.createAccessToken(adminId, TokenType.ADMIN, roleAuthority);
        String refreshToken = jwtTokenProvider.createRefreshToken(adminId, TokenType.ADMIN, roleAuthority, true);

        refreshTokenRepository.save(TokenType.ADMIN, roleAuthority, adminId, refreshToken,
                Duration.ofMillis(jwtTokenProvider.getRefreshTokenValidityMs()));

        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.accessTokenCookie(accessToken, true).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.refreshTokenCookie(refreshToken, true).toString());

        log.info("event=ADMIN_LOGIN_SUCCESS adminId={}", adminId);
        return admin;
    }

    /**
     * 요구사항의 "임시 비밀번호 발급" — 호출자가 비밀번호를 정하지 않고 서버가 SecureRandom으로
     * 생성한다(TempPasswordGenerator). 평문은 이 메서드의 반환값(AdminRegistrationResult)에만
     * 잠깐 담기고 그 이후로는 어디에도(로그 포함) 남지 않는다 — DB엔 해시만 저장.
     */
    @Transactional
    public AdminRegistrationResult register(AdminRegisterRequest request, Long requesterId) {
        Admin requester = adminRepository.findById(requesterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_NOT_FOUND));

        if (requester.getRole() != AdminRole.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.NOT_SUPER_ADMIN);
        }

        if (adminRepository.existsByLoginId(request.loginId())) {
            throw new BusinessException(ErrorCode.DUPLICATE_LOGIN_ID);
        }

        String temporaryPassword = TempPasswordGenerator.generate();
        String encodedPassword = passwordEncoder.encode(temporaryPassword);

        Admin created;
        try {
            created = adminRepository.saveAndFlush(request.toEntity(encodedPassword));
            log.info("event=ADMIN_REGISTERED actorId={} targetId={} targetRole={}",
                    requesterId, created.getId(), created.getRole());
        } catch (DataIntegrityViolationException e) {
            log.warn("event=ADMIN_REGISTER_FAILED actorId={} reason=DUPLICATE_LOGIN_ID_RACE", requesterId);
            throw new BusinessException(ErrorCode.DUPLICATE_LOGIN_ID, e);
        }

        return new AdminRegistrationResult(created, temporaryPassword);
    }

    /**
     * 관리자 본인 비밀번호 변경. 요구사항 세부사항의 "변경 시 토큰 전량 폐기"를 따라 RT/AT를 모두
     * 무효화한다 — 비밀번호를 바꾼 이유가 유출 의심이었다면 기존 세션(탈취됐을 수 있는)도 같이
     * 끊어야 의미가 있고, 단순 변경이었어도 새 비밀번호로 재로그인시키는 게 일관적이다.
     */
    @Transactional
    public void changePassword(Long adminId, String currentPassword, String newPassword) {
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_NOT_FOUND));

        if (!passwordEncoder.matches(currentPassword, admin.getPasswordHash())) {
            log.warn("event=ADMIN_PASSWORD_CHANGE_FAILED adminId={} reason=CURRENT_PASSWORD_MISMATCH", adminId);
            throw new BusinessException(ErrorCode.CURRENT_PASSWORD_MISMATCH);
        }

        admin.changePassword(passwordEncoder.encode(newPassword));

        String roleAuthority = admin.getRole().toAuthority();
        refreshTokenRepository.delete(TokenType.ADMIN, roleAuthority, adminId);
        accessTokenValidAfterRepository.invalidateBefore(
                roleAuthority, adminId, LocalDateTime.now(),
                Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()));

        log.info("event=ADMIN_PASSWORD_CHANGED adminId={}", adminId);
    }

    /**
     * 관리자 삭제 = 소프트 삭제(status=DELETED). 목표 DDL이 "하드 삭제가 불가능하다"고 명시한다
     * (다른 도메인의 이력 테이블들이 admin_id를 참조하기 때문 — fresh-demo엔 아직 그 테이블들이
     * 없지만, DDL이 이미 그 미래를 가정하고 있어 맞춰둔다).
     */
    @Transactional
    public void deleteAdmin(Long targetAdminId, Long requesterId) {
        Admin requester = adminRepository.findById(requesterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_NOT_FOUND));

        if (requester.getRole() != AdminRole.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.NOT_SUPER_ADMIN);
        }

        // 요구사항 예외사항: "본인 및 마지막 최고관리자는 비활성화 불가". 본인 삭제부터 막는다 —
        // 그렇지 않으면 마지막 SUPER_ADMIN이 자기 자신을 지워서 아무도 관리자를 발급/삭제할 수
        // 없는 상태가 될 수 있다.
        if (targetAdminId.equals(requesterId)) {
            throw new BusinessException(ErrorCode.CANNOT_DELETE_SELF);
        }

        Admin target = adminRepository.findById(targetAdminId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_NOT_FOUND));

        if (target.isDeleted()) {
            throw new BusinessException(ErrorCode.ADMIN_ALREADY_DELETED);
        }

        // 대상이 SUPER_ADMIN이면, 이 삭제 후에도 ACTIVE한 SUPER_ADMIN이 최소 1명은 남아야 한다.
        // countByRoleAndStatus는 target 자신도 포함해서 세므로(아직 삭제 전), 1 이하면 target이
        // 마지막 남은 한 명이라는 뜻이다.
        if (target.getRole() == AdminRole.SUPER_ADMIN
                && adminRepository.countByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE) <= 1) {
            throw new BusinessException(ErrorCode.LAST_SUPER_ADMIN_CANNOT_BE_DELETED);
        }

        target.delete();

        String targetRole = target.getRole().toAuthority();

        // "즉시 토큰 폐기" — RT뿐 아니라 이미 발급된 AT도 커트라인을 등록해 즉시 무효화한다.
        // RT만 지우면 만료 전까지 발급된 AT는 그대로 유효해서(최대 accessToken TTL), 삭제 직후에도
        // 한동안 그 관리자 권한으로 요청이 통과할 수 있다(MemberWithdrawalService와 동일한 이유).
        refreshTokenRepository.delete(TokenType.ADMIN, targetRole, target.getId());
        accessTokenValidAfterRepository.invalidateBefore(
                targetRole, target.getId(), LocalDateTime.now(),
                Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()));

        log.info("event=ADMIN_DELETED actorId={} targetId={}", requesterId, targetAdminId);
    }
}
