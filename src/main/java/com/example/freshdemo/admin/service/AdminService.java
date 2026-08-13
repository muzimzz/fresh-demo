package com.example.freshdemo.admin.service;

import com.example.freshdemo.admin.domain.Admin;
import com.example.freshdemo.admin.domain.AdminRole;
import com.example.freshdemo.admin.dto.AdminRegisterRequest;
import com.example.freshdemo.admin.repository.AdminRepository;
import com.example.freshdemo.auth.jwt.AuthCookieFactory;
import com.example.freshdemo.auth.jwt.JwtTokenProvider;
import com.example.freshdemo.auth.jwt.RefreshTokenRepository;
import com.example.freshdemo.auth.jwt.TokenType;
import com.example.freshdemo.common.exception.BusinessException;
import com.example.freshdemo.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
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

    @Transactional
    public Admin register(AdminRegisterRequest request, Long requesterId) {
        Admin requester = adminRepository.findById(requesterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_NOT_FOUND));

        if (requester.getRole() != AdminRole.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.NOT_SUPER_ADMIN);
        }

        if (adminRepository.existsByLoginId(request.loginId())) {
            throw new BusinessException(ErrorCode.DUPLICATE_LOGIN_ID);
        }

        String encodedPassword = passwordEncoder.encode(request.password());

        Admin created;
        try {
            created = adminRepository.saveAndFlush(request.toEntity(encodedPassword));
            log.info("event=ADMIN_REGISTERED actorId={} targetId={} targetRole={}",
                    requesterId, created.getId(), created.getRole());
        } catch (DataIntegrityViolationException e) {
            log.warn("event=ADMIN_REGISTER_FAILED actorId={} reason=DUPLICATE_LOGIN_ID_RACE", requesterId);
            throw new BusinessException(ErrorCode.DUPLICATE_LOGIN_ID, e);
        }

        return created;
    }

    @Transactional
    public void deleteAdmin(Long targetAdminId, Long requesterId) {
        Admin requester = adminRepository.findById(requesterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_NOT_FOUND));

        if (requester.getRole() != AdminRole.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.NOT_SUPER_ADMIN);
        }

        Admin target = adminRepository.findById(targetAdminId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_NOT_FOUND));

        // 삭제된 관리자가 들고 있던 refreshToken도 같이 지운다 — 안 지우면 계정은 삭제됐는데
        // 만료 전까지 재발급(reissue)만으로 계속 accessToken을 새로 받을 수 있게 된다.
        refreshTokenRepository.delete(TokenType.ADMIN, target.getRole().toAuthority(), target.getId());

        adminRepository.deleteById(targetAdminId);

        log.info("event=ADMIN_DELETED actorId={} targetId={}", requesterId, targetAdminId);
    }
}
