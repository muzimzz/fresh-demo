package com.example.freshdemo.admin.dto;

import com.example.freshdemo.admin.domain.Admin;
import com.example.freshdemo.admin.domain.AdminRole;
import jakarta.validation.constraints.NotBlank;

/**
 * SUPER_ADMIN이 새 관리자 계정을 발급할 때 쓰는 요청. role은 항상 ADMIN으로 시작한다
 * (SUPER_ADMIN 승격은 별도 절차로 두는 게 안전해서 이 API로는 지원하지 않는다).
 *
 * password 필드가 없다 — 요구사항의 "임시 비밀번호 발급"대로 호출자가 비밀번호를 정하지 않고
 * 서버가 랜덤 생성한다(AdminService.register(), TempPasswordGenerator 참고). 발급받은 관리자는
 * 이후 PATCH /admin/me/password로 직접 바꾼다.
 */
public record AdminRegisterRequest(
        @NotBlank String loginId,
        @NotBlank String name
) {

    public Admin toEntity(String encodedPassword) {
        return Admin.builder()
                .loginId(this.loginId)
                .passwordHash(encodedPassword)
                .name(this.name)
                .role(AdminRole.ADMIN)
                .build();
    }
}
