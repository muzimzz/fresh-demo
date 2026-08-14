package com.example.freshdemo.admin.domain.entity;

public enum AdminRole {

    ADMIN,
    SUPER_ADMIN,
    ;

    public String toAuthority() {
        return "ROLE_" + name();
    }
}
