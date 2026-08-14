package com.example.freshdemo.admin;

import com.example.freshdemo.admin.domain.entity.Admin;
import com.example.freshdemo.admin.domain.service.AdminRegistrationResult;
import com.example.freshdemo.admin.domain.service.AdminService;
import com.example.freshdemo.admin.dto.AdminLoginRequest;
import com.example.freshdemo.admin.dto.AdminLoginResponse;
import com.example.freshdemo.admin.dto.AdminPasswordChangeRequest;
import com.example.freshdemo.admin.dto.AdminRegisterRequest;
import com.example.freshdemo.admin.dto.AdminRegisterResponse;
import com.example.freshdemo.common.auth.CustomUserDetails;
import com.example.freshdemo.common.response.ResponseEnvelope;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 로그인 / 계정 발급·삭제(SUPER_ADMIN 전용) / 본인 비밀번호 변경 API. 실제 경로 /api/admin/**. */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @PostMapping("/login")
    public ResponseEntity<ResponseEnvelope<AdminLoginResponse>> login(@RequestBody @Valid AdminLoginRequest request,
                                                      HttpServletResponse response) {
        Admin admin = adminService.login(request.loginId(), request.password(), response);
        return ResponseEntity.ok(ResponseEnvelope.success(AdminLoginResponse.from(admin)));
    }

    @PostMapping
    public ResponseEntity<ResponseEnvelope<AdminRegisterResponse>> register(@RequestBody @Valid AdminRegisterRequest request,
                                                            @AuthenticationPrincipal CustomUserDetails requester) {
        AdminRegistrationResult result = adminService.register(request, requester.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseEnvelope.success(AdminRegisterResponse.of(result.admin(), result.temporaryPassword())));
    }

    @DeleteMapping("/{adminId}")
    public ResponseEntity<ResponseEnvelope<Void>> deleteAdmin(@PathVariable Long adminId,
                                             @AuthenticationPrincipal CustomUserDetails requester) {
        adminService.deleteAdmin(adminId, requester.getId());
        return ResponseEntity.ok(ResponseEnvelope.success());
    }

    @PatchMapping("/me/password")
    public ResponseEntity<ResponseEnvelope<Void>> changePassword(@RequestBody @Valid AdminPasswordChangeRequest request,
                                                              @AuthenticationPrincipal CustomUserDetails requester) {
        adminService.changePassword(requester.getId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.ok(ResponseEnvelope.success());
    }
}
