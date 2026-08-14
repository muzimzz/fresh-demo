package com.example.freshdemo.admin.dto;

import com.example.freshdemo.admin.domain.entity.Admin;
import com.example.freshdemo.admin.domain.entity.AdminRole;
import java.time.LocalDateTime;
import lombok.Builder;

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
