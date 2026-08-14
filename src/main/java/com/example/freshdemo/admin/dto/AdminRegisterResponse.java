package com.example.freshdemo.admin.dto;

import com.example.freshdemo.admin.domain.Admin;
import com.example.freshdemo.admin.domain.AdminRole;
import java.time.LocalDateTime;
import lombok.Builder;

/**
 * temporaryPassword는 이 응답 한 번에만 평문으로 실린다 — DB엔 해시만 저장되고(Admin.passwordHash),
 * 이후로는 어디서도 다시 조회할 수 없다(TempPasswordGenerator/AdminService.register() 참고).
 * 요구사항의 "임시 비밀번호 발급 후 첫 로그인 시 변경 강제" 중 "발급" 부분만 구현한 것이고,
 * "강제"는 별도 플래그/게이트 없이 프론트가 이 값을 받은 시점에 안내하는 방식으로 처리하기로 했다
 * (DESIGN_NOTES.md 참고 — 필드 추가 없이는 로그인마다 다시 강제할 방법이 없다).
 */
@Builder
public record AdminRegisterResponse(
        Long id,
        String loginId,
        String name,
        AdminRole role,
        String temporaryPassword,
        LocalDateTime createdAt
) {

    public static AdminRegisterResponse of(Admin admin, String temporaryPassword) {
        return AdminRegisterResponse.builder()
                .id(admin.getId())
                .loginId(admin.getLoginId())
                .name(admin.getName())
                .role(admin.getRole())
                .temporaryPassword(temporaryPassword)
                .createdAt(admin.getCreatedAt())
                .build();
    }
}
