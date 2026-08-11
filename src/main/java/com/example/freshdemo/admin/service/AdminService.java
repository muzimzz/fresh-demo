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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        // DaoAuthenticationProvider가 BadCredentialsException으로 두 경우를 감춰주는 것을 수동으로 재현
        Admin admin = adminRepository.findByLoginId(loginId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PASSWORD));

        if (!passwordEncoder.matches(rawPassword, admin.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        String roleAuthority = admin.getRole().toAuthority();
        UUID adminId = admin.getId();

        // 관리자 로그인엔 "자동로그인" 체크박스 개념이 없다(스펙도 회원 섹션에만 있음) — 항상 영속 쿠키.
        String accessToken = jwtTokenProvider.createAccessToken(adminId, TokenType.ADMIN, roleAuthority);
        String refreshToken = jwtTokenProvider.createRefreshToken(adminId, TokenType.ADMIN, roleAuthority, true);

        refreshTokenRepository.save(roleAuthority, adminId, refreshToken,
                Duration.ofMillis(jwtTokenProvider.getRefreshTokenValidityMs()));

        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.accessTokenCookie(accessToken, true).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.refreshTokenCookie(refreshToken, true).toString());

        return admin;
    }

    @Transactional
    public Admin register(AdminRegisterRequest request, UUID requesterId) {
        Admin requester = adminRepository.findById(requesterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_NOT_FOUND));

        if (requester.getRole() != AdminRole.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.NOT_SUPER_ADMIN);
        }

        if (adminRepository.existsByLoginId(request.loginId())) {
            throw new BusinessException(ErrorCode.DUPLICATE_LOGIN_ID);
        }

        String encodedPassword = passwordEncoder.encode(request.password());
        return adminRepository.save(request.toEntity(encodedPassword));
    }

    @Transactional
    public void deleteAdmin(UUID targetAdminId, UUID requesterId) {
        Admin requester = adminRepository.findById(requesterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_NOT_FOUND));

        if (requester.getRole() != AdminRole.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.NOT_SUPER_ADMIN);
        }

        Admin target = adminRepository.findById(targetAdminId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_NOT_FOUND));

        // 삭제된 관리자가 들고 있던 refreshToken도 같이 지운다 — 안 지우면 계정은 삭제됐는데
        // 만료 전까지 재발급(reissue)만으로 계속 accessToken을 새로 받을 수 있게 된다.
        refreshTokenRepository.delete(target.getRole().toAuthority(), target.getId());

        adminRepository.deleteById(targetAdminId);
    }
}
