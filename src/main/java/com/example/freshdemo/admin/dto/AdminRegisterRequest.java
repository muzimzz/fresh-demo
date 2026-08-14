package com.example.freshdemo.admin.dto;

import jakarta.validation.constraints.NotBlank;

// [LG-fm 컨벤션 리팩토링] toEntity()는 AdminService.register()로 옮겼다.
public record AdminRegisterRequest(
        @NotBlank String loginId,
        @NotBlank String name
) {
}
