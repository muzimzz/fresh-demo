package com.example.freshdemo.admin.dto;

import com.example.freshdemo.admin.domain.Admin;
import com.example.freshdemo.admin.domain.AdminRole;
import java.util.UUID;

public record AdminLoginResponse(UUID adminId, String name, AdminRole role) {

    public static AdminLoginResponse from(Admin admin) {
        return new AdminLoginResponse(admin.getId(), admin.getName(), admin.getRole());
    }
}
