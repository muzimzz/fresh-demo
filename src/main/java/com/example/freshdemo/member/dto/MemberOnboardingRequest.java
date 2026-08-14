package com.example.freshdemo.member.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MemberOnboardingRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 50) String nickname,
        @AssertTrue(message = "약관에 동의해야 가입할 수 있습니다.") boolean termsAgreed,
        boolean marketingAgreed
) {
}
