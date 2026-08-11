package com.example.freshdemo.admin.controller;

import com.example.freshdemo.admin.domain.Admin;
import com.example.freshdemo.admin.dto.AdminLoginRequest;
import com.example.freshdemo.admin.dto.AdminLoginResponse;
import com.example.freshdemo.admin.dto.AdminRegisterRequest;
import com.example.freshdemo.admin.dto.AdminRegisterResponse;
import com.example.freshdemo.admin.service.AdminService;
import com.example.freshdemo.auth.CustomUserDetails;
import com.example.freshdemo.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 로그인 / 계정 발급·삭제(SUPER_ADMIN 전용) API. 실제 경로 /api/admin/**. */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AdminLoginResponse>> login(@RequestBody @Valid AdminLoginRequest request,
                                                      HttpServletResponse response) {
        Admin admin = adminService.login(request.loginId(), request.password(), response);
        return ResponseEntity.ok(ApiResponse.of(AdminLoginResponse.from(admin)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AdminRegisterResponse>> register(@RequestBody @Valid AdminRegisterRequest request,
                                                            @AuthenticationPrincipal CustomUserDetails requester) {
        Admin admin = adminService.register(request, requester.getId());
        // 리소스를 새로 만드는 요청이라 200 대신 201로 바꿨다.
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(AdminRegisterResponse.from(admin)));
    }

    @DeleteMapping("/{adminId}")
    public ResponseEntity<ApiResponse<Void>> deleteAdmin(@PathVariable UUID adminId,
                                             @AuthenticationPrincipal CustomUserDetails requester) {
        adminService.deleteAdmin(adminId, requester.getId());
        return ResponseEntity.ok(ApiResponse.of(null));
    }
}
