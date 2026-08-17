package com.example.freshdemo.admin.domain.service;

import com.example.freshdemo.admin.domain.AdminRegistrationResult;
import com.example.freshdemo.admin.domain.TempPasswordGenerator;
import com.example.freshdemo.admin.domain.entity.Admin;
import com.example.freshdemo.admin.domain.entity.AdminRole;
import com.example.freshdemo.admin.domain.entity.AdminStatus;
import com.example.freshdemo.admin.domain.repository.AdminRepository;
import com.example.freshdemo.admin.dto.AdminRegisterRequest;
import com.example.freshdemo.admin.exception.AdminErrorCode;
import com.example.freshdemo.admin.exception.AdminException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 계정 발급/삭제는 권한 상승/회수로 직결되는 민감한 액션이라, 누가(actorId) 언제 누구를
 * (targetId) 대상으로 했는지 감사 로그(event=ADMIN_*)를 남긴다 — 마스킹 없이 id만 찍는다
 * (DESIGN_NOTES.md 로깅 원칙: 비즈니스 로그는 원칙적으로 id만).
 *
 * [LG-fm 컨벤션 리팩토링] admin.domain.service로 이동, common.auth.jwt 패키지 경로, 예외 타입,
 * Admin.register() 팩토리 호출만 변경. 로직 무변경.
 *
 * [LG-fm 컨벤션 리팩토링 3차] 순환_의존이_없다 대응: JwtTokenProvider/RefreshTokenRepository/
 * AccessTokenValidAfterRepository/AuthCookieFactory 직접 호출을 전부 AdminTokenService로
 * 옮겼다. 토큰 관련 로직 자체는 무변경 — 호출 위치만 바뀌었다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminTokenService adminTokenService;

    @Transactional
    public Admin login(String loginId, String rawPassword, HttpServletResponse response) {
        Admin admin = adminRepository.findByLoginId(loginId)
                .orElseThrow(() -> {
                    log.warn("event=ADMIN_LOGIN_FAILED loginId={} reason=NO_SUCH_ACCOUNT", loginId);
                    return new AdminException(AdminErrorCode.INVALID_PASSWORD);
                });

        if (admin.isDeleted()) {
            log.warn("event=ADMIN_LOGIN_FAILED adminId={} reason=DELETED_ACCOUNT", admin.getId());
            throw new AdminException(AdminErrorCode.INVALID_PASSWORD);
        }

        if (!passwordEncoder.matches(rawPassword, admin.getPasswordHash())) {
            log.warn("event=ADMIN_LOGIN_FAILED adminId={} reason=WRONG_PASSWORD", admin.getId());
            throw new AdminException(AdminErrorCode.INVALID_PASSWORD);
        }

        adminTokenService.issue(admin, response);

        log.info("event=ADMIN_LOGIN_SUCCESS adminId={}", admin.getId());
        return admin;
    }

    @Transactional
    public AdminRegistrationResult register(AdminRegisterRequest request, Long requesterId) {
        Admin requester = adminRepository.findById(requesterId)
                .orElseThrow(() -> new AdminException(AdminErrorCode.ADMIN_NOT_FOUND));

        if (requester.getRole() != AdminRole.SUPER_ADMIN) {
            throw new AdminException(AdminErrorCode.NOT_SUPER_ADMIN);
        }

        if (adminRepository.existsByLoginId(request.loginId())) {
            throw new AdminException(AdminErrorCode.DUPLICATE_LOGIN_ID);
        }

        String temporaryPassword = TempPasswordGenerator.generate();
        String encodedPassword = passwordEncoder.encode(temporaryPassword);

        Admin created;
        try {
            created = adminRepository.saveAndFlush(Admin.register(request.loginId(), encodedPassword, request.name()));
            log.info("event=ADMIN_REGISTERED actorId={} targetId={} targetRole={}",
                    requesterId, created.getId(), created.getRole());
        } catch (DataIntegrityViolationException e) {
            log.warn("event=ADMIN_REGISTER_FAILED actorId={} reason=DUPLICATE_LOGIN_ID_RACE", requesterId);
            throw new AdminException(AdminErrorCode.DUPLICATE_LOGIN_ID, e);
        }

        return new AdminRegistrationResult(created, temporaryPassword);
    }

    @Transactional
    public void changePassword(Long adminId, String currentPassword, String newPassword) {
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new AdminException(AdminErrorCode.ADMIN_NOT_FOUND));

        if (!passwordEncoder.matches(currentPassword, admin.getPasswordHash())) {
            log.warn("event=ADMIN_PASSWORD_CHANGE_FAILED adminId={} reason=CURRENT_PASSWORD_MISMATCH", adminId);
            throw new AdminException(AdminErrorCode.CURRENT_PASSWORD_MISMATCH);
        }

        admin.changePassword(passwordEncoder.encode(newPassword));

        adminTokenService.revoke(adminId, admin.getRole().toAuthority());

        log.info("event=ADMIN_PASSWORD_CHANGED adminId={}", adminId);
    }

    @Transactional
    public void deleteAdmin(Long targetAdminId, Long requesterId) {
        Admin requester = adminRepository.findById(requesterId)
                .orElseThrow(() -> new AdminException(AdminErrorCode.ADMIN_NOT_FOUND));

        if (requester.getRole() != AdminRole.SUPER_ADMIN) {
            throw new AdminException(AdminErrorCode.NOT_SUPER_ADMIN);
        }

        if (targetAdminId.equals(requesterId)) {
            throw new AdminException(AdminErrorCode.CANNOT_DELETE_SELF);
        }

        Admin target = adminRepository.findById(targetAdminId)
                .orElseThrow(() -> new AdminException(AdminErrorCode.ADMIN_NOT_FOUND));

        if (target.isDeleted()) {
            throw new AdminException(AdminErrorCode.ADMIN_ALREADY_DELETED);
        }

        if (target.getRole() == AdminRole.SUPER_ADMIN
                && adminRepository.countByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE) <= 1) {
            throw new AdminException(AdminErrorCode.LAST_SUPER_ADMIN_CANNOT_BE_DELETED);
        }

        target.delete();

        adminTokenService.revoke(target.getId(), target.getRole().toAuthority());

        log.info("event=ADMIN_DELETED actorId={} targetId={}", requesterId, targetAdminId);
    }
}
