package com.example.freshdemo.member.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MemberProfileUpdateRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 50) String nickname,
        @Size(max = 20) String phone,
        @Size(max = 255) String address
) {
}
