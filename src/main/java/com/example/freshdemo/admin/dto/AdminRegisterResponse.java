package com.example.freshdemo.admin.dto;

import com.example.freshdemo.admin.domain.Admin;
import com.example.freshdemo.admin.domain.AdminRole;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;

@Builder
public record AdminRegisterResponse(
        UUID id,
        String loginId,
        String name,
        AdminRole role,
        LocalDateTime createdAt
) {

    public static AdminRegisterResponse from(Admin admin) {
        return AdminRegisterResponse.builder()
                .id(admin.getId())
                .loginId(admin.getLoginId())
                .name(admin.getName())
                .role(admin.getRole())
                .createdAt(admin.getCreatedAt())
                .build();
    }
}
